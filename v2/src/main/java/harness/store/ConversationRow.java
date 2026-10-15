package harness.store;

/** The conversation row: one user talking to one agent; its turns point at it. */
public record ConversationRow(String id, String agent, long createdAt) {
}
