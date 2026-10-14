package harness.store;

import harness.core.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where the rows of a run go. The Orchestrator appends every row here BEFORE it takes the
 * next step, so whatever happens to the process, the log says how far the run got.
 */
public interface RunStore {

    /** Opens a run and returns its id. */
    String newRun(String agent, String question);

    /** Adds one row to the end of the run. Rows are never rewritten. */
    void append(String runId, Span span);

    /** Every row of the run, in step order. */
    List<Span> load(String runId);

    boolean exists(String runId);

    /** The rows from that step on: what replay prints. */
    default List<Span> load(String runId, int fromStep) {
        List<Span> tail = new ArrayList<>();
        for (Span span : load(runId)) {
            if (span.step() >= fromStep) {
                tail.add(span);
            }
        }
        return tail;
    }

    /** A fresh id: r- plus 4 hex chars, short enough to type back. Stores retry on a clash. */
    static String newId() {
        return String.format("r-%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }
}
