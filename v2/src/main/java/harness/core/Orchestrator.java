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
 * Owns the loop. Each question becomes a Turn: ask the model, run the tool or tools it
 * names (several in one reply run together, rows in request order), record each as a row,
 * repeat until the model says Done, then summarize. The agent's maxPasses caps the loop;
 * hitting the cap leaves a NOTE row instead of a silent stop. Every row is written to the
 * store before the loop acts on it: the LLM row before the tools run, the TOOL rows before
 * the model is asked again. The LLM row's attrs carry the decided calls, so a run that died
 * between two rows resumes from the log without asking the model again for that decision.
 */
public class Orchestrator {

    /** Attrs keys. The question is on every LLM row; tool (comma-joined when several) + input fields name the decided calls. */
    static final String QUESTION = "question";
    static final String TOOL = "tool";
    static final String KEY = "key";

    private final Agent agent;
    private final ToolRegistry registry;
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
        this.registry = registry;
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
        return loop(turn, conversation.turns().size(), 1);
    }

    /**
     * Picks a run up where its log ends. Rebuilds the turns from the rows; if the last
     * decision has calls with no TOOL row after them, those run first, in order (the model
     * is not asked again for them), then the loop continues at the next pass. A run whose
     * last row already closed it (Done, or the maxPasses cut) gets its answer back unchanged.
     */
    public String resume(String runId) {
        if (conversation != null) {
            throw new IllegalStateException("this orchestrator already holds run " + conversation.id());
        }
        List<Span> rows = store.load(runId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("run " + runId + " has no rows to resume from");
        }
        conversation = new Conversation(runId, agent);
        Turn turn = null;
        for (Span row : rows) {
            if (row.turn() != conversation.turns().size()) {
                String question = row.attrs().get(QUESTION);
                if (question == null) {
                    throw new IllegalStateException("row " + row.step() + " of " + runId + " carries no question: this log predates resume");
                }
                turn = new Turn(question);
                conversation.turns().add(turn);
            }
            turn.spans().add(row);
        }
        for (int i = 0; i < conversation.turns().size() - 1; i++) {
            Turn earlier = conversation.turns().get(i);
            earlier.setAnswer(summarizer.summarize(earlier));
        }
        steps = rows.getLast().step();
        int turnNo = conversation.turns().size();

        Span last = rows.getLast();
        boolean closed = last.status() == Span.Status.CUT_SHORT
                || (last.kind() == Span.Kind.LLM && !last.attrs().containsKey(TOOL));
        if (closed) {
            return finish(turn);
        }
        record(turn, turnNo, last.pass(), Span.Kind.NOTE, "resumed from step " + last.step(), "",
                Span.Status.OK, 0, Map.of());

        Span decided = null;
        int toolRowsAfter = 0;
        for (Span row : turn.spans()) {
            if (row.kind() == Span.Kind.LLM) {
                decided = row;
                toolRowsAfter = 0;
            } else if (row.kind() == Span.Kind.TOOL) {
                toolRowsAfter++;
            }
        }
        List<ToolCall> calls = decided.attrs().containsKey(TOOL) ? decidedCalls(decided) : List.of();
        if (toolRowsAfter < calls.size()) {
            runCalls(turn, turnNo, decided.pass(), calls.subList(toolRowsAfter, calls.size()), decided.step());
        }
        return loop(turn, turnNo, decided.pass() + 1);
    }

    /** The conversation so far: its id is the run id. Null until the first question opens the run. */
    public Conversation conversation() {
        return conversation;
    }

    /** Ask, run, repeat from the given pass until Done or the cap. */
    private String loop(Turn turn, int turnNo, int fromPass) {
        int maxPasses = agent.maxPasses();
        for (int pass = fromPass; pass <= maxPasses; pass++) {
            long started = System.nanoTime();
            ModelReply reply = llm.next(new Context(turn.question(), List.copyOf(turn.spans())), tools);
            List<ToolCall> calls = reply.calls();
            record(turn, turnNo, pass, Span.Kind.LLM, "pass " + pass, reply.toString(),
                    Span.Status.OK, Span.millisSince(started), decided(turn.question(), calls));

            if (calls.isEmpty()) {
                return finish(turn);
            }
            runCalls(turn, turnNo, pass, calls, steps);
        }

        record(turn, turnNo, maxPasses, Span.Kind.NOTE, "cut short at maxPasses=" + maxPasses, "",
                Span.Status.CUT_SHORT, 0, Map.of());
        return finish(turn);
    }

    /**
     * Runs the calls (together when several) and records one TOOL row each, in request
     * order, with that call's own time; a batch also leaves a NOTE row: wall against work.
     */
    private void runCalls(Turn turn, int turnNo, int pass, List<ToolCall> calls, int decidingStep) {
        long started = System.nanoTime();
        List<ToolResult> results = calls.size() == 1 ? List.of(toolCaller.call(calls.get(0))) : toolCaller.callAll(calls);
        long wall = Span.millisSince(started);
        long work = 0;
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results.get(i);
            work += result.ms();
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(TOOL, call.toolName());
            attrs.putAll(call.input());
            attrs.put(KEY, conversation.id() + "/" + decidingStep);
            record(turn, turnNo, pass, Span.Kind.TOOL, call.toolName() + " " + call.input(),
                    result.output(), result.status(), result.ms(), attrs);
        }
        if (calls.size() > 1) {
            record(turn, turnNo, pass, Span.Kind.NOTE, "ran " + calls.size() + " tools together: wall "
                    + wall + " ms, work " + work + " ms", "", Span.Status.OK, wall, Map.of());
        }
    }

    /** What the LLM row remembers: the question, and the calls if the model asked for any. */
    private static Map<String, String> decided(String question, List<ToolCall> calls) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(QUESTION, question);
        for (ToolCall call : calls) {
            attrs.merge(TOOL, call.toolName(), (names, name) -> names + "," + name);
            attrs.putAll(call.input());
        }
        return attrs;
    }

    /** The calls an LLM row decided, read back from its attrs: each tool takes its own input field. */
    private List<ToolCall> decidedCalls(Span llmRow) {
        List<ToolCall> calls = new ArrayList<>();
        for (String name : llmRow.attrs().get(TOOL).split(",")) {
            String field = registry.find(name).map(Tool::inputField).orElse(null);
            String value = field == null ? null : llmRow.attrs().get(field);
            calls.add(new ToolCall(name, value == null ? Map.of() : Map.of(field, value)));
        }
        return calls;
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
}
