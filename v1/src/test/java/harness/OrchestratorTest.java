package harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestratorTest {

    private final ToolRegistry registry = SampleTools.registry();
    private final Agent agent = new Agent("support-agent", List.of("order_status", "shipping_status", "stock_status"));

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
        Agent bad = new Agent("bad", List.of("order_status", "refund_order"));

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
        assertEquals(Orchestrator.MAX_PASSES, modelCalls);
    }
}
