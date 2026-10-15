package harness.core;

import harness.store.TurnRow;

/**
 * Told what the loop does as it does it. on: a row landed in the store (the console listener
 * prints it, tests collect it). token: a piece of the answer, as the model produces it. done:
 * the turn ended. The worker forwards all three to the run's stream.
 */
public interface RunListener {

    /** A row landed in the store. */
    void on(Span span);

    /** A piece of the answer, as the model produces it. */
    default void token(String turnId, String text) {
    }

    /** The turn ended: DONE with its answer, or FAILED. */
    default void done(String turnId, TurnRow.Status status, String answer) {
    }
}
