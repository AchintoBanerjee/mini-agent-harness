package harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleBasedLLMTest {

    private final RuleBasedLLM llm = new RuleBasedLLM();
    private final List<Tool> tools = SampleTools.all();

    @Test
    void orderQuestionPicksOrderTool() {
        ModelReply reply = llm.next(new Context("What is the status of order ORD-1?", List.of()), tools);

        assertEquals(new ToolCall("order_status", Map.of("orderId", "ORD-1")), reply);
    }

    @Test
    void shipmentQuestionPicksShippingTool() {
        ModelReply reply = llm.next(new Context("where is my shipment shp-3", List.of()), tools);

        assertEquals(new ToolCall("shipping_status", Map.of("shipmentId", "SHP-3")), reply);
    }

    @Test
    void stockQuestionPicksStockTool() {
        ModelReply reply = llm.next(new Context("is PRD-2 available?", List.of()), tools);

        assertEquals(new ToolCall("stock_status", Map.of("productId", "PRD-2")), reply);
    }

    @Test
    void keywordWithoutIdMatchesNothing() {
        ModelReply reply = llm.next(new Context("where is my order?", List.of()), tools);

        assertInstanceOf(Done.class, reply);
    }

    @Test
    void doneOnceAToolHasRunInThisTurn() {
        List<Span> spans = List.of(new Span(Span.Kind.TOOL, "order_status", "Order ORD-1: SHIPPED"));

        ModelReply reply = llm.next(new Context("What is the status of order ORD-1?", spans), tools);

        assertInstanceOf(Done.class, reply);
    }
}
