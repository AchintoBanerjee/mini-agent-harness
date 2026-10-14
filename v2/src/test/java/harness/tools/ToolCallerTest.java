package harness.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import harness.core.Span;
import harness.model.ToolCall;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallerTest {

    private final ToolCaller caller = new ToolCaller(ToolLoader.load(Path.of("tools")));

    @Test
    void runsTheNamedTool() {
        ToolResult result = caller.call(new ToolCall("stock_status", Map.of("productId", "PRD-1")));

        assertEquals(Span.Status.OK, result.status());
        assertEquals("Product PRD-1: 42 units in stock", result.output());
    }

    @Test
    void unknownIdIsANormalOutputNotAnError() {
        ToolResult result = caller.call(new ToolCall("order_status", Map.of("orderId", "ORD-99")));

        assertEquals(Span.Status.OK, result.status());
        assertEquals("Order ORD-99 not found", result.output());
    }

    @Test
    void unknownToolIsAnErrorResult() {
        ToolResult result = caller.call(new ToolCall("cancel_order", Map.of()));

        assertEquals(Span.Status.ERROR, result.status());
        assertEquals("Unknown tool: cancel_order", result.output());
    }

    @Test
    void aToolThatThrowsBecomesAnErrorResult() {
        ToolResult result = caller.call(new ToolCall("throw_boom", Map.of()));

        assertEquals(Span.Status.ERROR, result.status());
        assertEquals("Tool failed: boom", result.output());
    }
}
