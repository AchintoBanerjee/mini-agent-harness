package harness.store;

/**
 * The turn row: one per turn, and its id is the job id. Opened QUEUED by submit, RUNNING
 * once a worker claims it (the row then says who, and how many times it was claimed), DONE
 * with the answer when the loop ends, FAILED when it threw. A turn whose worker died stays
 * RUNNING, which is how the next worker can tell it apart. The agent comes from the
 * conversation the turn belongs to.
 */
public record TurnRow(String id, String conversationId, String agent, String question, String answer,
                      Status status, String claimedBy, int attempts, long createdAt, long updatedAt) {

    public enum Status {
        QUEUED, RUNNING, DONE, FAILED;

        /** Nothing more will happen to the turn: the stream can close. */
        public boolean ended() {
            return this == DONE || this == FAILED;
        }
    }

    public TurnRow with(Status status, String answer, long updatedAt) {
        return new TurnRow(id, conversationId, agent, question, answer, status, claimedBy, attempts, createdAt, updatedAt);
    }

    /** A worker took the turn: RUNNING, its name on the row, one more attempt. */
    public TurnRow claimedBy(String worker, long updatedAt) {
        return new TurnRow(id, conversationId, agent, question, answer, Status.RUNNING, worker, attempts + 1, createdAt, updatedAt);
    }
}
