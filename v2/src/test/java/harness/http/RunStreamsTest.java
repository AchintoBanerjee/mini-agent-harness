package harness.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The per-run stream the API holds: ids in order, reads from a last id, a wait for the next append, done, expiry. */
class RunStreamsTest {

    private final RunStreams streams = new RunStreams();

    @Test
    void appendNumbersTheEntriesFromOneAndReadStartsAfterTheGivenId() {
        assertEquals(1, streams.append("r-1", "step", "{\"span\":1}"));
        assertEquals(2, streams.append("r-1", "step", "{\"span\":2}"));
        assertEquals(3, streams.append("r-1", "token", "{\"text\":\"Order \"}"));
        assertEquals(1, streams.append("r-2", "step", "{\"span\":1}"), "each turn has its own numbering");

        List<RunStreams.Entry> tail = streams.read("r-1", 1, 0);
        assertEquals(List.of(2, 3), tail.stream().map(RunStreams.Entry::id).toList());
        assertEquals(List.of("step", "token"), tail.stream().map(RunStreams.Entry::event).toList());
        assertEquals("{\"text\":\"Order \"}", tail.get(1).data());
        assertEquals(3, streams.read("r-1", 0, 0).size());
        assertEquals(3, streams.read("r-1", -1, 0).size(), "an id below 0 reads from the start");
        assertEquals(List.of(), streams.read("r-1", 3, 0), "nothing after the last id");
    }

    @Test
    void aReadOnAnEmptyStreamWaitsForAnAppendFromAnotherThread() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                reading.await();
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            streams.append("r-1", "step", "{\"span\":1}");
        });
        long started = System.nanoTime();
        reading.countDown();

        List<RunStreams.Entry> entries = streams.read("r-1", 0, 2000);

        long waited = (System.nanoTime() - started) / 1_000_000;
        assertEquals(1, entries.size(), "the read came back with the appended entry");
        assertEquals(1, entries.get(0).id());
        assertTrue(waited >= 30 && waited < 1500, "woken by the append, not by the timeout: " + waited + " ms");
        assertEquals(List.of(), streams.read("r-1", 1, 50), "nothing more: the wait ran out, empty");
    }

    @Test
    void doneFinishesTheStreamAndALaterReadReturnsAtOnce() {
        streams.append("r-1", "step", "{}");
        assertTrue(!streams.get("r-1").finished());

        streams.append("r-1", "done", "{\"status\":\"DONE\"}");

        assertTrue(streams.get("r-1").finished());
        long started = System.nanoTime();
        assertEquals(List.of(), streams.read("r-1", 2, 2000), "nothing after done");
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500, "a finished stream never waits");
        assertEquals("done", streams.read("r-1", 1, 0).get(0).event());
    }

    @Test
    void nothingAppendedAfterDoneIsKept() {
        streams.append("r-1", "step", "{}");
        assertNull(streams.get("r-1").done(), "no done yet");
        streams.append("r-1", "done", "{\"status\":\"DONE\"}");

        assertEquals(0, streams.append("r-1", "step", "{\"late\":true}"), "ignored: the stream has ended");
        assertEquals(0, streams.append("r-1", "done", "{}"), "a second done too");

        assertEquals(2, streams.get("r-1").size(), "the late entries were never added");
        RunStreams.Entry done = streams.get("r-1").done();
        assertEquals(2, done.id());
        assertEquals("done", done.event());
        assertEquals(List.of(done), streams.read("r-1", 1, 0), "done stays the last entry");
        assertEquals(List.of(), streams.read("r-1", 2, 0));
    }

    @Test
    void forgetDropsTheStreamNow() {
        RunStreams.Stream opened = streams.open("r-1");
        streams.append("r-2", "step", "{}");

        streams.forget("r-1");

        assertNull(streams.get("r-1"), "gone, finished or not");
        assertNotNull(streams.get("r-2"), "the others stay");
        assertNotSame(opened, streams.open("r-1"), "opened again: a new stream");
    }

    @Test
    void getIsNullForAnUnknownRunAndOpenCreatesTheStream() {
        assertNull(streams.get("r-9"));

        RunStreams.Stream opened = streams.open("r-9");

        assertNotNull(opened);
        assertEquals(0, opened.size());
        assertSame(opened, streams.get("r-9"), "opened once, found after");
        assertSame(opened, streams.open("r-9"));
        streams.append("r-9", "step", "{}");
        assertEquals(1, opened.size(), "the worker's append lands in the stream the reader opened");
    }

    @Test
    void aFinishedStreamIsDroppedOnTheNextLookupAfterItsTimeToLive() throws Exception {
        streams.ttlMs(100);
        streams.append("r-1", "step", "{}");
        streams.append("r-1", "done", "{}");
        streams.append("r-2", "step", "{}");
        assertNotNull(streams.get("r-1"));

        Thread.sleep(150);

        assertNull(streams.get("r-1"), "finished and past its time: gone");
        assertNotNull(streams.get("r-2"), "not finished: kept whatever the clock says");
        assertEquals(0, streams.open("r-1").size(), "a new, empty stream after the old one was dropped");
    }
}
