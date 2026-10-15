package harness.model;

import java.util.List;

/** The model asks for several tools at once. They run together; their rows land in this order. */
public record ToolCalls(List<ToolCall> calls) implements ModelReply {
}
