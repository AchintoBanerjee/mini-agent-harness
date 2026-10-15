package harness.store;

import harness.core.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where the rows of a run go. The Orchestrator appends every row here BEFORE it takes the
 * next step, so whatever happens to the process, the log says how far the run got. The
 * job row next to the rows says whether the loop is queued, running, finished or threw.
 */
public interface RunStore {

    /** Opens a run (a QUEUED job row) and returns its id. */
    String newRun(String agent, String question);

    /** Adds one row to the end of the run. Rows are never rewritten. */
    void append(String runId, Span span);

    /** Every row of the run, in step order. */
    List<Span> load(String runId);

    boolean exists(String runId);

    /** The job row: agent, question, status, who claimed it and how often, and times. */
    Job job(String runId);

    /** QUEUED until a worker takes it, RUNNING while the loop runs, DONE at the end, FAILED if it threw, DEAD when given up. */
    void setStatus(String runId, Job.Status status);

    /** A worker takes the job: RUNNING, the worker's name on the row, attempts + 1. Returns the new attempt number. */
    int claim(String runId, String claimedBy);

    /** The job rows in that status, oldest first: the dead-letter list is withStatus(DEAD). */
    List<Job> withStatus(Job.Status status);

    /** The rows from that step on: what replay prints and what an SSE client missed. */
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
