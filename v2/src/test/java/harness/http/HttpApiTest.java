package harness.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.tools.ToolLoader;
import harness.worker.DyingWorker;
import harness.worker.Worker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The harness through its face: every endpoint through java.net.http.HttpClient on a random
 * port, over a temp SQLite file, the real agents/ and tools/ (read only) and a small golden
 * set of the test's own. The server only queues a turn; a worker thread over the same file
 * runs it when a test starts one, wired to the API's producer so its events feed the run's
 * stream in process (the CLI's worker pushes them over HTTP instead), and a worker that dies
 * is a DyingWorker.
 */
class HttpApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUESTION = "What is the status of order ORD-1?";
    private static final String ORDER = "Order ORD-1: SHIPPED, shipment SHP-1";
    private static final String ANSWER = ORDER + "\nShipment SHP-1: IN TRANSIT, arriving 2026-10-16";
    private static final List<String> PIECES = List.of("Order ", "ORD-1: ", "SHIPPED, ", "shipment ", "SHP-1\nShipment ",
            "SHP-1: ", "IN ", "TRANSIT, ", "arriving ", "2026-10-16");
    /** The canonical turn's stream: five rows, ten pieces, then done without an id. */
    private static final List<String> STREAM_IDS = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", null);
    private static final List<String> STREAM_EVENTS = List.of("step", "step", "step", "step", "step",
            "token", "token", "token", "token", "token", "token", "token", "token", "token", "token", "done");
    private static final Pattern TOGETHER = Pattern.compile("ran (\\d+) tools together: wall (\\d+) ms, work (\\d+) ms");
    private static final Path AGENTS = Path.of("agents");
    private static final Path TOOLS = Path.of("tools");

    @TempDir
    Path dir;
    private SqliteRunStore store;
    private RunQueue queue;
    private Worker worker;
    private Thread workerThread;
    private HttpApi api;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        ToolLoader.HANDLER_CALLS.clear();
        Path golden = dir.resolve("golden.json");
        Files.writeString(golden, """
                [ { "question": "What is the status of order ORD-1?", "agent": "support",
                    "expectTools": ["order_status", "shipping_status"], "answerContains": "SHIPPED", "maxPasses": 3 },
                  { "question": "boom", "agent": "chaos",
                    "expectTools": ["throw_boom"], "answerContains": "ERROR", "maxPasses": 3 },
                  { "question": "track my parcel SHP-3", "agent": "support",
                    "expectTools": ["shipping_status"], "answerContains": "DELIVERED", "maxPasses": 3 } ]
                """);
        store = new SqliteRunStore(dir.resolve("harness.db"));
        queue = new RunQueue(store);
        api = new HttpApi(AGENTS, TOOLS, golden, store, queue);
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

    /** A worker over the same temp file, polling fast, on its own thread, its events into the API's streams: the process that runs the loop. */
    private void startWorker() {
        worker = new Worker(AGENTS, TOOLS, store, queue, "test-worker", 2000);
        worker.pollMs(20);
        worker.stream(api.producer());
        workerThread = Thread.ofVirtual().start(worker::loop);
    }

    @Test
    void agentsAndToolsAreListedAndReadByName() throws Exception {
        HttpResponse<String> list = get("/agents");
        assertEquals(200, list.statusCode());
        assertEquals("application/json; charset=utf-8", list.headers().firstValue("Content-Type").orElse(""));
        assertEquals(List.of("chaos", "concierge", "inventory", "logistics", "loop", "sandbox", "support"), names(JSON.readTree(list.body())));

        JsonNode support = JSON.readTree(get("/agents/support").body());
        assertEquals("Answers order, shipment and stock questions", support.get("description").asText());
        assertEquals(List.of("order_status", "shipping_status", "stock_status"), texts(support.get("tools")));
        assertEquals(5, support.get("maxPasses").asInt());
        assertEquals(20, support.get("maxModelCalls").asInt());

        HttpResponse<String> missing = get("/agents/nope");
        assertEquals(404, missing.statusCode());
        assertEquals("no agent named nope", JSON.readTree(missing.body()).get("error").asText());

        JsonNode tools = JSON.readTree(get("/tools").body());
        assertEquals(List.of("ask_logistics", "ask_loop", "call_out", "fingerprint", "hang_forever", "hang_in_box", "order_status",
                "read_secrets", "refund_order", "shipping_status", "stock_status", "throw_boom", "write_disk"), names(tools));
        assertEquals("agent", tools.get(0).get("type").asText());
        assertEquals("logistics", tools.get(0).get("agent").asText(), "an agent tool names the agent it runs");
        assertEquals("goal", tools.get(0).get("inputField").asText());
        assertEquals("container", tools.get(2).get("type").asText(), "call_out runs in the sandbox");
        assertEquals("lookup", tools.get(6).get("type").asText());
        assertEquals("orderId", tools.get(6).get("inputField").asText());
        assertEquals("ORD-\\d+", tools.get(6).get("match").get("idPattern").asText());
        assertFalse(tools.get(6).has("agent"), "a lookup is not an agent");
        assertFalse(tools.get(11).has("inputField"), "throw_boom takes no input");

        assertEquals(404, get("/nothing").statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(uri("/tools")).DELETE().build()).statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(uri("/agents/support")).PUT(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode(),
                "agents are files on disk: there is no builder endpoint");
    }

    @Test
    void postRunsQueuesTheTurnAtOnceAndAWorkerFinishesIt() throws Exception {
        HttpResponse<String> accepted = post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}");
        assertEquals(202, accepted.statusCode());
        JsonNode ticket = JSON.readTree(accepted.body());
        String id = ticket.get("turnId").asText();
        assertTrue(id.matches("r-[0-9a-f]{4}"), id);
        assertEquals(id, ticket.get("jobId").asText(), "the job id is the turn id");
        assertTrue(ticket.get("conversationId").asText().matches("c-[0-9a-f]{4}"), ticket.toString());
        assertEquals("QUEUED", ticket.get("status").asText());

        JsonNode queued = JSON.readTree(get("/turns/" + id).body());
        assertEquals("QUEUED", queued.get("status").asText(), "no worker yet: the server does not run the loop");
        assertEquals(0, queued.get("spans").size());
        assertTrue(queued.get("answer").isNull());
        assertEquals(1, queue.size());

        startWorker();
        JsonNode turn = awaitDone(id);
        assertEquals(ticket.get("conversationId").asText(), turn.get("conversationId").asText());
        assertEquals("support", turn.get("agent").asText());
        assertEquals(QUESTION, turn.get("question").asText());
        assertEquals("test-worker", turn.get("claimedBy").asText());
        assertEquals(1, turn.get("attempts").asInt());
        assertEquals(List.of("LLM", "TOOL", "LLM", "TOOL", "LLM"), turn.get("spans").findValuesAsText("kind"),
                "the order, then the shipment its result names, then done");
        assertEquals(List.of(1, 2, 3, 4, 5), turn.get("spans").findValues("span").stream().map(JsonNode::asInt).toList());
        assertEquals(id + "/1", turn.get("spans").get(1).get("attrs").get("key").asText(), "the TOOL span's key: turn id plus the deciding span");
        assertEquals("shipping_status", turn.get("spans").get(3).get("attrs").get("tool").asText());
        assertEquals(id + "/3", turn.get("spans").get(3).get("attrs").get("key").asText(), "decided by pass 2's row");
        assertEquals(ANSWER, turn.get("answer").asText());
        assertEquals(0, queue.size(), "acked");
        assertEquals(turn, JSON.readTree(get("/runs/" + id).body()), "/runs/{id} is the same turn");

        assertEquals(404, get("/turns/r-0000").statusCode());
        assertEquals(400, post("/runs", "{\"agent\":\"nope\",\"question\":\"q\"}").statusCode());
        assertEquals("the body needs \"question\"", JSON.readTree(post("/runs", "{\"agent\":\"support\"}").body()).get("error").asText());
        assertEquals(400, post("/runs", "not json").statusCode());
    }

    @Test
    void theStreamCarriesEachStepThenEachPieceOfTheAnswerThenDoneAndReplaysFromLastEventId() throws Exception {
        startWorker();
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("turnId").asText();
        awaitDone(id);

        HttpResponse<String> stream = get("/turns/" + id + "/events");
        assertEquals(200, stream.statusCode());
        assertEquals("text/event-stream; charset=utf-8", stream.headers().firstValue("Content-Type").orElse(""));
        List<Event> events = events(stream.body());
        assertEquals(STREAM_EVENTS, events.stream().map(Event::event).toList(), "the rows as the worker wrote them, then the answer as the model produced it");
        assertEquals(STREAM_IDS, events.stream().map(Event::id).toList(), "the event id is the entry's number in the run's stream; done has none");
        assertEquals("TOOL", JSON.readTree(events.get(1).data()).get("kind").asText());
        assertEquals(id, JSON.readTree(events.get(1).data()).get("turnId").asText());
        assertEquals("shipping_status", JSON.readTree(events.get(3).data()).get("attrs").get("tool").asText(), "the chained call is a step like any");
        assertEquals(PIECES, tokens(events), "one word per piece, the space kept");
        assertEquals(ANSWER, String.join("", tokens(events)), "the pieces concatenate to the answer");
        assertEquals(id, JSON.readTree(events.get(5).data()).get("turnId").asText(), "a token names its turn");
        assertEquals(ANSWER, JSON.readTree(events.getLast().data()).get("answer").asText());
        assertEquals("DONE", JSON.readTree(events.getLast().data()).get("status").asText());

        List<Event> tail = events(send(HttpRequest.newBuilder(uri("/turns/" + id + "/events")).header("Last-Event-ID", "2").build()).body());
        assertEquals(STREAM_IDS.subList(2, STREAM_IDS.size()), tail.stream().map(Event::id).toList(), "only what came after 2, then done");
        assertEquals(STREAM_EVENTS.subList(2, STREAM_EVENTS.size()), tail.stream().map(Event::event).toList());

        List<Event> onlyTheAnswer = events(get("/runs/" + id + "/events?after=5").body());
        assertEquals(STREAM_EVENTS.subList(5, STREAM_EVENTS.size()), onlyTheAnswer.stream().map(Event::event).toList());
        assertEquals(List.of("done"), events(get("/runs/" + id + "/events?after=15").body()).stream().map(Event::event).toList(),
                "a client that has everything still gets done");

        assertEquals(404, get("/turns/r-0000/events").statusCode());
        assertEquals(400, send(HttpRequest.newBuilder(uri("/turns/" + id + "/events")).header("Last-Event-ID", "two").build()).statusCode());
    }

    @Test
    void postEventsIsTheWorkersPushIntoTheStream() throws Exception {
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("turnId").asText();

        assertEquals(404, post("/turns/r-0000/events", "{\"event\":\"step\",\"data\":{}}").statusCode(), "no such turn");
        assertEquals(400, post("/turns/" + id + "/events", "{\"event\":\"row\",\"data\":{}}").statusCode(), "not an event of the stream");
        assertEquals(400, post("/turns/" + id + "/events", "{\"event\":\"step\"}").statusCode(), "no data");

        String step = "{\"turnId\":\"" + id + "\",\"span\":1,\"pass\":1,\"kind\":\"LLM\",\"request\":\"pass 1\",\"response\":\"Done[]\",\"status\":\"OK\",\"ms\":0,\"attrs\":{}}";
        assertEquals(204, post("/turns/" + id + "/events", "{\"event\":\"step\",\"data\":" + step + "}").statusCode());
        assertEquals(204, post("/runs/" + id + "/events", "{\"event\":\"token\",\"data\":{\"turnId\":\"" + id + "\",\"text\":\"No \"}}").statusCode(),
                "/runs/{id}/events is the same push");
        assertEquals(204, post("/turns/" + id + "/events", "{\"event\":\"done\",\"data\":{\"turnId\":\"" + id + "\",\"status\":\"DONE\",\"answer\":\"No\"}}").statusCode());

        List<Event> events = events(get("/turns/" + id + "/events").body());
        assertEquals(List.of("step", "token", "done"), events.stream().map(Event::event).toList(), "what was pushed, in order");
        assertEquals(Arrays.asList("1", "2", null), events.stream().map(Event::id).toList());
        assertEquals(JSON.readTree(step), JSON.readTree(events.get(0).data()), "the step's data is the pushed span, as pushed");
        assertEquals("No ", JSON.readTree(events.get(1).data()).get("text").asText());
        assertEquals("No", JSON.readTree(events.get(2).data()).get("answer").asText());
        assertEquals("QUEUED", JSON.readTree(get("/turns/" + id).body()).get("status").asText(), "the push feeds the stream, not the store");
        assertEquals(3, events(get("/turns/" + id + "/events?after=-1").body()).size(), "an id below 0 reads the stream from the start");

        assertEquals(204, post("/turns/" + id + "/events", "{\"event\":\"step\",\"data\":{\"late\":true}}").statusCode(),
                "a push after done is taken and dropped: the stream has ended");
        HttpResponse<InputStream> tail = client.send(HttpRequest.newBuilder(uri("/turns/" + id + "/events?after=2")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tail.body()))) {
            assertEquals(List.of("done"), events(block(reader)).stream().map(Event::event).toList(),
                    "a client that has everything gets done, not the late push");
            assertEquals("", block(reader), "once: the response ended there");
        }
        assertEquals(List.of("step", "token", "done"), events(get("/turns/" + id + "/events").body()).stream().map(Event::event).toList(),
                "the stream is as it was");
    }

    @Test
    void aStreamOpenedForATurnThatNoWorkerThenFedIsDroppedOnceTheTurnEnded() throws Exception {
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("turnId").asText();
        HttpResponse<InputStream> early = client.send(HttpRequest.newBuilder(uri("/turns/" + id + "/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, early.statusCode(), "the reader is waiting on a stream nothing has fed yet");
        assertTrue(new Worker(AGENTS, TOOLS, store, queue, "unwired", 2000).once(), "a worker with no stream set ran the turn");

        List<Event> events;
        try (InputStream body = early.body()) {
            events = events(new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertEquals(Arrays.asList("1", "2", "3", "4", "5", null), events.stream().map(Event::id).toList(),
                "the early reader waited one read, found the turn ended and its stream empty, and got the saved rows");
        long started = System.nanoTime();
        List<Event> later = events(get("/turns/" + id + "/events").body());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500, "the empty stream was dropped: a later reader replays at once");
        assertEquals(Arrays.asList("1", "2", "3", "4", "5", null), later.stream().map(Event::id).toList());
    }

    @Test
    void aFinishedTurnWhoseStreamNobodyFedIsReplayedFromTheStoreOnce() throws Exception {
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("turnId").asText();
        assertTrue(new Worker(AGENTS, TOOLS, store, queue, "unwired", 2000).once(), "a worker with no stream set: its events went nowhere");
        assertEquals("DONE", JSON.readTree(get("/turns/" + id).body()).get("status").asText());

        List<Event> events = events(get("/turns/" + id + "/events").body());

        assertEquals(List.of("step", "step", "step", "step", "step", "done"), events.stream().map(Event::event).toList(),
                "the fallback: the saved rows, no tokens, because no stream was ever fed for this turn");
        assertEquals(Arrays.asList("1", "2", "3", "4", "5", null), events.stream().map(Event::id).toList(), "the ids are the span numbers");
        assertEquals(ANSWER, JSON.readTree(events.getLast().data()).get("answer").asText(), "done comes from the turn row");
        List<Event> tail = events(get("/turns/" + id + "/events?after=2").body());
        assertEquals(Arrays.asList("3", "4", "5", null), tail.stream().map(Event::id).toList(), "after is a span number on this path");
    }

    @Test
    void eventsFollowALiveTurnAndADisconnectDoesNotStopIt() throws Exception {
        ToolLoader.SLOW_LOOKUP_MS = 300;
        startWorker();
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("turnId").asText();

        HttpResponse<InputStream> live = client.send(HttpRequest.newBuilder(uri("/turns/" + id + "/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        Event first;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(live.body()))) {
            first = events(block(reader)).get(0);
            assertEquals("RUNNING", JSON.readTree(get("/turns/" + id).body()).get("status").asText(), "the tool is still sleeping");
        }
        assertEquals("1", first.id());
        assertEquals("LLM", JSON.readTree(first.data()).get("kind").asText(), "the first row arrived while the turn was live");

        JsonNode turn = awaitDone(id);
        assertEquals(5, turn.get("spans").size(), "the turn finished although its listener left");
        assertEquals(ANSWER, turn.get("answer").asText());
        List<Event> whole = events(get("/turns/" + id + "/events").body());
        assertEquals(STREAM_IDS, whole.stream().map(Event::id).toList(), "the stream is still held: five rows, ten pieces, done");
        assertEquals(ANSWER, String.join("", tokens(whole)));
    }

    @Test
    void twoToolsInOneReplyRunTogether() throws Exception {
        ToolLoader.SLOW_LOOKUP_MS = 300;
        startWorker();
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"Status of order ORD-2 and is product PRD-1 available?\"}").body())
                .get("turnId").asText();

        JsonNode turn = awaitDone(id);
        JsonNode spans = turn.get("spans");
        assertEquals(List.of("LLM", "TOOL", "TOOL", "NOTE", "LLM"), spans.findValuesAsText("kind"));
        assertEquals("order_status", spans.get(1).get("attrs").get("tool").asText(), "rows in request order");
        assertEquals("stock_status", spans.get(2).get("attrs").get("tool").asText());
        assertTrue(spans.get(1).get("ms").asLong() >= 250 && spans.get(2).get("ms").asLong() >= 250, "each lookup slept");
        Matcher note = TOGETHER.matcher(spans.get(3).get("request").asText());
        assertTrue(note.matches(), spans.get(3).toString());
        assertTrue(Long.parseLong(note.group(2)) < Long.parseLong(note.group(3)), "wall < work: " + note.group());
        assertEquals("Order ORD-2: PROCESSING\nProduct PRD-1: 42 units in stock", turn.get("answer").asText());
    }

    @Test
    void aFollowUpTurnInTheConversationDoesNotRunTheRepeatedCall() throws Exception {
        startWorker();
        JsonNode first = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"status of order ORD-1\"}").body());
        String conversation = first.get("conversationId").asText();
        awaitDone(first.get("turnId").asText());

        HttpResponse<String> accepted = post("/runs", "{\"conversationId\":\"" + conversation + "\",\"question\":\"status of order ORD-1 again\"}");
        assertEquals(202, accepted.statusCode());
        JsonNode second = JSON.readTree(accepted.body());
        assertEquals(conversation, second.get("conversationId").asText(), "the same conversation");
        assertFalse(second.get("turnId").asText().equals(first.get("turnId").asText()), "a turn of its own");

        JsonNode turn = awaitDone(second.get("turnId").asText());
        assertEquals("support", turn.get("agent").asText(), "the conversation's agent");
        JsonNode spans = turn.get("spans");
        assertEquals(List.of(6, 7, 8), spans.findValues("span").stream().map(JsonNode::asInt).toList(),
                "numbered on from the first turn's five; a reference names no id, so nothing chains");
        assertEquals("DEDUPED", spans.get(1).get("status").asText());
        assertEquals("unchanged, see step 2", spans.get(1).get("response").asText());
        assertEquals("2", spans.get(1).get("attrs").get("ref").asText(), "the first turn's TOOL row");
        assertEquals(ORDER, turn.get("answer").asText(), "the earlier output, relayed");
        assertEquals(1, ToolLoader.HANDLER_CALLS.get("order_status"), "the handler ran once for the two turns");

        assertEquals(400, post("/runs", "{\"conversationId\":\"c-0000\",\"question\":\"q\"}").statusCode());
        assertEquals("conversation " + conversation + " belongs to agent support",
                JSON.readTree(post("/runs", "{\"conversationId\":\"" + conversation + "\",\"agent\":\"chaos\",\"question\":\"q\"}").body()).get("error").asText());
    }

    @Test
    void aDeadWorkersTurnIsResumedByTheNextWorkerAndEndsDone() throws Exception {
        String id = JSON.readTree(post("/runs", "{\"agent\":\"support\",\"question\":\"" + QUESTION + "\"}").body()).get("turnId").asText();

        DyingWorker dying = new DyingWorker(AGENTS, TOOLS, store, queue, "worker-a", 200, 2);
        Thread.ofVirtual().start(dying::once);
        assertTrue(dying.died.await(5, TimeUnit.SECONDS), "the first worker died after row 2");
        JsonNode orphan = JSON.readTree(get("/turns/" + id).body());
        assertEquals("RUNNING", orphan.get("status").asText(), "a dead worker leaves the turn RUNNING");
        assertEquals("worker-a", orphan.get("claimedBy").asText());
        assertEquals(1, orphan.get("attempts").asInt());
        assertEquals(List.of("LLM", "TOOL"), orphan.get("spans").findValuesAsText("kind"));
        assertEquals(1, queue.size(), "no ack: the message is still there, hidden for the lease");

        Thread.sleep(250);
        assertTrue(new Worker(AGENTS, TOOLS, store, queue, "worker-b", 200).once(), "the lease ran out: the next worker received it");

        JsonNode done = JSON.readTree(get("/turns/" + id).body());
        assertEquals("DONE", done.get("status").asText());
        assertEquals("worker-b", done.get("claimedBy").asText());
        assertEquals(2, done.get("attempts").asInt());
        assertEquals(List.of("LLM", "TOOL", "NOTE", "LLM", "TOOL", "LLM"), done.get("spans").findValuesAsText("kind"),
                "the second worker continues the chain: pass 2 asks for the shipment the saved result names");
        assertEquals("resumed from step 2", done.get("spans").get(2).get("request").asText());
        assertEquals("shipping_status", done.get("spans").get(4).get("attrs").get("tool").asText());
        assertEquals(ANSWER, done.get("answer").asText());
        assertEquals(1, ToolLoader.HANDLER_CALLS.get("order_status"), "the saved tool call did not run again");
        List<Event> events = events(get("/turns/" + id + "/events").body());
        assertEquals(List.of("step", "step", "step", "step", "step", "step", "done"), events.stream().map(Event::event).toList(),
                "neither worker was wired to the stream, so this is the fallback: the saved rows once, no tokens");
        assertEquals(Arrays.asList("1", "2", "3", "4", "5", "6", null), events.stream().map(Event::id).toList(),
                "the ids are the span numbers: the rows, whoever wrote them");
        assertEquals("resumed from step 2", JSON.readTree(events.get(2).data()).get("request").asText());
        assertEquals(ANSWER, JSON.readTree(events.getLast().data()).get("answer").asText());
    }

    @Test
    void aConciergeTurnDelegatesToTheLogisticsAgentAndTheStreamShowsTheNestedSpans() throws Exception {
        startWorker();
        String id = JSON.readTree(post("/runs", "{\"agent\":\"concierge\",\"question\":\"Where is shipment SHP-1 for my order?\"}").body())
                .get("turnId").asText();

        JsonNode turn = awaitDone(id);
        JsonNode spans = turn.get("spans");
        assertEquals(List.of("LLM", "LLM", "TOOL", "LLM", "AGENT", "LLM"), spans.findValuesAsText("kind"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), spans.findValues("span").stream().map(JsonNode::asInt).toList(), "one numbering, one turn");
        assertEquals("ask_logistics", spans.get(0).get("attrs").get("tool").asText(), "the concierge delegates");
        assertFalse(spans.get(0).get("attrs").has("depth"), "the parent's rows carry no depth");
        for (int child : List.of(1, 2, 3)) {
            assertEquals("1", spans.get(child).get("attrs").get("parent").asText(), "span " + spans.get(child).get("span") + " hangs under the delegating span");
            assertEquals("1", spans.get(child).get("attrs").get("depth").asText());
        }
        assertEquals("shipping_status", spans.get(2).get("attrs").get("tool").asText(), "the child ran its own tool");
        assertEquals("Shipment SHP-1: IN TRANSIT, arriving 2026-10-16", spans.get(4).get("response").asText(), "the AGENT row carries the child's answer");
        assertEquals(id + "/1", spans.get(4).get("attrs").get("key").asText());
        assertEquals("Shipment SHP-1: IN TRANSIT, arriving 2026-10-16", turn.get("answer").asText());

        List<Event> events = events(get("/turns/" + id + "/events").body());
        assertEquals(List.of("step", "step", "step", "step", "step", "step"), events.subList(0, 6).stream().map(Event::event).toList(),
                "the stream sends the child's spans too");
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), events.subList(0, 6).stream().map(Event::id).toList());
        assertEquals("1", JSON.readTree(events.get(2).data()).get("attrs").get("parent").asText());
        assertEquals("AGENT", JSON.readTree(events.get(4).data()).get("kind").asText());
        assertEquals(List.of("Shipment ", "SHP-1: ", "IN ", "TRANSIT, ", "arriving ", "2026-10-16"), tokens(events),
                "the child's answer is a tool result, not pieces: only the parent's answer streams, once");
        assertEquals("done", events.getLast().event());
        assertEquals(13, events.size(), "six rows, six pieces, one done");
    }

    @Test
    void postEvalsRunsTheGoldenSetAndReturnsTheTable() throws Exception {
        HttpResponse<String> reply = post("/evals", "");
        assertEquals(200, reply.statusCode());
        JsonNode table = JSON.readTree(reply.body());
        JsonNode cases = table.get("cases");
        assertEquals(3, cases.size());
        assertEquals(1, cases.get(0).get("n").asInt());
        assertTrue(cases.get(0).get("passed").asBoolean());
        assertEquals(List.of("order_status", "shipping_status"), texts(cases.get(0).get("tools")), "the chained call counts as a choice");
        assertEquals(3, cases.get(0).get("passes").asInt());
        assertEquals(ANSWER, cases.get(0).get("answer").asText());
        assertTrue(cases.get(1).get("passed").asBoolean(), "the throw is named in the answer");
        assertEquals("Tool throw_boom ERROR: Tool failed: boom", cases.get(1).get("answer").asText());
        assertFalse(cases.get(2).get("passed").asBoolean(), "parcel matches no tool: the stub's known limit");
        assertEquals("tool_choice: expected [shipping_status] got []", cases.get(2).get("failures").get(0).asText());
        assertEquals(4, table.get("lines").size(), "three rows and the footer");
        assertTrue(table.get("lines").get(0).asText().startsWith(" 1  PASS  support "), table.get("lines").get(0).asText());
        assertEquals(table.get("footer").asText(), table.get("lines").get(3).asText());
        assertTrue(table.get("footer").asText().startsWith("harness: tool_choice 2/3 · stopped_by_done 3/3 · errors_surfaced 3/3"), table.get("footer").asText());
        assertEquals(405, get("/evals").statusCode());
    }

    // --- helpers ---

    private JsonNode awaitDone(String id) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            JsonNode turn = JSON.readTree(get("/turns/" + id).body());
            if (turn.get("status").asText().equals("DONE")) {
                return turn;
            }
            assertTrue(System.nanoTime() < deadline, "turn " + id + " did not finish in 5 s: " + turn);
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).build());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)).build());
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

    /** The text of every token event, in order. */
    private static List<String> tokens(List<Event> events) {
        List<String> texts = new ArrayList<>();
        for (Event event : events) {
            if (event.event().equals("token")) {
                try {
                    texts.add(JSON.readTree(event.data()).get("text").asText());
                } catch (IOException e) {
                    throw new AssertionError("not JSON: " + event.data(), e);
                }
            }
        }
        return texts;
    }

    record Event(String id, String event, String data) {
    }
}
