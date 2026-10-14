package harness;

/** Runs one tool call and returns its output. Never throws: failures become error results. */
public class ToolCaller {

    private final ToolRegistry registry;

    public ToolCaller(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolResult call(ToolCall call) {
        var tool = registry.find(call.toolName());
        if (tool.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.toolName(), true);
        }
        try {
            return new ToolResult(tool.get().run(call.input()), false);
        } catch (RuntimeException e) {
            return new ToolResult("Tool failed: " + e.getMessage(), true);
        }
    }
}
