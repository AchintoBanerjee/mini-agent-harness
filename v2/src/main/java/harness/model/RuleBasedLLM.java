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
import java.util.regex.Matcher;

/**
 * Stub model. Pass 1 reads the question: every tool whose matching condition fits it is
 * called, one as a ToolCall, several as one ToolCalls batch in the agent's tool order. An
 * agent tool gets the whole question as its goal, where a tool gets the id the question
 * mentions. Later passes read the results: the chain is what a model does when a result
 * names something it can look up. Every OK call row of the turn is scanned for the ids each
 * id-taking tool knows, and each (tool, id) that has no call row yet is called, in the order
 * found. A row that is DEDUPED, ERROR, TIMEOUT or DENIED is not read: its text is a
 * reference or a message. Done when nothing new is named. Its final reply is the relay rule
 * (RelaySummarizer), handed out word by word.
 */
public class RuleBasedLLM implements LLMProxy {

    /** --slow: every piece of the answer waits this long before it goes out, so the stream can be watched (latency simulated). */
    public static volatile long SLOW_TOKEN_MS = 0;

    private final RelaySummarizer relay = new RelaySummarizer();

    @Override
    public ModelReply next(Context context, List<Tool> tools) {
        boolean callRowExists = context.spans().stream().anyMatch(span -> span.kind().isCall());
        List<ToolCall> calls = callRowExists ? chained(context.spans(), tools) : fromQuestion(context.question(), tools);
        if (calls.isEmpty()) {
            return new Done();
        }
        return calls.size() == 1 ? calls.get(0) : new ToolCalls(calls);
    }

    /** Pass 1: every tool whose matching condition fits the question. */
    private static List<ToolCall> fromQuestion(String question, List<Tool> tools) {
        List<ToolCall> calls = new ArrayList<>();
        for (Tool tool : tools) {
            Optional<String> id = tool.matching().match(question);
            if (id.isPresent()) {
                String value = tool.isAgent() ? question : id.get();
                Map<String, String> input = tool.inputField() == null ? Map.of() : Map.of(tool.inputField(), value);
                calls.add(new ToolCall(tool.name(), input));
            }
        }
        return calls;
    }

    /** Pass 2 and later: every (tool, id) an OK result names that no call row of the turn has yet, each once. */
    private static List<ToolCall> chained(List<Span> spans, List<Tool> tools) {
        List<ToolCall> calls = new ArrayList<>();
        for (Span row : spans) {
            if (!row.kind().isCall() || row.status() != Span.Status.OK) {
                continue;
            }
            for (Tool tool : tools) {
                if (tool.isAgent() || tool.inputField() == null || tool.matching().idPattern() == null) {
                    continue;
                }
                Matcher ids = tool.matching().idPattern().matcher(row.response());
                while (ids.find()) {
                    ToolCall call = new ToolCall(tool.name(), Map.of(tool.inputField(), ids.group().toUpperCase()));
                    if (!calls.contains(call) && !alreadyCalled(spans, call)) {
                        calls.add(call);
                    }
                }
            }
        }
        return calls;
    }

    /** True when a call row of the turn already ran this tool on this id, whatever it ended as. */
    private static boolean alreadyCalled(List<Span> spans, ToolCall call) {
        for (Span row : spans) {
            if (row.kind().isCall() && call.toolName().equals(row.attrs().get("tool"))
                    && row.attrs().entrySet().containsAll(call.input().entrySet())) {
                return true;
            }
        }
        return false;
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
