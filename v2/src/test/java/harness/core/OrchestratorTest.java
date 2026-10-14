package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.model.LLMProxy;
import harness.model.RuleBasedLLM;
import harness.model.ToolCall;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestratorTest {

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final Agent agent = AgentLoader.load(Path.of("agents", "support.json"));

    private Orchestrator orchestrator() {
        return new Orchestrator(agent, registry, new RuleBasedLLM(), new ToolCaller(registry), new RelaySummarizer());
    }

    @Test
    void orderQuestionIsAnsweredByTheOrderTool() {
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", orchestrator().run("What is the status of order ORD-1?"));
    }

    @Test
    void shipmentQuestionIsAnsweredByTheShippingTool() {
        assertEquals("Shipment SHP-1: IN TRANSIT, arriving 2026-10-16", orchestrator().run("Where is my shipment SHP-1?"));
    }

    @Test
    void stockQuestionIsAnsweredByTheStockTool() {
        assertEquals("Product PRD-2: OUT OF STOCK", orchestrator().run("Is product PRD-2 available?"));
    }

    @Test
    void questionWithNoMatchingToolEndsWithNoToolAnswer() {
        assertEquals(RelaySummarizer.NO_TOOL, orchestrator().run("What is the weather today?"));
    }

    @Test
    void turnRecordsModelCallThenToolCallThenDone() {
        Orchestrator orchestrator = orchestrator();

        orchestrator.run("What is the status of order ORD-2?");

        Turn turn = orchestrator.conversation().turns().get(0);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM),
                turn.spans().stream().map(Span::kind).toList());
        assertEquals("Order ORD-2: PROCESSING", turn.answer());
    }

    @Test
    void eachQuestionIsItsOwnTurnInTheSameConversation() {
        Orchestrator orchestrator = orchestrator();

        orchestrator.run("What is the status of order ORD-1?");
        orchestrator.run("Is product PRD-1 available?");

        assertEquals(2, orchestrator.conversation().turns().size());
        assertEquals("Product PRD-1: 42 units in stock", orchestrator.conversation().turns().get(1).answer());
    }

    @Test
    void summarizerCanBeSwapped() {
        Summarizer shouting = turn -> "Here you go: " + new RelaySummarizer().summarize(turn).toUpperCase();
        Orchestrator orchestrator = new Orchestrator(
                agent, registry, new RuleBasedLLM(), new ToolCaller(registry), shouting);

        assertEquals("Here you go: ORDER ORD-3: DELIVERED, SHIPMENT SHP-3", orchestrator.run("order ORD-3 status"));
    }

    @Test
    void agentWithAToolMissingFromTheRegistryIsRejected() {
        Agent bad = new Agent("bad", "lists a tool that does not exist", List.of("order_status", "cancel_order"), 5, 2000);

        assertThrows(IllegalArgumentException.class, () ->
                new Orchestrator(bad, registry, new RuleBasedLLM(), new ToolCaller(registry), new RelaySummarizer()));
    }

    @Test
    void aModelThatNeverSaysDoneIsStoppedAfterMaxPasses() {
        LLMProxy stubborn = (context, tools) -> new ToolCall("order_status", Map.of("orderId", "ORD-1"));
        Orchestrator orchestrator = new Orchestrator(
                agent, registry, stubborn, new ToolCaller(registry), new RelaySummarizer());

        orchestrator.run("anything");

        Turn turn = orchestrator.conversation().turns().get(0);
        long modelCalls = turn.spans().stream().filter(s -> s.kind() == Span.Kind.LLM).count();
        assertEquals(agent.maxPasses(), modelCalls);
    }

    @Test
    void hittingTheCapLeavesACutShortNoteAsTheLastRow() {
        LLMProxy stubborn = (context, tools) -> new ToolCall("order_status", Map.of("orderId", "ORD-1"));
        Agent twoPasses = new Agent("stubborn", "never says done", List.of("order_status"), 2, 2000);
        Orchestrator orchestrator = new Orchestrator(
                twoPasses, registry, stubborn, new ToolCaller(registry), new RelaySummarizer());

        String answer = orchestrator.run("anything");

        List<Span> rows = orchestrator.conversation().turns().get(0).spans();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.NOTE),
                rows.stream().map(Span::kind).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), rows.stream().map(Span::step).toList());
        Span note = rows.getLast();
        assertEquals(Span.Status.CUT_SHORT, note.status());
        assertEquals("cut short at maxPasses=2", note.request());
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", answer);
    }
}
