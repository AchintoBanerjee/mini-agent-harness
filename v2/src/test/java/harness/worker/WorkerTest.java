package harness.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Context;
import harness.core.Span;
import harness.model.LLMProxy;
import harness.model.ModelReply;
import harness.model.RuleBasedLLM;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.store.TurnRow;
import harness.tools.Tool;
import harness.tools.ToolLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dead worker, without a dead process (DyingWorker). The turn stays RUNNING, its lease
 * runs out, and the next worker over the same temp file receives it and resumes from the
 * saved rows.
 */
class WorkerTest {

    private static final String QUESTION = "What is the status of order ORD-1?";
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
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", done.answer());
        assertEquals("worker-b", done.claimedBy());
        assertEquals(2, done.attempts());
        List<Span> rows = store.load(id);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.NOTE, Span.Kind.LLM), kinds(rows));
        assertEquals("resumed from step 2", rows.get(2).request());
        assertEquals("Done[]", rows.get(3).response());
        assertEquals(0, queue.size(), "acked");
        assertEquals(1, ToolLoader.HANDLER_CALLS.get("order_status"), "the saved tool call did not run again");
        assertEquals(1, llm.decisionAsks, "the model decided the tool call once, in the first worker");
        assertEquals(2, llm.asks, "and was asked only for the new step by the second");
    }

    @Test
    void onceReturnsFalseOnAnEmptyQueueAndTrueAfterRunningATurn() {
        RunQueue queue = new RunQueue(store);
        Worker worker = new Worker(AGENTS, TOOLS, store, queue, "worker-a", 300);
        assertFalse(worker.once());

        String id = queue.submit(store.newConversation("support"), QUESTION);
        assertTrue(worker.once());
        assertEquals(TurnRow.Status.DONE, store.turn(id).status());
        assertEquals(3, store.load(id).size());
        assertEquals(0, queue.size());
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
    }
}
