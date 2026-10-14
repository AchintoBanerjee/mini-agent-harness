package harness.model;

/** The model says the work is finished. The Orchestrator closes the loop on this. */
public record Done() implements ModelReply {
}
