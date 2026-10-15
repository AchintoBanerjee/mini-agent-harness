package harness.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.core.RunListener;
import harness.core.Span;
import harness.eval.CaseResult;
import harness.eval.EvalRunner;
import harness.eval.GoldenCase;
import harness.store.RunQueue;
import harness.store.RunStore;
import harness.store.TurnRow;
import harness.tools.Tool;
import harness.tools.ToolLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * The harness as a small service on the JDK's own HttpServer, JSON in and out: GET /agents
 * and /agents/{name}, GET /tools, POST /runs (the turn row and its queue message, 202 with
 * the turn id at once; a worker runs the loop; a conversationId in the body adds a turn to
 * an earlier conversation), GET /turns/{id} (the turn row, its spans, the answer when DONE;
 * /runs/{id} is the same), POST /turns/{id}/events (the worker's push: one event of the
 * run's stream, appended to the stream this process holds for the turn), GET
 * /turns/{id}/events (Server-Sent Events: that stream from Last-Event-ID on, each step, each
 * piece of the answer, then done; the saved rows once when the stream is gone) and POST
 * /evals (the golden set, scored in this process). Agents and tools stay files on disk;
 * turns live in the store, so this process does not care which worker ran the loop, and a
 * client that goes away does not stop its turn.
 */
public class HttpApi {

    /** How long one read of the stream waits for the next event before the handler checks whether to go on. */
    static final long READ_WAIT_MS = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Pretty JSON so curl is readable; the eval table also puts every array element on its own line. */
    private static final ObjectWriter PRETTY = JSON.writerWithDefaultPrettyPrinter();
    private static final ObjectWriter PRETTY_LINES = JSON.writer(
            new DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE));

    private final Path agentsDir;
    private final Path toolsDir;
    private final Path golden;
    private final RunStore store;
    private final RunQueue queue;
    private final RunStreams streams = new RunStreams();
    private HttpServer server;
    private volatile boolean stopped;

    public HttpApi(Path agentsDir, Path toolsDir, Path golden, RunStore store, RunQueue queue) {
        this.agentsDir = agentsDir;
        this.toolsDir = toolsDir;
        this.golden = golden;
        this.store = store;
        this.queue = queue;
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

    /** Stops listening; an open event stream ends at its next read. Turns in flight belong to the workers. */
    public void stop() {
        stopped = true;
        server.stop(0);
    }

    /**
     * The loop's events into the streams this process holds: a row as a step event with the
     * span as its data, a piece of the answer as a token event, the end as done. A worker in
     * this process is wired to it directly; a worker in another process reaches the same
     * append through POST /turns/{id}/events.
     */
    public RunListener producer() {
        return new RunListener() {
            @Override
            public void on(Span span) {
                streams.append(span.turnId(), "step", json(span));
            }

            @Override
            public void token(String turnId, String text) {
                streams.append(turnId, "token", JSON.createObjectNode().put("turnId", turnId).put("text", text).toString());
            }

            @Override
            public void done(String turnId, TurnRow.Status status, String answer) {
                streams.append(turnId, "done", doneData(turnId, status, answer).toString());
            }
        };
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
        boolean turns = parts[0].equals("turns") || parts[0].equals("runs");
        if (parts.length == 1 && agents) {
            return method.equals("GET") ? listAgents(exchange) : methodNotAllowed(method, path);
        }
        if (parts.length == 2 && agents) {
            return method.equals("GET") ? getAgent(exchange, parts[1]) : methodNotAllowed(method, path);
        }
        if (parts.length == 1 && parts[0].equals("tools")) {
            return method.equals("GET") ? listTools(exchange) : methodNotAllowed(method, path);
        }
        if (parts.length == 1 && turns) {
            return method.equals("POST") ? submit(exchange) : methodNotAllowed(method, path);
        }
        if (parts.length == 2 && turns) {
            return method.equals("GET") ? getTurn(exchange, parts[1]) : methodNotAllowed(method, path);
        }
        if (parts.length == 3 && turns && parts[2].equals("events")) {
            return switch (method) {
                case "GET" -> events(exchange, parts[1]);
                case "POST" -> push(exchange, parts[1]);
                default -> methodNotAllowed(method, path);
            };
        }
        if (parts.length == 1 && parts[0].equals("evals")) {
            return method.equals("POST") ? evals(exchange) : methodNotAllowed(method, path);
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

    private int listTools(HttpExchange exchange) throws IOException {
        ArrayNode list = JSON.createArrayNode();
        for (Tool tool : ToolLoader.load(toolsDir).all()) {
            ObjectNode node = list.addObject().put("name", tool.name()).put("type", tool.type())
                    .put("description", tool.description());
            if (tool.isAgent()) {
                node.put("agent", tool.agent().name());
            }
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

    // --- turns: rows in the store ---

    /**
     * Writes the turn row and its queue message and answers 202 with the turn id (the job
     * id); a worker runs the loop. Without a conversationId the body's agent opens a new
     * conversation; with one, the turn joins that conversation and its agent.
     */
    private int submit(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String question = text(body, "question");
        String conversationId;
        Agent agent;
        if (body.hasNonNull("conversationId")) {
            conversationId = body.get("conversationId").asText();
            String agentName;
            try {
                agentName = store.conversation(conversationId).agent();
            } catch (IllegalArgumentException e) {
                throw new HttpError(400, "no conversation " + conversationId);
            }
            if (body.hasNonNull("agent") && !body.get("agent").asText().equals(agentName)) {
                throw new HttpError(400, "conversation " + conversationId + " belongs to agent " + agentName);
            }
            agent = findAgent(agentName).orElseThrow(() -> new HttpError(400, "no agent named " + agentName));
        } else {
            String agentName = text(body, "agent");
            agent = findAgent(agentName).orElseThrow(() -> new HttpError(400, "no agent named " + agentName));
            conversationId = store.newConversation(agent.name());
        }
        String turnId = queue.submit(conversationId, question);
        return send(exchange, 202, JSON.createObjectNode().put("turnId", turnId).put("jobId", turnId)
                .put("conversationId", conversationId).put("status", TurnRow.Status.QUEUED.name()));
    }

    /** The turn row (the answer is on it once DONE) and its spans. */
    private int getTurn(HttpExchange exchange, String id) throws IOException {
        TurnRow turn = requireTurn(id);
        ObjectNode reply = JSON.valueToTree(turn);
        reply.set("spans", JSON.valueToTree(store.load(id)));
        return send(exchange, 200, reply);
    }

    /**
     * The worker's push: one event of the run's stream, {"event": step | token | done,
     * "data": {...}}, appended to the stream this process holds for the turn. The stand-in
     * for the append to the stream.
     */
    private int push(HttpExchange exchange, String id) throws IOException {
        requireTurn(id);
        JsonNode body = readJson(exchange);
        String event = text(body, "event");
        if (!List.of("step", "token", "done").contains(event)) {
            throw new HttpError(400, "event must be step, token or done, got " + event);
        }
        if (!body.hasNonNull("data")) {
            throw new HttpError(400, "the body needs \"data\"");
        }
        streams.append(id, event, body.get("data").toString());
        exchange.sendResponseHeaders(204, -1);
        return 204;
    }

    /**
     * Server-Sent Events: the consumer of the run's stream. From the last id the client saw
     * (Last-Event-ID or ?after=N) every entry of the stream goes out as it arrives, its id the
     * entry's id: step with the span, token with a piece of the answer, then done, which ends
     * the response. The stream is fed by the worker's pushes and lives in this process; the
     * store is read only when the stream is gone (replay). A client that goes away does not
     * stop its turn.
     */
    private int events(HttpExchange exchange, String id) throws IOException {
        requireTurn(id);
        int after = after(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            // the headers go out now, so the client sees the stream is open before its first event; the server would otherwise hold them until the first write
            out.flush();
            if (streams.get(id) == null && store.turn(id).status().ended()) {
                return replay(out, id, after);
            }
            RunStreams.Stream stream = streams.open(id);
            while (!stopped) {
                List<RunStreams.Entry> entries = stream.read(after, READ_WAIT_MS);
                if (entries.isEmpty()) {
                    if (stream.finished()) {
                        // a client that has everything still gets done
                        entries = List.of(stream.done());
                    } else if (stream.size() == 0 && store.turn(id).status().ended()) {
                        // the turn ran without feeding this API: the empty stream goes, so a later reader replays at once
                        streams.forget(id);
                        return replay(out, id, after);
                    } else {
                        continue;
                    }
                }
                for (RunStreams.Entry entry : entries) {
                    if (entry.event().equals("done")) {
                        write(out, "event: done\ndata: " + entry.data() + "\n\n");
                        return 200;
                    }
                    write(out, "id: " + entry.id() + "\nevent: " + entry.event() + "\ndata: " + entry.data() + "\n\n");
                    after = entry.id();
                }
                out.flush();
            }
        }
        return 200;
    }

    /**
     * The fallback: the stream is gone (its time ran out, this process restarted, or no worker
     * fed it), so the saved rows go out once, from the store, each a step event with the span
     * number as its id, then done. The live path never reads the store.
     */
    private int replay(OutputStream out, String id, int after) throws IOException {
        TurnRow turn = store.turn(id);
        for (Span row : store.load(id, after + 1)) {
            write(out, "id: " + row.span() + "\nevent: step\ndata: " + json(row) + "\n\n");
        }
        write(out, "event: done\ndata: " + doneData(id, turn.status(), turn.answer()) + "\n\n");
        return 200;
    }

    // --- evals: the golden set, scored here ---

    /** Runs the golden set in this process, each case on its own in-memory store, and returns the table: one object per case, plus the lines as printed. */
    private int evals(HttpExchange exchange) throws IOException {
        EvalRunner runner = new EvalRunner(AgentLoader.loadAll(agentsDir), ToolLoader.load(toolsDir));
        List<CaseResult> results = runner.run(GoldenCase.loadAll(golden));
        ObjectNode reply = JSON.createObjectNode();
        ArrayNode cases = reply.putArray("cases");
        ArrayNode lines = JSON.createArrayNode();
        for (int i = 0; i < results.size(); i++) {
            CaseResult result = results.get(i);
            ObjectNode node = cases.addObject().put("n", i + 1).put("passed", result.passed())
                    .put("agent", result.golden().agent()).put("question", result.golden().question());
            node.set("tools", JSON.valueToTree(result.tools()));
            node.put("passes", result.passes()).put("ms", result.ms()).put("answer", result.answer());
            node.set("failures", JSON.valueToTree(result.failures()));
            lines.add(result.line(i + 1));
        }
        lines.add(EvalRunner.footer(results));
        reply.put("footer", EvalRunner.footer(results));
        reply.set("lines", lines);
        return send(exchange, 200, reply, PRETTY_LINES);
    }

    // --- helpers ---

    private TurnRow requireTurn(String id) {
        if (!store.exists(id)) {
            throw new HttpError(404, "no turn " + id);
        }
        return store.turn(id);
    }

    /** Last-Event-ID wins, then ?after=N, else 0: the span the client has already seen. */
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
            throw new HttpError(400, "Last-Event-ID or after must be a span number, got " + value);
        }
    }

    private Optional<Agent> findAgent(String name) {
        return AgentLoader.loadAll(agentsDir).stream().filter(agent -> agent.name().equals(name)).findFirst();
    }

    /** The agent the way its file says it: name, description, tools, maxPasses, toolTimeoutMs, maxModelCalls. */
    private static ObjectNode agentJson(Agent agent) {
        ObjectNode node = JSON.createObjectNode().put("name", agent.name()).put("description", agent.description());
        ArrayNode tools = node.putArray("tools");
        for (String tool : agent.toolNames()) {
            tools.add(tool);
        }
        return node.put("maxPasses", agent.maxPasses()).put("toolTimeoutMs", agent.toolTimeoutMs())
                .put("maxModelCalls", agent.maxModelCalls());
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

    private static int send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        return send(exchange, status, body, PRETTY);
    }

    /** JSON out, pretty printed so curl is readable; the status is returned for the log line. */
    private static int send(HttpExchange exchange, int status, JsonNode body, ObjectWriter writer) throws IOException {
        byte[] bytes = (writer.writeValueAsString(body) + "\n").getBytes(StandardCharsets.UTF_8);
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

    /** The done event's data: the turn, how it ended, and the answer when it ended DONE. */
    private static ObjectNode doneData(String turnId, TurnRow.Status status, String answer) {
        ObjectNode done = JSON.createObjectNode().put("turnId", turnId).put("status", status.name());
        if (status == TurnRow.Status.DONE) {
            done.put("answer", answer);
        }
        return done;
    }

    /** One line of JSON, the shape a step event carries. */
    private static String json(Object value) {
        return JSON.valueToTree(value).toString();
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
