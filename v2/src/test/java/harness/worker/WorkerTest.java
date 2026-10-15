package harness.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Context;
import harness.core.RunListener;
import harness.core.Span;
import harness.core.Turn;
import harness.http.HttpApi;
import harness.model.LLMProxy;
import harness.model.ModelReply;
import harness.model.RuleBasedLLM;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.store.TurnRow;
import harness.tools.Tool;
import harness.tools.ToolLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dead worker, without a dead process (DyingWorker). The turn stays RUNNING, its lease
 * runs out, and the next worker over the same temp file receives it and resumes from the
 * saved rows. And the worker's side of the run's stream: every event in order to whatever
 * stream(...) is set to, and an HttpPush that cannot reach the API, or whose pushes the API
 * turns away, never stops the loop.
 */
class WorkerTest {

    private static final String QUESTION = "What is the status of order ORD-1?";
    private static final String ANSWER = "Order ORD-1: SHIPPED, shipment SHP-1\nShipment SHP-1: IN TRANSIT, arriving 2026-10-16";
    private static final Path AGENTS = Path.of("agents");
    private static final Path TOOLS = Path.of("tools");

    @TempDir
    Path dir;
    private SqliteRunStore store;
    private final CountingLLM llm = new CountingLLM();

    @BeforeEach
    void open() {
        ToolLoader.HANDLER_CALLS.clear();
        store = new SqliteRunStore(dir.resolve("harness.db"));
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void aDeadWorkersTurnIsReceivedAgainAfterItsLeaseAndResumedFromTheLastStep() throws Exception {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit(store.newConversation("support"), QUESTION);

        DyingWorker first = new DyingWorker(AGENTS, TOOLS, store, queue, "worker-a", 200, 2);
        first.model(llm);
        Thread.ofVirtual().start(first::once);
        assertTrue(first.died.await(5, TimeUnit.SECONDS), "the first worker died after row 2");

        TurnRow orphan = store.turn(id);
        assertEquals(TurnRow.Status.RUNNING, orphan.status(), "a dead worker leaves the turn RUNNING");
        assertEquals("worker-a", orphan.claimedBy());
        assertEquals(1, orphan.attempts());
        assertEquals(null, orphan.answer());
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL), kinds(store.load(id)));
        assertEquals(1, queue.size(), "no ack: the message is still there");
        assertTrue(queue.receive(200).isEmpty(), "but hidden while the lease holds");

        Thread.sleep(250);
        Worker second = new Worker(AGENTS, TOOLS, store, queue, "worker-b", 200);
        second.model(llm);
        assertTrue(second.once(), "the lease ran out: the second worker received it");

        TurnRow done = store.turn(id);
        assertEquals(TurnRow.Status.DONE, done.status());
        assertEquals(ANSWER, done.answer());
        assertEquals("worker-b", done.claimedBy());
        assertEquals(2, done.attempts());
        List<Span> rows = store.load(id);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.NOTE, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM), kinds(rows));
        assertEquals("resumed from step 2", rows.get(2).request());
        assertEquals("ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]", rows.get(3).response(),
                "the second worker continues the chain at pass 2, from the saved result");
        assertEquals("Done[]", rows.get(5).response());
        assertEquals(0, queue.size(), "acked");
        assertEquals(1, ToolLoader.HANDLER_CALLS.get("order_status"), "the saved tool call did not run again");
        assertEquals(1, ToolLoader.HANDLER_CALLS.get("shipping_status"));
        assertEquals(1, llm.decisionAsks, "the model decided the tool call once, in the first worker");
        assertEquals(3, llm.asks, "and was asked only for the new steps by the second: chain, then Done");
    }

    @Test
    void onceReturnsFalseOnAnEmptyQueueAndTrueAfterRunningATurn() {
        RunQueue queue = new RunQueue(store);
        Worker worker = new Worker(AGENTS, TOOLS, store, queue, "worker-a", 300);
        assertFalse(worker.once());

        String id = queue.submit(store.newConversation("support"), QUESTION);
        assertTrue(worker.once());
        assertEquals(TurnRow.Status.DONE, store.turn(id).status());
        assertEquals(5, store.load(id).size());
        assertEquals(0, queue.size());
    }

    @Test
    void aWorkerWithAStreamSetPushesEveryRowThenEachPieceOfTheAnswerThenDoneInOrder() {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit(store.newConversation("support"), QUESTION);
        Worker worker = new Worker(AGENTS, TOOLS, store, queue, "worker-a", 300);
        List<String> pushed = new ArrayList<>();
        worker.stream(new RunListener() {
            @Override
            public void on(Span span) {
                pushed.add("step " + span.span() + " " + span.kind());
            }

            @Override
            public void token(String turnId, String text) {
                pushed.add("token " + turnId + " " + text);
            }

            @Override
            public void done(String turnId, TurnRow.Status status, String answer) {
                pushed.add("done " + turnId + " " + status + " " + answer);
            }
        });

        assertTrue(worker.once());

        assertEquals(List.of("step 1 LLM", "step 2 TOOL", "step 3 LLM", "step 4 TOOL", "step 5 LLM",
                        "token " + id + " Order ", "token " + id + " ORD-1: ", "token " + id + " SHIPPED, ",
                        "token " + id + " shipment ", "token " + id + " SHP-1\nShipment ", "token " + id + " SHP-1: ",
                        "token " + id + " IN ", "token " + id + " TRANSIT, ", "token " + id + " arriving ", "token " + id + " 2026-10-16",
                        "done " + id + " DONE " + ANSWER),
                pushed, "the rows as they land, the answer as the model produces it, then the end");
        assertEquals(TurnRow.Status.DONE, store.turn(id).status());
    }

    @Test
    void anHttpPushAgainstAClosedPortSaysSoOnceAndTheTurnStillEndsDone() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        RunQueue queue = new RunQueue(store);
        String id = queue.submit(store.newConversation("support"), QUESTION);
        Worker worker = new Worker(AGENTS, TOOLS, store, queue, "worker-a", 300);
        worker.stream(new HttpPush("http://localhost:" + closedPort));
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(console, true));
        try {
            assertTrue(worker.once(), "the push failed on every event and the loop never noticed");
        } finally {
            System.setOut(stdout);
        }

        assertEquals(TurnRow.Status.DONE, store.turn(id).status());
        assertEquals(ANSWER, store.turn(id).answer());
        assertEquals(0, queue.size(), "acked");
        String printed = console.toString();
        String warning = "stream: cannot reach http://localhost:" + closedPort + ", clients will read the saved rows";
        assertEquals(1, printed.split(java.util.regex.Pattern.quote(warning), -1).length - 1, "said once, for sixteen events:\n" + printed);
    }

    @Test
    void aPushTheApiTurnsAwayIsSaidOnceLikeAFailedOneAndTheTurnStillEndsDone() throws Exception {
        SqliteRunStore other = new SqliteRunStore(dir.resolve("other.db"));
        HttpApi api = new HttpApi(AGENTS, TOOLS, dir.resolve("golden.json"), other, new RunQueue(other));
        api.start(0);
        RunQueue queue = new RunQueue(store);
        String id = queue.submit(store.newConversation("support"), QUESTION);
        Worker worker = new Worker(AGENTS, TOOLS, store, queue, "worker-a", 300);
        worker.stream(new HttpPush("http://localhost:" + api.port()));
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(console, true));
        try {
            assertTrue(worker.once(), "the API's store has no such turn, so every push came back 404, and the loop never noticed");
        } finally {
            System.setOut(stdout);
            api.stop();
            other.close();
        }

        assertEquals(TurnRow.Status.DONE, store.turn(id).status());
        assertEquals(0, queue.size(), "acked");
        String printed = console.toString();
        String warning = "stream: cannot reach http://localhost:" + api.port() + ", clients will read the saved rows";
        assertEquals(1, printed.split(java.util.regex.Pattern.quote(warning), -1).length - 1, "said once, for sixteen rejected pushes:\n" + printed);
    }

    private static List<Span.Kind> kinds(List<Span> rows) {
        return rows.stream().map(Span::kind).toList();
    }

    /** The rules, plus a count of how often the model was asked, and how often with no tool result yet. */
    static class CountingLLM implements LLMProxy {

        private final RuleBasedLLM rules = new RuleBasedLLM();
        int asks;
        int decisionAsks;

        @Override
        public ModelReply next(Context context, List<Tool> tools) {
            asks++;
            if (context.spans().stream().noneMatch(span -> span.kind() == Span.Kind.TOOL)) {
                decisionAsks++;
            }
            return rules.next(context, tools);
        }

        @Override
        public String answer(Turn turn, Consumer<String> out) {
            return rules.answer(turn, out);
        }
    }
}
