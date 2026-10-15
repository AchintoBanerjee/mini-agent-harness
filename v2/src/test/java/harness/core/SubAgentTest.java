package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.model.RuleBasedLLM;
import harness.store.InMemoryRunStore;
import harness.store.TurnRow;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An agent as a tool: the concierge hands a shipment question to the logistics agent, whose
 * loop runs inside the tool call and writes its rows into the same turn, marked with the
 * parent span and its depth; only its answer comes back. The depth cap and the shared
 * budget of model calls are bounds in the loop, not in the prompt.
 */
class SubAgentTest {

    private static final String QUESTION = "Where is shipment SHP-1 for my order?";
    private static final String SHIPMENT = "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16";

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final Agent concierge = AgentLoader.load(Path.of("agents", "concierge.json"));
    private final Agent loop = AgentLoader.load(Path.of("agents", "loop.json"));
    private final InMemoryRunStore store = new InMemoryRunStore();
    private final List<Span> announced = new ArrayList<>();

    private Orchestrator orchestrator(Agent agent, TurnBudget budget) {
        ToolCaller caller = new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs()));
        return new Orchestrator(agent, registry, new RuleBasedLLM(), caller, new RelaySummarizer(), store, announced::add, 0, 0, budget);
    }

    @Test
    void delegationReturnsTheChildsAnswerWithItsSpansNestedInTheSameTurn() {
        Orchestrator orchestrator = orchestrator(concierge, new TurnBudget(20));

        String answer = orchestrator.run(QUESTION);

        assertEquals(SHIPMENT, answer, "the child's answer, relayed by the parent");
        String turnId = orchestrator.conversation().turns().getFirst().id();
        List<Span> rows = store.load(turnId);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM, Span.Kind.AGENT, Span.Kind.LLM),
                rows.stream().map(Span::kind).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), rows.stream().map(Span::span).toList(), "one numbering for parent and child");
        assertEquals(rows, announced, "every row announced in the order it was written, the child's too");

        Span delegating = rows.get(0);
        assertEquals("ask_logistics", delegating.attrs().get("tool"));
        assertEquals(QUESTION, delegating.attrs().get("goal"), "the stub hands the child the whole question");
        assertFalse(delegating.attrs().containsKey("depth"), "the parent's rows carry no depth");
        for (Span child : rows.subList(1, 4)) {
            assertEquals("1", child.attrs().get("parent"), child.toString());
            assertEquals("1", child.attrs().get("depth"), child.toString());
            assertEquals(turnId, child.turnId(), "the same turn, no row of its own");
        }
        assertEquals(QUESTION, rows.get(1).attrs().get("question"), "the child's question is the goal");
        assertEquals("shipping_status", rows.get(2).attrs().get("tool"), "the child's own tool");
        assertEquals(turnId + "/2", rows.get(2).attrs().get("key"), "keyed by the child's deciding span");
        assertEquals("Done[]", rows.get(3).response());

        Span result = rows.get(4);
        assertEquals(Span.Status.OK, result.status());
        assertEquals(SHIPMENT, result.response(), "the AGENT row carries the child's answer");
        assertEquals(turnId + "/1", result.attrs().get("key"));
        assertFalse(result.attrs().containsKey("depth"));
        assertEquals("Done[]", rows.get(5).response());

        List<Span> parentSees = orchestrator.conversation().turns().getFirst().spans();
        assertEquals(List.of(1, 5, 6), parentSees.stream().map(Span::span).toList(), "the parent's own view: its decision, the answer, done");
        assertEquals(TurnRow.Status.DONE, store.turn(turnId).status());
        assertEquals(4, orchestrator.budget().modelCalls(), "two for the parent, two for the child, one budget");
    }

    @Test
    void anAgentThatListsItselfStopsAtTheDepthCapWithAnErrorRow() {
        Orchestrator orchestrator = orchestrator(loop, new TurnBudget(20));

        String answer = orchestrator.run("loop, please");

        List<Span> rows = store.load(orchestrator.conversation().turns().getFirst().id());
        assertEquals(List.of("0", "1", "2", "2", "2", "1", "1", "0", "0"),
                rows.stream().map(row -> row.attrs().getOrDefault("depth", "0")).toList(), "three loops deep, then back up");
        Span refused = rows.get(3);
        assertEquals(Span.Kind.AGENT, refused.kind());
        assertEquals(Span.Status.ERROR, refused.status());
        assertTrue(refused.response().contains("depth limit"), refused.response());
        assertEquals("2", refused.attrs().get("depth"));
        assertEquals("2", refused.attrs().get("parent"), "every row of the depth-2 loop hangs under the depth-1 loop's decision");
        assertEquals(Span.Status.OK, rows.get(5).status(), "the depth-1 loop relays the refusal as an answer, as any tool result");
        assertTrue(answer.contains("depth limit"), answer);
        assertEquals(6, orchestrator.budget().modelCalls(), "two model calls per loop, never a stack overflow");
    }

    @Test
    void oneBudgetOfModelCallsStopsParentAndChildTogether() {
        Orchestrator orchestrator = orchestrator(concierge, new TurnBudget(3));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> orchestrator.run(QUESTION));

        assertTrue(thrown.getMessage().startsWith("budget exhausted"), thrown.getMessage());
        String turnId = orchestrator.conversation().turns().getFirst().id();
        assertEquals(TurnRow.Status.FAILED, store.turn(turnId).status());
        List<Span> rows = store.load(turnId);
        assertEquals(3, rows.stream().filter(row -> row.kind() == Span.Kind.LLM).count(), "three model calls in all, parent and child together");
        Span last = rows.getLast();
        assertEquals(Span.Kind.NOTE, last.kind());
        assertEquals("budget exhausted: 3 model calls for this turn", last.request());
        assertEquals(Span.Status.ERROR, last.status());
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM, Span.Kind.AGENT, Span.Kind.NOTE),
                rows.stream().map(Span::kind).toList(), "the child finished on the shared count, the parent could not ask again");
    }

    @Test
    void theChildIsFreshPerCallAndSeesNoHistory() {
        Orchestrator orchestrator = orchestrator(concierge, new TurnBudget(20));
        orchestrator.run("What is the status of order ORD-1?");

        String answer = orchestrator.run(QUESTION);

        assertEquals(SHIPMENT, answer);
        Turn second = orchestrator.conversation().turns().get(1);
        List<Span> rows = store.load(second.id());
        assertEquals(List.of(4, 5, 6, 7, 8, 9), rows.stream().map(Span::span).toList(), "numbered on from the first turn");
        assertEquals(Map.of("question", QUESTION, "tool", "shipping_status", "shipmentId", "SHP-1", "parent", "4", "depth", "1"),
                rows.get(1).attrs(), "the child's first row: its goal and its decision, nothing of the conversation");
    }
}
