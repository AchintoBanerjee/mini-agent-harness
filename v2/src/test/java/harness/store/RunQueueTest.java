package harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three queue operations on a temp SQLite file: the lease, the extension, the ack, the dead letter. */
class RunQueueTest {

    @TempDir
    Path dir;
    private SqliteRunStore store;

    @BeforeEach
    void open() {
        store = new SqliteRunStore(dir.resolve("harness.db"));
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void submitWritesAQueuedJobRowAndOneMessage() {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit("support", "What is the status of order ORD-1?");

        Job job = store.job(id);
        assertEquals(Job.Status.QUEUED, job.status());
        assertEquals("support", job.agent());
        assertEquals("What is the status of order ORD-1?", job.question());
        assertEquals(0, job.attempts());
        assertEquals(1, queue.size());
        assertEquals(List.of(), store.load(id), "no steps until a worker runs it");
    }

    @Test
    void receiveHidesTheMessageForTheVisibilityWindowThenHandsItOutAgain() throws Exception {
        RunQueue queue = new RunQueue(store);
        String first = queue.submit("support", "one");
        String second = queue.submit("support", "two");

        assertEquals(new RunQueue.Message(first, 1, false), queue.receive(100).orElseThrow(), "the oldest message first");
        assertEquals(new RunQueue.Message(second, 1, false), queue.receive(100).orElseThrow());
        assertTrue(queue.receive(100).isEmpty(), "both are leased: nothing to receive");
        assertEquals(2, queue.size(), "leased is not gone");

        Thread.sleep(130);
        assertEquals(new RunQueue.Message(first, 2, false), queue.receive(100).orElseThrow(), "the lease ran out: received again, counted");
        assertEquals(new RunQueue.Message(second, 2, false), queue.receive(100).orElseThrow());
    }

    @Test
    void extendPushesTheWindowForward() throws Exception {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit("support", "q");
        queue.receive(60).orElseThrow();
        queue.extend(id, 250);

        Thread.sleep(100);
        assertTrue(queue.receive(60).isEmpty(), "the first window passed, the extended one has not");
        Thread.sleep(200);
        assertEquals(new RunQueue.Message(id, 2, false), queue.receive(60).orElseThrow());
    }

    @Test
    void ackRemovesTheMessageForGood() throws Exception {
        RunQueue queue = new RunQueue(store);
        String id = queue.submit("support", "q");
        queue.receive(0).orElseThrow();
        queue.ack(id);

        assertEquals(0, queue.size());
        assertTrue(queue.receive(0).isEmpty(), "nothing comes back after the ack, even with no window");
        assertEquals(Job.Status.QUEUED, store.job(id).status(), "the queue does not touch the job row: the worker does");
    }

    @Test
    void theReceiveAfterMaxReceivesDeadLettersTheJob() {
        RunQueue queue = new RunQueue(store, 2);
        String id = queue.submit("support", "q");

        assertEquals(new RunQueue.Message(id, 1, false), queue.receive(0).orElseThrow());
        assertEquals(new RunQueue.Message(id, 2, false), queue.receive(0).orElseThrow(), "delivered twice, as allowed");
        Optional<RunQueue.Message> third = queue.receive(0);
        assertEquals(new RunQueue.Message(id, 3, true), third.orElseThrow(), "the third receive is one too many: dead");

        assertEquals(Job.Status.DEAD, store.job(id).status());
        assertEquals(List.of(id), store.withStatus(Job.Status.DEAD).stream().map(Job::id).toList(), "the dead-letter list");
        assertEquals(0, queue.size(), "the message is gone");
        assertTrue(queue.receive(0).isEmpty());
        assertFalse(store.withStatus(Job.Status.QUEUED).stream().anyMatch(job -> job.id().equals(id)));
    }
}
