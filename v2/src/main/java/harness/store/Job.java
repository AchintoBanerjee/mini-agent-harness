package harness.store;

/**
 * The job row: one per run. Opened RUNNING, set DONE when the loop ends, FAILED when it
 * threw. A run that died mid-loop stays RUNNING, which is how it can be told apart. The
 * conversation id is the job id today: one question per job; a follow-up turn would reuse it.
 */
public record Job(String id, String conversationId, String agent, String question,
                  Status status, long createdAt, long updatedAt) {

    public enum Status { RUNNING, DONE, FAILED }

    public Job with(Status status, long updatedAt) {
        return new Job(id, conversationId, agent, question, status, createdAt, updatedAt);
    }
}
