package harness.tools;

import harness.core.Span;
import harness.model.ToolCall;
import java.util.Optional;

/** Runs one tool call and returns its output. Never throws: failures become ERROR results. */
public class ToolCaller {

    private final ToolRegistry registry;

    public ToolCaller(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolResult call(ToolCall call) {
        Optional<Tool> tool = registry.find(call.toolName());
        if (tool.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.toolName(), Span.Status.ERROR);
        }
        try {
            return new ToolResult(tool.get().run(call.input()), Span.Status.OK);
        } catch (RuntimeException e) {
            return new ToolResult("Tool failed: " + e.getMessage(), Span.Status.ERROR);
        }
    }
}
