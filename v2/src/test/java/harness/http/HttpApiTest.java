package harness.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.tools.ToolLoader;
import harness.worker.Worker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every endpoint through java.net.http.HttpClient on a random port, over a temp SQLite file
 * and a copy of agents/ (PUT writes there), with the real tools/ (read only). The server
 * only queues a job; a worker thread over the same file runs it when a test starts one.
 */
class HttpApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUESTION = "What is the status of order ORD-1?";
    private static final String ANSWER = "Order ORD-1: SHIPPED, shipment SHP-1";

    @TempDir
    Path dir;
    private Path agents;
    private SqliteRunStore store;
    private RunQueue queue;
    private Worker worker;
    private Thread workerThread;
    private HttpApi api;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        agents = dir.resolve("agents");
        Files.createDirectories(agents);
        for (String name : List.of("chaos.json", "inventory.json", "support.json")) {
            Files.copy(Path.of("agents", name), agents.resolve(name));
        }
        store = new SqliteRunStore(dir.resolve("harness.db"));
        queue = new RunQueue(store);
        api = new HttpApi(agents, Path.of("tools"), store, queue);
        api.start(0);
        base = "http://localhost:" + api.port();
    }

    @AfterEach
    void stop() throws InterruptedException {
        ToolLoader.SLOW_LOOKUP_MS = 0;
        api.stop();
        if (worker != null) {
            worker.stop();
            workerThread.join();
        }
        store.close();
    }

    /** A worker over the same temp file, polling fast, on its own thread: the process that runs the loop. */
    private void startWorker() {
        worker = new Worker(agents, Path.of("tools"), store, queue, "test-worker", 2000);
        worker.pollMs(20);
        workerThread = Thread.ofVirtual().start(worker::loop);
    }

    @Test
    void agentsAndToolsAreListedAndReadByName() throws Exception {
        HttpResponse<String> list = get("/agents");
        assertEquals(200, list.statusCode());
        assertEquals("application/json; charset=utf-8", list.headers().firstValue("Content-Type").orElse(""));
        assertEquals(List.of("chaos", "inventory", "support"), names(JSON.readTree(list.body())));

        JsonNode support = JSON.readTree(get("/agents/support").body());
        assertEquals("Answers order, shipment and stock questions", support.get("description").asText());
        assertEquals(List.of("order_status", "shipping_status", "stock_status"), texts(support.get("tools")));
        assertEquals(5, support.get("maxPasses").asInt());

        HttpResponse<String> missing = get("/agents/nope");
        assertEquals(404, missing.statusCode());
        assertEquals("no agent named nope", JSON.readTree(missing.body()).get("error").asText());

        JsonNode tools = JSON.readTree(get("/tools").body());
        assertEquals(List.of("hang_forever", "order_status", "refund_order", "shipping_status", "stock_status", "throw_boom"), names(tools));
        assertEquals("lookup", tools.get(1).get("type").asText());
        assertEquals("orderId", tools.get(1).get("inputField").asText());
        assertEquals("ORD-\\d+", tools.get(1).get("match").get("idPattern").asText());
        assertFalse(tools.get(5).has("inputField"), "throw_boom takes no input");

        assertEquals(404, get("/nothing").statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(uri("/tools")).DELETE().build()).statusCode());
    }

    @Test
    void putAgentWritesTheFileAndRefusesAnUnknownTool() throws Exception {
        String badTool = "{\"name\":\"returns\",\"description\":\"Refunds orders\",\"tools\":[\"refund_orders\"],\"maxPasses\":3,\"toolTimeoutMs\":2000}";
        HttpResponse<String> refused = put("/agents/returns", badTool);
        assertEquals(400, refused.statusCode());
        assertEquals("no tool named refund_orders in tools/", JSON.readTree(refused.body()).get("error").asText());
        assertFalse(Files.exists(agents.resolve("returns.json")), "nothing was written");

        String good = badTool.replace("refund_orders", "refund_order");
        HttpResponse<String> created = put("/agents/returns", good);
        assertEquals(201, created.statusCode());
        assertTrue(Files.exists(agents.resolve("returns.json")));
        assertEquals(List.of("chaos", "inventory", "returns", "support"), names(JSON.readTree(get("/agents").body())));
        JsonNode returns = JSON.readTree(get("/agents/returns").body());
        assertEquals(List.of("refund_order"), texts(returns.get("tools")));
        assertEquals(3, returns.get("maxPasses").asInt());

        assertEquals(200, put("/agents/returns", good.replace("\"maxPasses\":3", "\"maxPasses\":4")).statusCode(), "a change, not a creation");
        assertEquals(4, JSON.readTree(get("/agents/returns").body()).get("maxPasses").asInt());

        assertEquals(400, put("/agents/other", good).statusCode(), "the body's name must match the path");
        HttpResponse<String> partial = put("/agents/partial", "{\"name\":\"partial\",\"description\":\"no tools\",\"maxPasses\":1,\"toolTimeoutMs\":1}");
        assertEquals(400, partial.statusCode());
        assertEquals("PUT /agents/partial: missing \"tools\"", JSON.readTree(partial.body()).get("error").asText());
        assertEquals(400, put("/agents/returns", "not json").statusCode());
    }

    @Test
    void postRunsQueuesTheJobAtOnceAndAWorkerFinishesIt() throws Exception {
        HttpResponse<String> accepted = post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}");
        assertEquals(202, accepted.statusCode());
        JsonNode ticket = JSON.readTree(accepted.body());
        String id = ticket.get("jobId").asText();
        assertTrue(id.matches("r-[0-9a-f]{4}"), id);
        assertEquals("QUEUED", ticket.get("status").asText());

        JsonNode queued = JSON.readTree(get("/runs/" + id).body());
        assertEquals("QUEUED", queued.get("status").asText(), "no worker yet: the server does not run the loop");
        assertEquals(0, queued.get("steps").size());
        assertFalse(queued.has("answer"));
        assertEquals(1, queue.size());

        startWorker();
        JsonNode run = awaitDone(id);
        assertEquals(id, run.get("conversationId").asText());
        assertEquals("support", run.get("agent").asText());
        assertEquals(QUESTION, run.get("question").asText());
        assertEquals("test-worker", run.get("claimedBy").asText());
        assertEquals(1, run.get("attempts").asInt());
        assertEquals(List.of("LLM", "TOOL", "LLM"), run.get("steps").findValuesAsText("kind"));
        assertEquals(List.of(1, 2, 3), run.get("steps").findValues("step").stream().map(JsonNode::asInt).toList());
        assertEquals(ANSWER, run.get("answer").asText());
        assertEquals(0, queue.size(), "acked");

        assertEquals(404, get("/runs/r-0000").statusCode());
        assertEquals(400, post("/runs", "{\"agent\":\"nope\",\"question\":\"q\"}").statusCode());
        assertEquals("the body needs \"question\"", JSON.readTree(post("/runs", "{\"agent\":\"support\"}").body()).get("error").asText());
    }

    @Test
    void eventsReplayFromLastEventIdAndEndWithDone() throws Exception {
        startWorker();
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("jobId").asText();
        awaitDone(id);

        HttpResponse<String> stream = get("/runs/" + id + "/events");
        assertEquals(200, stream.statusCode());
        assertEquals("text/event-stream; charset=utf-8", stream.headers().firstValue("Content-Type").orElse(""));
        List<Event> events = events(stream.body());
        assertEquals(List.of("step", "step", "step", "done"), events.stream().map(Event::event).toList());
        assertEquals(List.of("1", "2", "3"), events.subList(0, 3).stream().map(Event::id).toList());
        assertEquals("TOOL", JSON.readTree(events.get(1).data()).get("kind").asText());
        assertEquals(ANSWER, JSON.readTree(events.get(3).data()).get("answer").asText());
        assertEquals("DONE", JSON.readTree(events.get(3).data()).get("status").asText());

        List<Event> tail = events(send(HttpRequest.newBuilder(uri("/runs/" + id + "/events")).header("Last-Event-ID", "2").build()).body());
        assertEquals(Arrays.asList("3", null), tail.stream().map(Event::id).toList(), "only the step after 2, then done");
        assertEquals(List.of("step", "done"), tail.stream().map(Event::event).toList());

        List<Event> nothingNew = events(get("/runs/" + id + "/events?after=3").body());
        assertEquals(List.of("done"), nothingNew.stream().map(Event::event).toList());

        assertEquals(404, get("/runs/r-0000/events").statusCode());
        assertEquals(400, send(HttpRequest.newBuilder(uri("/runs/" + id + "/events")).header("Last-Event-ID", "two").build()).statusCode());
    }

    @Test
    void eventsFollowALiveRunAndADisconnectDoesNotStopIt() throws Exception {
        ToolLoader.SLOW_LOOKUP_MS = 300;
        startWorker();
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("jobId").asText();

        HttpResponse<InputStream> live = client.send(HttpRequest.newBuilder(uri("/runs/" + id + "/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        Event first;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(live.body()))) {
            first = events(block(reader)).get(0);
            assertEquals("RUNNING", JSON.readTree(get("/runs/" + id).body()).get("status").asText(), "the tool is still sleeping");
        }
        assertEquals("1", first.id());
        assertEquals("LLM", JSON.readTree(first.data()).get("kind").asText(), "the first row arrived while the run was live");

        JsonNode run = awaitDone(id);
        assertEquals(3, run.get("steps").size(), "the run finished although its listener left");
        assertEquals(ANSWER, run.get("answer").asText());
        assertEquals(Arrays.asList("1", "2", "3", null), events(get("/runs/" + id + "/events").body()).stream().map(Event::id).toList());
    }

    @Test
    void theDeadLetterListIsTheJobsTheQueueGaveUpOn() throws Exception {
        assertEquals(0, JSON.readTree(get("/dlq").body()).size(), "nothing dead yet");
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("jobId").asText();
        for (int receive = 1; receive <= RunQueue.DEFAULT_MAX_RECEIVES; receive++) {
            assertFalse(queue.receive(0).orElseThrow().dead(), "receive " + receive + " is delivered (and its worker dies at once)");
        }
        assertTrue(queue.receive(0).orElseThrow().dead(), "one receive too many");

        HttpResponse<String> dlq = get("/dlq");
        assertEquals(200, dlq.statusCode());
        JsonNode dead = JSON.readTree(dlq.body());
        assertEquals(1, dead.size());
        assertEquals(id, dead.get(0).get("id").asText());
        assertEquals("DEAD", dead.get(0).get("status").asText());
        assertEquals(QUESTION, dead.get(0).get("question").asText());

        JsonNode run = JSON.readTree(get("/runs/" + id).body());
        assertEquals("DEAD", run.get("status").asText());
        assertFalse(run.has("answer"));
        List<Event> events = events(get("/runs/" + id + "/events").body());
        assertEquals(List.of("done"), events.stream().map(Event::event).toList(), "the stream closes on a dead job");
        assertEquals("DEAD", JSON.readTree(events.get(0).data()).get("status").asText());
        assertEquals(405, send(HttpRequest.newBuilder(uri("/dlq")).DELETE().build()).statusCode());
    }

    // --- helpers ---

    private JsonNode awaitDone(String id) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            JsonNode run = JSON.readTree(get("/runs/" + id).body());
            if (run.get("status").asText().equals("DONE")) {
                return run;
            }
            assertTrue(System.nanoTime() < deadline, "run " + id + " did not finish in 5 s: " + run);
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).build());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : list) {
            names.add(node.get("name").asText());
        }
        return names;
    }

    private static List<String> texts(JsonNode list) {
        List<String> texts = new ArrayList<>();
        for (JsonNode node : list) {
            texts.add(node.asText());
        }
        return texts;
    }

    /** One event: the lines up to the blank line that ends it. */
    private static String block(BufferedReader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** The events of a stream body: id (null for done), event, data. */
    private static List<Event> events(String body) {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String id = null;
            String event = null;
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("id: ")) {
                    id = line.substring(4);
                } else if (line.startsWith("event: ")) {
                    event = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    data = line.substring(6);
                }
            }
            if (event != null) {
                events.add(new Event(id, event, data));
            }
        }
        return events;
    }

    record Event(String id, String event, String data) {
    }
}
