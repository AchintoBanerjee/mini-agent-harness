package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.model.LLMProxy;
import harness.model.ModelReply;
import harness.model.RuleBasedLLM;
import harness.model.ToolCall;
import harness.store.InMemoryRunStore;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import harness.tools.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Interrupt and resume. A crash is an exception thrown from inside the loop: before the
 * tool runs (the LLM row is already stored) or after it ran (its TOOL row is not). A
 * second orchestrator over the same in-memory store resumes the run from its rows.
 */
class ResumeTest {

    private static final String QUESTION = "What is the status of order ORD-1?";
    private static final String ANSWER = "Order ORD-1: SHIPPED, shipment SHP-1";

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final Agent agent = AgentLoader.load(Path.of("agents", "support.json"));
    private final InMemoryRunStore store = new InMemoryRunStore();
    private final CountingLLM llm = new CountingLLM();

    @BeforeEach
    void resetHandlerCounts() {
        ToolLoader.HANDLER_CALLS.clear();
    }

    @Test
    void crashAfterTheLlmRowIsPersistedResumesWithoutAskingTheModelAgain() {
        Orchestrator first = orchestrator(new CrashingToolCaller(registry, false));
        assertThrows(Crash.class, () -> first.run(QUESTION));
        String runId = first.conversation().turns().getLast().id();
        assertEquals(List.of(Span.Kind.LLM), kinds(store.load(runId)));
        assertEquals(0, handlerCalls("order_status"), "the tool never ran");

        assertEquals(ANSWER, orchestrator(new ToolCaller(registry)).resume(runId));

        List<Span> rows = store.load(runId);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.NOTE, Span.Kind.TOOL, Span.Kind.LLM), kinds(rows));
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(Span::span).toList());
        assertEquals("resumed from step 1", rows.get(1).request());
        assertEquals(runId + "/1", rows.get(2).attrs().get("key"), "the key names the row that decided the call");
        assertEquals(1, handlerCalls("order_status"));
        assertEquals(1, llm.decisionAsks, "the model was asked once for the decided step");
        assertEquals(2, llm.asks, "once to decide, once to say Done");
    }

    @Test
    void crashBeforeTheToolRowIsPersistedRunsThePendingCallAgain() {
        Orchestrator first = orchestrator(new CrashingToolCaller(registry, true));
        assertThrows(Crash.class, () -> first.run(QUESTION));
        String runId = first.conversation().turns().getLast().id();
        assertEquals(List.of(Span.Kind.LLM), kinds(store.load(runId)));
        assertEquals(1, handlerCalls("order_status"), "the tool did its work but its row was never written");

        assertEquals(ANSWER, orchestrator(new ToolCaller(registry)).resume(runId));

        List<Span> rows = store.load(runId);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.NOTE, Span.Kind.TOOL, Span.Kind.LLM), kinds(rows));
        assertEquals(2, handlerCalls("order_status"), "at-least-once: the same decision ran twice");
        assertEquals(runId + "/1", rows.get(2).attrs().get("key"), "both runs of the call carry the same key");
        assertEquals(1, llm.decisionAsks);
    }

    @Test
    void crashAfterTheToolRowIsPersistedResumesIntoTheNextPass() {
        RunListener dieAfterRow2 = span -> {
            if (span.span() == 2) {
                throw new Crash();
            }
        };
        Orchestrator first = new Orchestrator(agent, registry, llm, new ToolCaller(registry), store, dieAfterRow2);
        assertThrows(Crash.class, () -> first.run(QUESTION));
        String runId = first.conversation().turns().getLast().id();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL), kinds(store.load(runId)));

        assertEquals(ANSWER, orchestrator(new ToolCaller(registry)).resume(runId));

        List<Span> rows = store.load(runId);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.NOTE, Span.Kind.LLM), kinds(rows));
        assertEquals("resumed from step 2", rows.get(2).request());
        assertEquals(2, rows.get(3).pass(), "the loop carries on at the next pass");
        assertEquals(1, handlerCalls("order_status"), "nothing was pending, nothing ran again");
    }

    @Test
    void resumeRebuildsEarlierTurnsAndFinishesTheInterruptedOne() {
        RunListener dieAfterRow4 = span -> {
            if (span.span() == 4) {
                throw new Crash();
            }
        };
        Orchestrator first = new Orchestrator(agent, registry, llm, new ToolCaller(registry), store, dieAfterRow4);
        assertEquals(ANSWER, first.run(QUESTION));
        assertThrows(Crash.class, () -> first.run("Is product PRD-1 available?"));
        String runId = first.conversation().turns().getLast().id();

        Orchestrator second = orchestrator(new ToolCaller(registry));
        assertEquals("Product PRD-1: 42 units in stock", second.resume(runId));

        List<Turn> turns = second.conversation().turns();
        assertEquals(2, turns.size());
        assertEquals(QUESTION, turns.get(0).question());
        assertEquals(ANSWER, turns.get(0).answer());
        assertEquals("Is product PRD-1 available?", turns.get(1).question());
        assertEquals(3, store.load(turns.get(0).id()).size(), "the first turn's rows are its own");
        assertEquals(List.of(4, 5, 6, 7), store.load(runId).stream().map(Span::span).toList(), "the second turn's rows are numbered on");
    }

    @Test
    void resumingAFinishedRunReturnsItsAnswerAndWritesNoRow() {
        Orchestrator first = orchestrator(new ToolCaller(registry));
        first.run(QUESTION);
        String runId = first.conversation().turns().getLast().id();

        assertEquals(ANSWER, orchestrator(new ToolCaller(registry)).resume(runId));

        assertEquals(3, store.load(runId).size());
        assertEquals(1, handlerCalls("order_status"));
    }

    @Test
    void theLlmRowCarriesTheDecidedCallAndTheToolRowItsKey() {
        Orchestrator orchestrator = orchestrator(new ToolCaller(registry));
        orchestrator.run(QUESTION);
        String runId = orchestrator.conversation().turns().getFirst().id();

        List<Span> rows = store.load(runId);
        assertEquals("order_status", rows.get(0).attrs().get("tool"));
        assertEquals("ORD-1", rows.get(0).attrs().get("orderId"));
        assertEquals(QUESTION, rows.get(0).attrs().get("question"));
        assertEquals(runId + "/1", rows.get(1).attrs().get("key"));
        assertFalse(rows.get(2).attrs().containsKey("tool"), "Done decides no call");
    }

    private Orchestrator orchestrator(ToolCaller toolCaller) {
        return new Orchestrator(agent, registry, llm, toolCaller, store, span -> { });
    }

    private static List<Span.Kind> kinds(List<Span> rows) {
        return rows.stream().map(Span::kind).toList();
    }

    private static int handlerCalls(String tool) {
        return ToolLoader.HANDLER_CALLS.getOrDefault(tool, 0);
    }

    /** The simulated process death. */
    static class Crash extends RuntimeException {
    }

    /** Dies on its first call: before the tool runs, or right after it ran (the at-least-once window). */
    static class CrashingToolCaller extends ToolCaller {

        private final boolean runTheToolFirst;

        CrashingToolCaller(ToolRegistry registry, boolean runTheToolFirst) {
            super(registry);
            this.runTheToolFirst = runTheToolFirst;
        }

        @Override
        public ToolResult call(ToolCall call) {
            if (runTheToolFirst) {
                super.call(call);
            }
            throw new Crash();
        }
    }

    /** The rules, plus a count of how often the model was asked, and how often with no tool result yet. */
    static class CountingLLM implements LLMProxy {

        private final RuleBasedLLM rules = new RuleBasedLLM();
        int asks;
        int decisionAsks;

        @Override
        public ModelReply next(Context context, List<Tool> tools) {
            asks++;
            if (context.spans().stream().noneMatch(span -> span.kind() == Span.Kind.TOOL)) {
                decisionAsks++;
            }
            return rules.next(context, tools);
        }

        @Override
        public String answer(Turn turn, Consumer<String> out) {
            return rules.answer(turn, out);
        }
    }
}
