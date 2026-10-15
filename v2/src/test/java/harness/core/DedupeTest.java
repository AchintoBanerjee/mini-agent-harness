package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.model.LLMProxy;
import harness.model.ModelReply;
import harness.model.RuleBasedLLM;
import harness.store.InMemoryRunStore;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Memory across turns. The model sees the earlier turns as Q/A lines, and a call whose tool
 * and input already ran OK in the conversation is not run again: its TOOL row is DEDUPED,
 * points at the step it repeats, and the answer is that step's output.
 */
class DedupeTest {

    private static final String QUESTION = "status of order ORD-1";
    private static final String ANSWER = "Order ORD-1: SHIPPED, shipment SHP-1";

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final Agent support = AgentLoader.load(Path.of("agents", "support.json"));
    private final InMemoryRunStore store = new InMemoryRunStore();
    private final SeeingLLM llm = new SeeingLLM();

    @BeforeEach
    void resetHandlerCounts() {
        ToolLoader.HANDLER_CALLS.clear();
    }

    @Test
    void theSameQuestionTwiceRunsTheHandlerOnceAndTheSecondAnswerEqualsTheFirst() {
        Orchestrator orchestrator = orchestrator(support, span -> { });

        String first = orchestrator.run(QUESTION);
        String second = orchestrator.run(QUESTION + " again");

        assertEquals(ANSWER, first);
        assertEquals(first, second);
        assertEquals(1, handlerCalls("order_status"), "one handler call for the two turns");
        List<Span> rows = rows(orchestrator);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM), kinds(rows));
        Span repeat = rows.get(4);
        assertEquals(Span.Status.DEDUPED, repeat.status());
        assertEquals("unchanged, see step 2", repeat.response());
        assertEquals("2", repeat.attrs().get("ref"));
        assertEquals("order_status", repeat.attrs().get("tool"));
        assertEquals("ORD-1", repeat.attrs().get("orderId"));
        assertEquals(orchestrator.conversation().turns().get(1).id() + "/4", repeat.attrs().get("key"), "still keyed by the LLM row that decided it");
        assertEquals(0, repeat.ms());
        assertEquals(Span.Status.OK, rows.get(1).status(), "the original row is untouched");
    }

    @Test
    void theModelSeesTheEarlierTurnsAsQuestionAndAnswerLinesOldestFirst() {
        Orchestrator orchestrator = orchestrator(support, span -> { });

        orchestrator.run(QUESTION);
        orchestrator.run("Is product PRD-1 available?");
        orchestrator.run("Where is shipment SHP-3?");

        assertEquals(6, llm.seen.size(), "two asks per turn: decide, then Done");
        assertEquals(List.of(), llm.seen.get(0).history(), "a first turn has nothing before it");
        Context thirdTurn = llm.seen.get(4);
        assertEquals(List.of("Q: " + QUESTION + " -> A: " + ANSWER,
                "Q: Is product PRD-1 available? -> A: Product PRD-1: 42 units in stock"), thirdTurn.history());
        assertTrue(thirdTurn.spans().isEmpty(), "spans are this turn's rows only");
        assertEquals("Where is shipment SHP-3?", thirdTurn.question());
    }

    @Test
    void aDifferentInputToTheSameToolStillRuns() {
        Orchestrator orchestrator = orchestrator(support, span -> { });

        orchestrator.run(QUESTION);
        assertEquals("Order ORD-2: PROCESSING", orchestrator.run("status of order ORD-2"));

        assertEquals(2, handlerCalls("order_status"));
        assertEquals(List.of(Span.Status.OK, Span.Status.OK), toolStatuses(rows(orchestrator)));
    }

    @Test
    void onlyARowThatEndedOkIsRepeated() {
        Agent chaos = AgentLoader.load(Path.of("agents", "chaos.json"));
        Orchestrator orchestrator = orchestrator(chaos, span -> { });

        orchestrator.run("boom");
        String second = orchestrator.run("boom again");

        assertEquals("Tool throw_boom ERROR: Tool failed: boom", second);
        assertEquals(List.of(Span.Status.ERROR, Span.Status.ERROR), toolStatuses(rows(orchestrator)),
                "a failed call is tried again, not repeated");
    }

    @Test
    void aRepeatedCallInABatchLeavesItsSiblingToRunAlone() {
        Orchestrator orchestrator = orchestrator(support, span -> { });

        orchestrator.run(QUESTION);
        String answer = orchestrator.run("Status of order ORD-1 and is product PRD-1 available?");

        assertEquals(ANSWER + "\nProduct PRD-1: 42 units in stock", answer, "request order, the repeat resolved in its place");
        assertEquals(Map.of("order_status", 1, "stock_status", 1), ToolLoader.HANDLER_CALLS);
        List<Span> rows = rows(orchestrator);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.TOOL, Span.Kind.LLM),
                kinds(rows), "one tool ran, so no 'ran N tools together' note");
        assertEquals(Span.Status.DEDUPED, rows.get(4).status());
        assertEquals("order_status", rows.get(4).attrs().get("tool"));
        assertEquals(Span.Status.OK, rows.get(5).status());
        assertEquals("stock_status", rows.get(5).attrs().get("tool"));
    }

    @Test
    void aRunResumedIntoARepeatedCallDoesNotRunItEither() {
        RunListener dieAfterRow4 = span -> {
            if (span.span() == 4) {
                throw new ResumeTest.Crash();
            }
        };
        Orchestrator first = orchestrator(support, dieAfterRow4);
        first.run(QUESTION);
        assertThrows(ResumeTest.Crash.class, () -> first.run(QUESTION + " again"));
        String secondTurn = first.conversation().turns().getLast().id();
        assertEquals(1, handlerCalls("order_status"));

        assertEquals(ANSWER, orchestrator(support, span -> { }).resume(secondTurn));

        List<Span> rows = store.load(secondTurn);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.NOTE, Span.Kind.TOOL, Span.Kind.LLM), kinds(rows), "the second turn's own rows");
        assertEquals(List.of(4, 5, 6, 7), rows.stream().map(Span::span).toList(), "numbered on from the first turn");
        assertEquals(Span.Status.DEDUPED, rows.get(2).status());
        assertEquals("2", rows.get(2).attrs().get("ref"), "pointing at the first turn's row");
        assertEquals(1, handlerCalls("order_status"), "the store remembers the earlier OK row: nothing ran again");
    }

    /** The rows of every turn of the conversation, in order, as the store holds them. */
    private List<Span> rows(Orchestrator orchestrator) {
        List<Span> rows = new ArrayList<>();
        for (Turn turn : orchestrator.conversation().turns()) {
            rows.addAll(store.load(turn.id()));
        }
        return rows;
    }

    private Orchestrator orchestrator(Agent agent, RunListener listener) {
        return new Orchestrator(agent, registry, llm, new ToolCaller(registry), new RelaySummarizer(), store, listener);
    }

    private static List<Span.Kind> kinds(List<Span> rows) {
        return rows.stream().map(Span::kind).toList();
    }

    private static List<Span.Status> toolStatuses(List<Span> rows) {
        return rows.stream().filter(row -> row.kind() == Span.Kind.TOOL).map(Span::status).toList();
    }

    private static int handlerCalls(String tool) {
        return ToolLoader.HANDLER_CALLS.getOrDefault(tool, 0);
    }

    /** The rules, remembering every context the model was shown. */
    static class SeeingLLM implements LLMProxy {

        private final RuleBasedLLM rules = new RuleBasedLLM();
        final List<Context> seen = new ArrayList<>();

        @Override
        public ModelReply next(Context context, List<Tool> tools) {
            seen.add(context);
            return rules.next(context, tools);
        }
    }
}
