package harness.tools;

import harness.core.Span;

/**
 * What a tool run gave back. A failure is still a result with a status, so the loop always
 * ends. ms is how long the tool ran, or the timeout it was allowed; 0 when it never ran.
 */
public record ToolResult(String output, Span.Status status, long ms) {
}
