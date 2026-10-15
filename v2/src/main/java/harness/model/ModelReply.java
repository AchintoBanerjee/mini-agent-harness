package harness.model;

import java.util.List;

/** What the model answers with: one tool to call, several to run together, or Done. */
public sealed interface ModelReply permits ToolCall, ToolCalls, Done {

    /** The calls this reply asks for, in order: none for Done. */
    default List<ToolCall> calls() {
        return List.of();
    }
}
