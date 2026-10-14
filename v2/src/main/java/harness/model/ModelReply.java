package harness.model;

/** What the model answers with: either a tool to call, or Done. */
public sealed interface ModelReply permits ToolCall, Done {
}
