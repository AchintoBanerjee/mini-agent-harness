package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.model.LLMProxy;
import harness.model.RuleBasedLLM;
import harness.model.ToolCall;
import harness.store.InMemoryRunStore;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import harness.tools.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The step log: every row lands in the store, in order, before the loop acts on it. */
class RunLogTest {

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final Agent agent = AgentLoader.load(Path.of("agents", "support.json"));
    private final InMemoryRunStore store = new InMemoryRunStore();
    private final List<Span> announced = new ArrayList<>();

    @Test
    void rowsLandInTheStoreInOrderWithIncreasingSteps() {
        Orchestrator orchestrator = new Orchestrator(agent, registry, new RuleBasedLLM(),
                new ToolCaller(registry), store, announced::add);

        orchestrator.run("What is the status of order ORD-1?");
        orchestrator.run("Is product PRD-1 available?");

        List<Turn> turns = orchestrator.conversation().turns();
        assertEquals(2, turns.size(), "one turn per question, in one conversation");
        assertTrue(orchestrator.conversation().id().matches("c-[0-9a-f]{4}"), orchestrator.conversation().id());
        List<Span> rows = new ArrayList<>(store.load(turns.get(0).id()));
        rows.addAll(store.load(turns.get(1).id()));
        assertTrue(turns.get(0).id().matches("r-[0-9a-f]{4}"), turns.get(0).id());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), rows.stream().map(Span::span).toList(), "the span numbers run on through the conversation");
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM),
                rows.stream().map(Span::kind).toList());
        assertTrue(rows.subList(0, 3).stream().allMatch(row -> row.turnId().equals(turns.get(0).id())));
        assertTrue(rows.subList(3, 6).stream().allMatch(row -> row.turnId().equals(turns.get(1).id())));
        assertEquals(rows, announced, "the listener saw the same rows in the same order");
    }

    @Test
    void eachRowIsInTheStoreBeforeTheLoopActsOnIt() {
        ToolCaller checking = new ToolCaller(registry) {
            @Override
            public ToolResult call(ToolCall call) {
                Span last = store.load(announced.getLast().turnId()).getLast();
                assertEquals(Span.Kind.LLM, last.kind(), "the LLM row is stored before its tool runs");
                assertTrue(last.response().contains(call.toolName()));
                return super.call(call);
            }
        };
        LLMProxy checkingToo = OrchestratorTest.deciding((context, tools) -> {
            if (!context.spans().isEmpty()) {
                Span last = store.load(context.spans().getLast().turnId()).getLast();
                assertEquals(Span.Kind.TOOL, last.kind(), "the TOOL row is stored before the model is asked again");
            }
            return new RuleBasedLLM().next(context, tools);
        });
        Orchestrator orchestrator = new Orchestrator(agent, registry, checkingToo,
                checking, store, announced::add);

        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", orchestrator.run("What is the status of order ORD-1?"));
        assertEquals(3, announced.size());
    }
}
