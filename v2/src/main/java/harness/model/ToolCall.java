package harness.model;

import java.util.List;
import java.util.Map;

/** The model asks for one tool to run, with its input (field name to value). */
public record ToolCall(String toolName, Map<String, String> input) implements ModelReply {

    @Override
    public List<ToolCall> calls() {
        return List.of(this);
    }
}
