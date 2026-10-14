package harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallerTest {

    private final ToolCaller caller = new ToolCaller(SampleTools.registry());

    @Test
    void runsTheNamedTool() {
        ToolResult result = caller.call(new ToolCall("stock_status", Map.of("productId", "PRD-1")));

        assertFalse(result.error());
        assertEquals("Product PRD-1: 42 units in stock", result.output());
    }

    @Test
    void unknownIdIsANormalOutputNotAnError() {
        ToolResult result = caller.call(new ToolCall("order_status", Map.of("orderId", "ORD-99")));

        assertFalse(result.error());
        assertEquals("Order ORD-99 not found", result.output());
    }

    @Test
    void unknownToolIsAnErrorResult() {
        ToolResult result = caller.call(new ToolCall("refund_order", Map.of()));

        assertTrue(result.error());
        assertEquals("Unknown tool: refund_order", result.output());
    }

    @Test
    void aToolThatThrowsBecomesAnErrorResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool("broken", "always fails", "x", null, input -> {
            throw new IllegalStateException("boom");
        }));

        ToolResult result = new ToolCaller(registry).call(new ToolCall("broken", Map.of()));

        assertTrue(result.error());
        assertEquals("Tool failed: boom", result.output());
    }
}
