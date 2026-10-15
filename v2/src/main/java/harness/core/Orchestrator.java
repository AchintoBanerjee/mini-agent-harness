package harness.core;

import harness.agent.Agent;
import harness.model.LLMProxy;
import harness.model.ModelReply;
import harness.model.ToolCall;
import harness.store.InMemoryRunStore;
import harness.store.RunStore;
import harness.store.TurnRow;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolRegistry;
import harness.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Owns the loop. Each question is a Turn (its id the job id): ask the model, run the tool
 * or tools it names (several in one reply run together, rows in request order), record
 * each as a span, repeat until the model says Done, then summarize. The agent's maxPasses
 * caps the loop; hitting the cap leaves a NOTE row instead of a silent stop. Every row is
 * written to the store before the loop acts on it: the LLM row before the tools run, the
 * TOOL rows before the model is asked again. The LLM row's attrs carry the decided calls,
 * so a turn that died between two rows resumes from the store without asking the model
 * again for that decision. Several turns form one conversation: the model sees the earlier
 * turns as Q/A lines, and a call whose tool and input already ran OK anywhere in the
 * conversation is not run again but recorded as DEDUPED, pointing at the span it repeats.
 * The turn row follows the loop: RUNNING while it runs, DONE with the answer when it ends,
 * FAILED if it threw.
 */
public class Orchestrator {

    /** Attrs keys. The question is on every LLM row; tool (comma-joined when several) + input fields name the decided calls. */
    static final String QUESTION = "question";
    static final String TOOL = "tool";
    static final String KEY = "key";
    static final String REF = "ref";

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

    /** A question in this process: the first one opens the conversation, each one is a turn of its own. */
    public String run(String question) {
        if (conversation == null) {
            conversation = new Conversation(store.newConversation(agent.name()), agent);
        }
        Turn turn = conversation.turn(store.newTurn(conversation.id(), question), question);
        return tracked(turn, () -> loop(turn, 1));
    }

    /**
     * A turn the store already holds and nobody has run: POST /runs or submit wrote its row
     * and answered with its id at once; a worker calls this. The conversation's earlier
     * turns are loaded first, so the model sees them.
     */
    public String start(String turnId) {
        Turn turn = open(turnId);
        if (!turn.spans().isEmpty()) {
            throw new IllegalStateException("turn " + turnId + " already has rows: resume it");
        }
        return tracked(turn, () -> loop(turn, 1));
    }

    /**
     * Picks a turn up where its rows end. If the last decision has calls with no TOOL row
     * after them, those run first, in order (the model is not asked again for them), then
     * the loop continues at the next pass. A turn whose last row already closed it (Done, or
     * the maxPasses cut) gets its answer back unchanged.
     */
    public String resume(String turnId) {
        Turn turn = open(turnId);
        List<Span> rows = turn.spans();
        if (rows.isEmpty()) {
            throw new IllegalStateException("turn " + turnId + " has no rows to resume from");
        }
        Span last = rows.getLast();
        boolean closed = last.status() == Span.Status.CUT_SHORT
                || (last.kind() == Span.Kind.LLM && !last.attrs().containsKey(TOOL));
        if (closed) {
            return tracked(turn, () -> finish(turn));
        }
        return tracked(turn, () -> continueFrom(turn, last));
    }

    /** The conversation so far: its turns' ids are the job ids. Null until the first question opens it. */
    public Conversation conversation() {
        return conversation;
    }

    /** Loads the turn's conversation from the store, up to the turn itself, and continues the span numbering. */
    private Turn open(String turnId) {
        if (conversation != null) {
            throw new IllegalStateException("this orchestrator already holds conversation " + conversation.id());
        }
        conversation = Conversation.load(store, store.turn(turnId).conversationId(), agent, turnId);
        for (Turn earlier : conversation.turns()) {
            for (Span row : earlier.spans()) {
                steps = Math.max(steps, row.span());
            }
        }
        return conversation.turns().getLast();
    }

    /** The turn row follows the work: RUNNING while it runs, DONE with the answer at the end, FAILED if it threw. */
    private String tracked(Turn turn, Supplier<String> work) {
        store.setStatus(turn.id(), TurnRow.Status.RUNNING);
        String answer;
        try {
            answer = work.get();
        } catch (RuntimeException | Error thrown) {
            store.setStatus(turn.id(), TurnRow.Status.FAILED);
            throw thrown;
        }
        store.done(turn.id(), answer);
        return answer;
    }

    /** Resume's work: note where it picks up, run the decided calls that have no row yet, carry on. */
    private String continueFrom(Turn turn, Span last) {
        record(turn, last.pass(), Span.Kind.NOTE, "resumed from step " + last.span(), "",
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
            runCalls(turn, decided.pass(), calls.subList(toolRowsAfter, calls.size()), decided.span());
        }
        return loop(turn, decided.pass() + 1);
    }

    /** Ask, run, repeat from the given pass until Done or the cap. */
    private String loop(Turn turn, int fromPass) {
        int maxPasses = agent.maxPasses();
        for (int pass = fromPass; pass <= maxPasses; pass++) {
            long started = System.nanoTime();
            ModelReply reply = llm.next(context(turn), tools);
            List<ToolCall> calls = reply.calls();
            record(turn, pass, Span.Kind.LLM, "pass " + pass, reply.toString(),
                    Span.Status.OK, Span.millisSince(started), decided(turn.question(), calls));

            if (calls.isEmpty()) {
                return finish(turn);
            }
            runCalls(turn, pass, calls, steps);
        }

        record(turn, maxPasses, Span.Kind.NOTE, "cut short at maxPasses=" + maxPasses, "",
                Span.Status.CUT_SHORT, 0, Map.of());
        return finish(turn);
    }

    /** What the model sees: this turn's rows so far, and each earlier turn as one "Q: ... -> A: ..." line. */
    private Context context(Turn turn) {
        List<String> history = new ArrayList<>();
        for (Turn earlier : conversation.turns()) {
            if (earlier == turn) {
                break;
            }
            history.add("Q: " + earlier.question() + " -> A: " + (earlier.answer() == null ? "(no answer)" : earlier.answer()));
        }
        return new Context(turn.question(), List.copyOf(turn.spans()), history);
    }

    /**
     * Runs the calls (together when several) and records one TOOL row each, in request
     * order, with that call's own time; a batch also leaves a NOTE row: wall against work.
     * A call whose tool and input already ran OK in this conversation does not run: its
     * row is DEDUPED and points at the span it repeats.
     */
    private void runCalls(Turn turn, int pass, List<ToolCall> calls, int decidingStep) {
        List<Span> repeats = new ArrayList<>();
        List<ToolCall> fresh = new ArrayList<>();
        for (ToolCall call : calls) {
            Span earlier = earlierOk(call);
            repeats.add(earlier);
            if (earlier == null) {
                fresh.add(call);
            }
        }
        long started = System.nanoTime();
        List<ToolResult> results = fresh.size() == 1 ? List.of(toolCaller.call(fresh.get(0))) : toolCaller.callAll(fresh);
        long wall = Span.millisSince(started);
        long work = 0;
        int next = 0;
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(TOOL, call.toolName());
            attrs.putAll(call.input());
            attrs.put(KEY, turn.id() + "/" + decidingStep);
            ToolResult result;
            if (repeats.get(i) != null) {
                attrs.put(REF, String.valueOf(repeats.get(i).span()));
                result = new ToolResult("unchanged, see step " + repeats.get(i).span(), Span.Status.DEDUPED, 0);
            } else {
                result = results.get(next++);
                work += result.ms();
            }
            record(turn, pass, Span.Kind.TOOL, call.toolName() + " " + call.input(),
                    result.output(), result.status(), result.ms(), attrs);
        }
        if (fresh.size() > 1) {
            record(turn, pass, Span.Kind.NOTE, "ran " + fresh.size() + " tools together: wall "
                    + wall + " ms, work " + work + " ms", "", Span.Status.OK, wall, Map.of());
        }
    }

    /** The first OK TOOL row of this conversation that ran the same tool on the same input, or null. */
    private Span earlierOk(ToolCall call) {
        for (Turn earlier : conversation.turns()) {
            for (Span row : earlier.spans()) {
                if (row.kind() == Span.Kind.TOOL && row.status() == Span.Status.OK
                        && call.toolName().equals(row.attrs().get(TOOL))
                        && row.attrs().entrySet().containsAll(call.input().entrySet())) {
                    return row;
                }
            }
        }
        return null;
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
    private void record(Turn turn, int pass, Span.Kind kind, String request,
                        String response, Span.Status status, long ms, Map<String, String> attrs) {
        steps++;
        Span span = new Span(turn.id(), steps, pass, kind, request, response, status, ms, attrs);
        turn.spans().add(span);
        store.append(turn.id(), span);
        listener.on(span);
    }

    private String finish(Turn turn) {
        String answer = summarizer.summarize(turn);
        turn.setAnswer(answer);
        return answer;
    }
}
