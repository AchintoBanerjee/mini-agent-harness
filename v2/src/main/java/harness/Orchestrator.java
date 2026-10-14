package harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owns the loop. Each question becomes a Turn: ask the model, run the tool it names,
 * record both as spans, repeat until the model says Done, then summarize.
 */
public class Orchestrator {

    public static final int MAX_PASSES = 5;

    private final Conversation conversation;
    private final List<Tool> tools = new ArrayList<>();
    private final LLMProxy llm;
    private final ToolCaller toolCaller;
    private final Summarizer summarizer;

    public Orchestrator(Agent agent, ToolRegistry registry, LLMProxy llm,
                        ToolCaller toolCaller, Summarizer summarizer) {
        for (String name : agent.toolNames()) {
            tools.add(registry.require(name));
        }
        this.conversation = new Conversation(UUID.randomUUID().toString(), agent);
        this.llm = llm;
        this.toolCaller = toolCaller;
        this.summarizer = summarizer;
    }

    public String run(String question) {
        Turn turn = new Turn(question);
        conversation.turns().add(turn);

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Context context = new Context(question, List.copyOf(turn.spans()));
            ModelReply reply = llm.next(context, tools);
            turn.spans().add(new Span(Span.Kind.LLM, "pass " + (pass + 1), reply.toString()));

            if (!(reply instanceof ToolCall call)) {
                break;
            }
            ToolResult result = toolCaller.call(call);
            turn.spans().add(new Span(Span.Kind.TOOL, call.toString(), result.output()));
        }

        String answer = summarizer.summarize(turn);
        turn.setAnswer(answer);
        return answer;
    }

    public Conversation conversation() {
        return conversation;
    }
}
