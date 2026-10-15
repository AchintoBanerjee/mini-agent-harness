package harness.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.core.Conversation;
import harness.core.Orchestrator;
import harness.core.RelaySummarizer;
import harness.core.Span;
import harness.model.RuleBasedLLM;
import harness.store.Job;
import harness.store.RunStore;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * The harness as a small service on the JDK's own HttpServer, JSON in and out:
 * GET /agents, GET and PUT /agents/{name}, GET /tools, POST /runs (202 with the job id at
 * once, the loop runs on a virtual thread), GET /runs/{id} (the job row, its steps, the
 * answer when DONE) and GET /runs/{id}/events (Server-Sent Events: every stored step from
 * Last-Event-ID on, then live until the job ends). Agents and tools stay files on disk;
 * runs live in the store. A client that goes away does not stop its run.
 */
public class HttpApi {

    /** How often an open event stream looks at the store for new rows. */
    public static final long POLL_MS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path agentsDir;
    private final Path toolsDir;
    private final RunStore store;
    private HttpServer server;
    private volatile boolean stopped;

    public HttpApi(Path agentsDir, Path toolsDir, RunStore store) {
        this.agentsDir = agentsDir;
        this.toolsDir = toolsDir;
        this.store = store;
    }

    /** Binds localhost:port (0 = any free port) and serves each request on a virtual thread. */
    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Stops listening; an open event stream ends at its next poll. Runs in flight finish on their own. */
    public void stop() {
        stopped = true;
        server.stop(0);
    }

    /** One line per request on stdout: the server's log. Errors become a JSON {"error": ...}. */
    private void handle(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String outcome;
        try {
            outcome = String.valueOf(route(exchange, method, path));
        } catch (HttpError e) {
            outcome = String.valueOf(sendError(exchange, e.status, e.getMessage()));
        } catch (IOException e) {
            outcome = "client went away (" + e.getMessage() + ")";
        } catch (RuntimeException e) {
            outcome = String.valueOf(sendError(exchange, 500, e.toString()));
        } finally {
            exchange.close();
        }
        System.out.println(method + " " + path + " -> " + outcome);
    }

    private int route(HttpExchange exchange, String method, String path) throws IOException {
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        boolean agents = parts[0].equals("agents");
        boolean runs = parts[0].equals("runs");
        if (parts.length == 1 && agents) {
            return method.equals("GET") ? listAgents(exchange) : methodNotAllowed(method, path);
        }
        if (parts.length == 2 && agents) {
            return switch (method) {
                case "GET" -> getAgent(exchange, parts[1]);
                case "PUT" -> putAgent(exchange, parts[1]);
                default -> methodNotAllowed(method, path);
            };
        }
        if (parts.length == 1 && parts[0].equals("tools")) {
            return method.equals("GET") ? listTools(exchange) : methodNotAllowed(method, path);
        }
        if (parts.length == 1 && runs) {
            return method.equals("POST") ? startRun(exchange) : methodNotAllowed(method, path);
        }
        if (parts.length == 2 && runs) {
            return method.equals("GET") ? getRun(exchange, parts[1]) : methodNotAllowed(method, path);
        }
        if (parts.length == 3 && runs && parts[2].equals("events")) {
            return method.equals("GET") ? events(exchange, parts[1]) : methodNotAllowed(method, path);
        }
        throw new HttpError(404, "no endpoint " + method + " " + path);
    }

    // --- agents and tools: files on disk ---

    private int listAgents(HttpExchange exchange) throws IOException {
        ArrayNode list = JSON.createArrayNode();
        for (Agent agent : AgentLoader.loadAll(agentsDir)) {
            list.add(agentJson(agent));
        }
        return send(exchange, 200, list);
    }

    private int getAgent(HttpExchange exchange, String name) throws IOException {
        Agent agent = findAgent(name).orElseThrow(() -> new HttpError(404, "no agent named " + name));
        return send(exchange, 200, agentJson(agent));
    }

    /** The builder experience: the body is the agent file; it is checked, then written to agents/{name}.json. */
    private int putAgent(HttpExchange exchange, String name) throws IOException {
        Agent agent;
        try {
            agent = AgentLoader.parse(readJson(exchange), "PUT /agents/" + name);
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, e.getMessage());
        }
        if (!agent.name().equals(name)) {
            throw new HttpError(400, "the body names agent \"" + agent.name() + "\" but the path says \"" + name + "\"");
        }
        ToolRegistry registry = ToolLoader.load(toolsDir);
        for (String tool : agent.toolNames()) {
            if (registry.find(tool).isEmpty()) {
                throw new HttpError(400, "no tool named " + tool + " in " + toolsDir + "/");
            }
        }
        Path file = agentsDir.resolve(name + ".json");
        boolean existed = Files.exists(file);
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(agentJson(agent)) + System.lineSeparator());
        return send(exchange, existed ? 200 : 201, agentJson(agent));
    }

    private int listTools(HttpExchange exchange) throws IOException {
        ArrayNode list = JSON.createArrayNode();
        for (Tool tool : ToolLoader.load(toolsDir).all()) {
            ObjectNode node = list.addObject().put("name", tool.name()).put("type", tool.type())
                    .put("description", tool.description());
            if (tool.inputField() != null) {
                node.put("inputField", tool.inputField());
            }
            ObjectNode match = node.putObject("match");
            ArrayNode keywords = match.putArray("keywords");
            for (String keyword : tool.matching().keywords()) {
                keywords.add(keyword);
            }
            if (tool.matching().idPattern() != null) {
                match.put("idPattern", tool.matching().idPattern().pattern());
            }
        }
        return send(exchange, 200, list);
    }

    // --- runs: rows in the store ---

    /** Writes the job row, answers 202 with its id, and runs the loop on a thread of its own. */
    private int startRun(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String agentName = text(body, "agent");
        String question = text(body, "question");
        Agent agent = findAgent(agentName).orElseThrow(() -> new HttpError(400, "no agent named " + agentName));
        String jobId = store.newRun(agent.name(), question);
        Thread.ofVirtual().name("run-" + jobId).start(() -> runJob(agent, jobId, question));
        return send(exchange, 202, JSON.createObjectNode().put("jobId", jobId).put("status", Job.Status.RUNNING.name()));
    }

    /** The loop: the Orchestrator sets the job DONE or FAILED when it ends, whoever is still listening. */
    private void runJob(Agent agent, String jobId, String question) {
        try {
            ToolRegistry registry = ToolLoader.load(toolsDir);
            ToolCaller toolCaller = new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs()));
            new Orchestrator(agent, registry, new RuleBasedLLM(), toolCaller, new RelaySummarizer(), store, span -> { })
                    .start(jobId, question);
        } catch (RuntimeException e) {
            store.setStatus(jobId, Job.Status.FAILED);
            System.err.println("run " + jobId + " failed: " + e);
        }
    }

    /** The job row, its steps, and the answer once the job is DONE. */
    private int getRun(HttpExchange exchange, String id) throws IOException {
        Job job = requireJob(id);
        List<Span> rows = store.load(id);
        ObjectNode reply = JSON.valueToTree(job);
        reply.set("steps", JSON.valueToTree(rows));
        if (job.status() == Job.Status.DONE) {
            reply.put("answer", answer(job, rows));
        }
        return send(exchange, 200, reply);
    }

    /**
     * Server-Sent Events. Every stored step after Last-Event-ID (or ?after=N) goes out at
     * once, id = the step number; then the store is polled until the job is DONE or FAILED,
     * and a final "done" event carries the answer. The status is read before the rows, so
     * when it says the job ended, every row is already in hand.
     */
    private int events(HttpExchange exchange, String id) throws IOException {
        requireJob(id);
        int after = after(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            while (!stopped) {
                Job job = store.job(id);
                for (Span row : store.load(id, after + 1)) {
                    write(out, "id: " + row.step() + "\nevent: step\ndata: " + JSON.writeValueAsString(row) + "\n\n");
                    after = row.step();
                }
                out.flush();
                if (job.status() != Job.Status.RUNNING) {
                    ObjectNode done = JSON.createObjectNode().put("jobId", id).put("status", job.status().name());
                    if (job.status() == Job.Status.DONE) {
                        done.put("answer", answer(job, store.load(id)));
                    }
                    write(out, "event: done\ndata: " + done + "\n\n");
                    return 200;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 200;
    }

    /** The answer of a finished run, read back from its rows the way resume reads them. */
    private String answer(Job job, List<Span> rows) {
        Agent agent = findAgent(job.agent())
                .orElseThrow(() -> new IllegalStateException("agent " + job.agent() + " of run " + job.id() + " no longer exists"));
        return new RelaySummarizer().summarize(Conversation.rebuild(job.id(), agent, rows).turns().getLast());
    }

    private Job requireJob(String id) {
        if (!store.exists(id)) {
            throw new HttpError(404, "no run " + id);
        }
        return store.job(id);
    }

    /** Last-Event-ID wins, then ?after=N, else 0: the step the client has already seen. */
    private static int after(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        String query = exchange.getRequestURI().getQuery();
        if (value == null && query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("after=")) {
                    value = pair.substring("after=".length());
                }
            }
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new HttpError(400, "Last-Event-ID or after must be a step number, got " + value);
        }
    }

    // --- helpers ---

    private Optional<Agent> findAgent(String name) {
        return AgentLoader.loadAll(agentsDir).stream().filter(agent -> agent.name().equals(name)).findFirst();
    }

    /** The agent the way its file says it: name, description, tools, maxPasses, toolTimeoutMs. */
    private static ObjectNode agentJson(Agent agent) {
        ObjectNode node = JSON.createObjectNode().put("name", agent.name()).put("description", agent.description());
        ArrayNode tools = node.putArray("tools");
        for (String tool : agent.toolNames()) {
            tools.add(tool);
        }
        return node.put("maxPasses", agent.maxPasses()).put("toolTimeoutMs", agent.toolTimeoutMs());
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        try {
            return JSON.readTree(exchange.getRequestBody().readAllBytes());
        } catch (JsonProcessingException e) {
            throw new HttpError(400, "the body is not JSON: " + e.getOriginalMessage());
        }
    }

    private static String text(JsonNode body, String field) {
        if (!body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            throw new HttpError(400, "the body needs \"" + field + "\"");
        }
        return body.get(field).asText();
    }

    private static int methodNotAllowed(String method, String path) {
        throw new HttpError(405, method + " is not allowed on " + path);
    }

    /** JSON out, pretty printed so curl is readable; the status is returned for the log line. */
    private static int send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body) + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        return status;
    }

    private static int sendError(HttpExchange exchange, int status, String message) {
        try {
            return send(exchange, status, JSON.createObjectNode().put("error", message));
        } catch (IOException e) {
            return status;
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A reply that is not 2xx: the status and the one-line reason the client gets back. */
    static class HttpError extends RuntimeException {

        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
