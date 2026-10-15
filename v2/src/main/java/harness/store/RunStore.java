package harness.store;

import harness.core.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where the rows go: a conversation has turns, a turn (job id) has spans. The Orchestrator
 * appends every span here BEFORE it takes the next step, so whatever happens to the
 * process, the rows say how far the turn got. The turn row next to them says whether the
 * loop is queued, running, finished with an answer, or threw.
 */
public interface RunStore {

    /** Opens a conversation for the agent and returns its id. */
    String newConversation(String agent);

    /** Opens a turn (a QUEUED turn row) in the conversation and returns its id: the job id. */
    String newTurn(String conversationId, String question);

    /** Adds one row to the end of the turn. Rows are never rewritten. */
    void append(String turnId, Span span);

    /** Every row of the turn, in span order. */
    List<Span> load(String turnId);

    boolean exists(String turnId);

    /** The turn row: agent, question, answer, status, who claimed it and how often, and times. */
    TurnRow turn(String turnId);

    /** The conversation row; an unknown id is an IllegalArgumentException. */
    ConversationRow conversation(String conversationId);

    /** The turns of a conversation, oldest first. */
    List<TurnRow> turns(String conversationId);

    /** QUEUED until a worker takes it, RUNNING while the loop runs, FAILED if it threw. */
    void setStatus(String turnId, TurnRow.Status status);

    /** The loop ended: DONE, and the answer on the row. */
    void done(String turnId, String answer);

    /** A worker takes the turn: RUNNING, the worker's name on the row, attempts + 1. Returns the new attempt number. */
    int claim(String turnId, String claimedBy);

    /** The rows from that span on: what replay prints and what an SSE client missed. */
    default List<Span> load(String turnId, int fromSpan) {
        List<Span> tail = new ArrayList<>();
        for (Span span : load(turnId)) {
            if (span.span() >= fromSpan) {
                tail.add(span);
            }
        }
        return tail;
    }

    /** A fresh id: the prefix plus 4 hex chars, short enough to type back. Stores retry on a clash. */
    static String newId(String prefix) {
        return String.format("%s-%04x", prefix, ThreadLocalRandom.current().nextInt(0x10000));
    }
}
