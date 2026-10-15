package harness.model;

import harness.core.Context;
import harness.core.Span;
import harness.tools.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stub model. If a tool already ran in this turn it says Done. Otherwise every tool whose
 * matching condition fits the question is called: one as a ToolCall, several as one
 * ToolCalls batch in the agent's tool order. No match means Done.
 */
public class RuleBasedLLM implements LLMProxy {

    @Override
    public ModelReply next(Context context, List<Tool> tools) {
        for (Span span : context.spans()) {
            if (span.kind() == Span.Kind.TOOL) {
                return new Done();
            }
        }
        List<ToolCall> calls = new ArrayList<>();
        for (Tool tool : tools) {
            Optional<String> id = tool.matching().match(context.question());
            if (id.isPresent()) {
                Map<String, String> input = tool.inputField() == null ? Map.of() : Map.of(tool.inputField(), id.get());
                calls.add(new ToolCall(tool.name(), input));
            }
        }
        if (calls.isEmpty()) {
            return new Done();
        }
        return calls.size() == 1 ? calls.get(0) : new ToolCalls(calls);
    }
}
