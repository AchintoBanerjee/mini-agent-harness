package harness.model;

import harness.core.Context;
import harness.core.RelaySummarizer;
import harness.core.Span;
import harness.core.Turn;
import harness.tools.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Stub model. If a tool (or an agent run as one) already ran in this turn it says Done.
 * Otherwise every tool whose matching condition fits the question is called: one as a
 * ToolCall, several as one ToolCalls batch in the agent's tool order. An agent tool gets the
 * whole question as its goal, where a tool gets the id the question mentions. No match
 * means Done. Its final reply is the relay rule (RelaySummarizer), handed out word by word.
 */
public class RuleBasedLLM implements LLMProxy {

    /** --slow: every piece of the answer waits this long before it goes out, so the stream can be watched (latency simulated). */
    public static volatile long SLOW_TOKEN_MS = 0;

    private final RelaySummarizer relay = new RelaySummarizer();

    @Override
    public ModelReply next(Context context, List<Tool> tools) {
        for (Span span : context.spans()) {
            if (span.kind().isCall()) {
                return new Done();
            }
        }
        List<ToolCall> calls = new ArrayList<>();
        for (Tool tool : tools) {
            Optional<String> id = tool.matching().match(context.question());
            if (id.isPresent()) {
                String value = tool.isAgent() ? context.question() : id.get();
                Map<String, String> input = tool.inputField() == null ? Map.of() : Map.of(tool.inputField(), value);
                calls.add(new ToolCall(tool.name(), input));
            }
        }
        if (calls.isEmpty()) {
            return new Done();
        }
        return calls.size() == 1 ? calls.get(0) : new ToolCalls(calls);
    }

    /** The relay rule's text, one word at a time: each piece keeps its trailing space, so the pieces concatenate to the text. */
    @Override
    public String answer(Turn turn, Consumer<String> out) {
        String text = relay.summarize(turn);
        String[] words = text.split(" ");
        for (int i = 0; i < words.length; i++) {
            pace();
            out.accept(i < words.length - 1 ? words[i] + " " : words[i]);
        }
        return text;
    }

    private static void pace() {
        if (SLOW_TOKEN_MS <= 0) {
            return;
        }
        try {
            Thread.sleep(SLOW_TOKEN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
