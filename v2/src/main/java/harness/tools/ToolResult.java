package harness.tools;

import harness.core.Span;

/** What a tool run gave back. A failure is still a result with a status, so the loop always ends. */
public record ToolResult(String output, Span.Status status) {
}
