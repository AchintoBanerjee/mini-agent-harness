package harness.core;

import harness.agent.Agent;
import harness.model.LLMProxy;
import harness.model.ModelReply;
import harness.model.ToolCall;
import harness.store.InMemoryRunStore;
import harness.store.RunStore;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolRegistry;
import harness.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the loop. Each question becomes a Turn: ask the model, run the tool it names,
 * record both as rows, repeat until the model says Done, then summarize. The agent's
 * maxPasses caps the loop; hitting the cap leaves a NOTE row instead of a silent stop.
 * Every row is written to the store before the loop acts on it: the LLM row before the
 * tool runs, the TOOL row before the model is asked again.
 */
public class Orchestrator {

    private final Agent agent;
    private final List<Tool> tools = new ArrayList<>();
    private final LLMProxy llm;
    private final ToolCaller toolCaller;
    private final Summarizer summarizer;
    private final RunStore store;
    private final RunListener listener;
    private Conversation conversation;
    private int steps = 0;

    /** In-memory store and no listener: for tests and embedding. */
    public Orchestrator(Agent agent, ToolRegistry registry, LLMProxy llm,
                        ToolCaller toolCaller, Summarizer summarizer) {
        this(agent, registry, llm, toolCaller, summarizer, new InMemoryRunStore(), span -> { });
    }

    public Orchestrator(Agent agent, ToolRegistry registry, LLMProxy llm, ToolCaller toolCaller,
                        Summarizer summarizer, RunStore store, RunListener listener) {
        for (String name : agent.toolNames()) {
            tools.add(registry.require(name));
        }
        this.agent = agent;
        this.llm = llm;
        this.toolCaller = toolCaller;
        this.summarizer = summarizer;
        this.store = store;
        this.listener = listener;
    }

    public String run(String question) {
        if (conversation == null) {
            conversation = new Conversation(store.newRun(agent.name(), question), agent);
        }
        Turn turn = new Turn(question);
        conversation.turns().add(turn);
        int turnNo = conversation.turns().size();
        int maxPasses = agent.maxPasses();

        for (int pass = 1; pass <= maxPasses; pass++) {
            long started = System.nanoTime();
            ModelReply reply = llm.next(new Context(question, List.copyOf(turn.spans())), tools);
            record(turn, turnNo, pass, Span.Kind.LLM, "pass " + pass, reply.toString(),
                    Span.Status.OK, millisSince(started), Map.of());

            if (!(reply instanceof ToolCall call)) {
                return finish(turn);
            }

            started = System.nanoTime();
            ToolResult result = toolCaller.call(call);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("tool", call.toolName());
            attrs.putAll(call.input());
            record(turn, turnNo, pass, Span.Kind.TOOL, call.toolName() + " " + call.input(),
                    result.output(), result.status(), millisSince(started), attrs);
        }

        record(turn, turnNo, maxPasses, Span.Kind.NOTE, "cut short at maxPasses=" + maxPasses, "",
                Span.Status.CUT_SHORT, 0, Map.of());
        return finish(turn);
    }

    /** The conversation so far: its id is the run id. Null until the first question opens the run. */
    public Conversation conversation() {
        return conversation;
    }

    /** Write, then act: the row is in the store and announced before the loop takes its next step. */
    private void record(Turn turn, int turnNo, int pass, Span.Kind kind, String request,
                        String response, Span.Status status, long ms, Map<String, String> attrs) {
        steps++;
        Span span = new Span(conversation.id(), steps, turnNo, pass, kind, request, response, status, ms, attrs);
        turn.spans().add(span);
        store.append(conversation.id(), span);
        listener.on(span);
    }

    private String finish(Turn turn) {
        String answer = summarizer.summarize(turn);
        turn.setAnswer(answer);
        return answer;
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
