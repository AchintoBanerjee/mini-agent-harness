package harness;

/** What a tool run gave back. An error is still a result, so the loop always ends. */
public record ToolResult(String output, boolean error) {
}
