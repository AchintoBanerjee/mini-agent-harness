package harness.store;

/**
 * The job row: one per run. Opened QUEUED by submit, RUNNING once a worker claims it (the
 * row then says who, and how many times it was claimed), DONE when the loop ends, FAILED
 * when it threw, DEAD when workers died on it too many times. A run whose worker died
 * stays RUNNING, which is how the next worker can tell it apart. The conversation id is
 * the job id today: one question per job; a follow-up turn would reuse it.
 */
public record Job(String id, String conversationId, String agent, String question,
                  Status status, String claimedBy, int attempts, long createdAt, long updatedAt) {

    public enum Status {
        QUEUED, RUNNING, DONE, FAILED, DEAD;

        /** Nothing more will happen to the job: the stream can close. */
        public boolean ended() {
            return this == DONE || this == FAILED || this == DEAD;
        }
    }

    public Job with(Status status, long updatedAt) {
        return new Job(id, conversationId, agent, question, status, claimedBy, attempts, createdAt, updatedAt);
    }

    /** A worker took the job: RUNNING, its name on the row, one more attempt. */
    public Job claimedBy(String worker, long updatedAt) {
        return new Job(id, conversationId, agent, question, Status.RUNNING, worker, attempts + 1, createdAt, updatedAt);
    }
}
