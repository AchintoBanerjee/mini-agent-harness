package harness.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The run's stream, held in this process: entries with ids per turn, read from the last id a
 * client saw. In production this is a Redis Stream per job id, so any API node can read it;
 * here it is a map in this process. The worker, another process, pushes into it over HTTP.
 * The done entry ends a stream: nothing appended after it is kept. A finished stream stays
 * for TTL_MS so a late or reconnecting client can still read it, and is dropped on the next
 * lookup after that: no sweeper thread. A stream a reader opened for a turn that then ended
 * without a worker feeding it is dropped by that reader (forget), so it does not stay for
 * the process's life.
 */
public class RunStreams {

    /** How long a finished stream stays readable. */
    public static final long TTL_MS = 60_000;

    /** One event of the stream: its id (1, 2, 3... per turn), the event name and the data, a JSON string. */
    public record Entry(int id, String event, String data) {
    }

    /** One turn's entries and whether its done has arrived; readers wait on it for the next append. */
    public static class Stream {

        private final List<Entry> entries = new ArrayList<>();
        private boolean finished;
        private long finishedAt;
        private Entry done;

        /** Adds the entry and wakes the readers; done finishes the stream, and an append after it is ignored (0). */
        synchronized int append(String event, String data) {
            if (finished) {
                return 0;
            }
            Entry entry = new Entry(entries.size() + 1, event, data);
            entries.add(entry);
            if (event.equals("done")) {
                finished = true;
                finishedAt = System.currentTimeMillis();
                done = entry;
            }
            notifyAll();
            return entry.id();
        }

        /** The entries after afterId (below 0 reads from the start); when there are none and the stream is not finished, waits up to waitMs for one. */
        public synchronized List<Entry> read(int afterId, long waitMs) {
            int from = Math.max(0, afterId);
            if (from >= entries.size() && !finished && waitMs > 0) {
                try {
                    wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return from >= entries.size() ? List.of() : List.copyOf(entries.subList(from, entries.size()));
        }

        public synchronized int size() {
            return entries.size();
        }

        public synchronized boolean finished() {
            return finished;
        }

        /** The done entry, the stream's last: null until it arrives. */
        public synchronized Entry done() {
            return done;
        }

        synchronized boolean expired(long ttlMs) {
            return finished && System.currentTimeMillis() > finishedAt + ttlMs;
        }
    }

    private final Map<String, Stream> streams = new HashMap<>();
    private long ttlMs = TTL_MS;

    /** Adds an event to the turn's stream, opening it on first use, and returns the entry's id; "done" finishes the stream, and an append after it is ignored (0). */
    public int append(String turnId, String event, String data) {
        return open(turnId).append(event, data);
    }

    /** The entries of the turn's stream after afterId, waiting up to waitMs for one when there are none yet. */
    public List<Entry> read(String turnId, int afterId, long waitMs) {
        return open(turnId).read(afterId, waitMs);
    }

    /** The turn's stream, or null when this process never saw the run or its time ran out. */
    public synchronized Stream get(String turnId) {
        Stream stream = streams.get(turnId);
        if (stream != null && stream.expired(ttlMs)) {
            streams.remove(turnId);
            return null;
        }
        return stream;
    }

    /** The turn's stream, opened if need be, so a reader can wait for a worker that has not pushed yet. */
    public synchronized Stream open(String turnId) {
        Stream stream = get(turnId);
        if (stream == null) {
            stream = new Stream();
            streams.put(turnId, stream);
        }
        return stream;
    }

    /** Drops the turn's stream now: for an empty one a reader opened for a turn that ended without a worker feeding it. */
    public synchronized void forget(String turnId) {
        streams.remove(turnId);
    }

    /** How long a finished stream stays; tests shorten it. */
    void ttlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }
}
