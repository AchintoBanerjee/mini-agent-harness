package harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three queue operations on a temp SQLite file: the lease, the extension, the ack. */
class RunQueueTest {

    @TempDir
    Path dir;
    private SqliteRunStore store;
    private String conversation;

    @BeforeEach
    void open() {
        store = new SqliteRunStore(dir.resolve("harness.db"));
        conversation = store.newConversation("support");
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void submitWritesAQueuedTurnRowAndOneMessage() {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit(conversation, "What is the status of order ORD-1?");

        TurnRow turn = store.turn(id);
        assertEquals(TurnRow.Status.QUEUED, turn.status());
        assertEquals(conversation, turn.conversationId());
        assertEquals("support", turn.agent());
        assertEquals("What is the status of order ORD-1?", turn.question());
        assertEquals(0, turn.attempts());
        assertEquals(1, queue.size());
        assertEquals(List.of(), store.load(id), "no spans until a worker runs it");
    }

    @Test
    void receiveHidesTheMessageForTheVisibilityWindowThenHandsItOutAgain() throws Exception {
        RunQueue queue = new RunQueue(store);
        String first = queue.submit(conversation, "one");
        String second = queue.submit(conversation, "two");

        assertEquals(new RunQueue.Message(first, 1), queue.receive(100).orElseThrow(), "the oldest message first");
        assertEquals(new RunQueue.Message(second, 1), queue.receive(100).orElseThrow());
        assertTrue(queue.receive(100).isEmpty(), "both are leased: nothing to receive");
        assertEquals(2, queue.size(), "leased is not gone");

        Thread.sleep(130);
        assertEquals(new RunQueue.Message(first, 2), queue.receive(100).orElseThrow(), "the lease ran out: received again, counted");
        assertEquals(new RunQueue.Message(second, 2), queue.receive(100).orElseThrow());
    }

    @Test
    void extendPushesTheWindowForward() throws Exception {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit(conversation, "q");
        queue.receive(60).orElseThrow();
        queue.extend(id, 250);

        Thread.sleep(100);
        assertTrue(queue.receive(60).isEmpty(), "the first window passed, the extended one has not");
        Thread.sleep(200);
        assertEquals(new RunQueue.Message(id, 2), queue.receive(60).orElseThrow());
    }

    @Test
    void ackRemovesTheMessageForGood() {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit(conversation, "q");
        queue.receive(0).orElseThrow();
        queue.ack(id);

        assertEquals(0, queue.size());
        assertTrue(queue.receive(0).isEmpty(), "nothing comes back after the ack, even with no window");
        assertEquals(TurnRow.Status.QUEUED, store.turn(id).status(), "the queue does not touch the turn row: the worker does");
    }
}
