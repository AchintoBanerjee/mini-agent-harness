package harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stub model. If a tool already ran in this turn it says Done. Otherwise the first
 * tool whose matching condition fits the question is called. No match means Done.
 */
public class RuleBasedLLM implements LLMProxy {

    @Override
    public ModelReply next(Context context, List<Tool> tools) {
        for (Span span : context.spans()) {
            if (span.kind() == Span.Kind.TOOL) {
                return new Done();
            }
        }
        for (Tool tool : tools) {
            Optional<String> id = tool.matching().match(context.question());
            if (id.isPresent()) {
                return new ToolCall(tool.name(), Map.of(tool.inputField(), id.get()));
            }
        }
        return new Done();
    }
}
