package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RelaySummarizerTest {

    private final RelaySummarizer summarizer = new RelaySummarizer();

    @Test
    void theLastToolOutputIsTheAnswer() {
        Turn turn = new Turn("order ORD-1");
        turn.spans().add(new Span(Span.Kind.LLM, "pass 1", "ToolCall"));
        turn.spans().add(new Span(Span.Kind.TOOL, "order_status {orderId=ORD-1}", "Order ORD-1: SHIPPED"));
        turn.spans().add(new Span(Span.Kind.LLM, "pass 2", "Done[]"));

        assertEquals("Order ORD-1: SHIPPED", summarizer.summarize(turn));
    }

    @Test
    void noToolRowMeansNoToolForThisQuestion() {
        Turn turn = new Turn("weather");
        turn.spans().add(new Span(Span.Kind.LLM, "pass 1", "Done[]"));

        assertEquals(RelaySummarizer.NO_TOOL, summarizer.summarize(turn));
    }

    @Test
    void aToolThatDidNotEndOkIsNamedWithItsStatus() {
        Turn turn = new Turn("boom");
        turn.spans().add(new Span("r-1", 2, 1, Span.Kind.TOOL, "throw_boom {}", "Tool failed: boom",
                Span.Status.ERROR, 1, Map.of("tool", "throw_boom")));

        assertEquals("Tool throw_boom ERROR: Tool failed: boom", summarizer.summarize(turn));
    }

    @Test
    void aTimedOutToolIsNamedTheSameWay() {
        Turn turn = new Turn("hang");
        turn.spans().add(new Span("r-1", 2, 1, Span.Kind.TOOL, "hang_forever {orderId=ORD-1}", "cut off after 1000 ms",
                Span.Status.TIMEOUT, 1000, Map.of("tool", "hang_forever", "orderId", "ORD-1")));
        turn.spans().add(new Span(Span.Kind.LLM, "pass 2", "Done[]"));

        assertEquals("Tool hang_forever TIMEOUT: cut off after 1000 ms", summarizer.summarize(turn));
    }

    @Test
    void severalToolsInTheLastPassAreJoinedWithNewlinesInRequestOrder() {
        Turn turn = new Turn("order ORD-2 and PRD-1");
        turn.spans().add(new Span("r-1", 1, 1, Span.Kind.LLM, "pass 1", "ToolCalls[...]", Span.Status.OK, 0, Map.of()));
        turn.spans().add(new Span("r-1", 2, 1, Span.Kind.TOOL, "order_status {orderId=ORD-2}", "Order ORD-2: PROCESSING",
                Span.Status.OK, 300, Map.of("tool", "order_status")));
        turn.spans().add(new Span("r-1", 3, 1, Span.Kind.TOOL, "stock_status {productId=PRD-1}", "Product PRD-1: 42 units in stock",
                Span.Status.OK, 301, Map.of("tool", "stock_status")));
        turn.spans().add(new Span("r-1", 4, 1, Span.Kind.NOTE, "ran 2 tools together: wall 302 ms, work 601 ms", "",
                Span.Status.OK, 302, Map.of()));
        turn.spans().add(new Span("r-1", 5, 2, Span.Kind.LLM, "pass 2", "Done[]", Span.Status.OK, 0, Map.of()));

        assertEquals("Order ORD-2: PROCESSING\nProduct PRD-1: 42 units in stock", summarizer.summarize(turn));
    }

    @Test
    void onlyTheLastPassCountsAndAFailedSiblingIsNamedInItsPlace() {
        Turn turn = new Turn("x");
        turn.spans().add(new Span("r-1", 2, 1, Span.Kind.TOOL, "order_status {orderId=ORD-3}", "Order ORD-3: DELIVERED, shipment SHP-3",
                Span.Status.OK, 1, Map.of("tool", "order_status")));
        turn.spans().add(new Span("r-1", 4, 2, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "Order ORD-1: SHIPPED, shipment SHP-1",
                Span.Status.OK, 1, Map.of("tool", "order_status")));
        turn.spans().add(new Span("r-1", 5, 2, Span.Kind.TOOL, "throw_boom {}", "Tool failed: boom",
                Span.Status.ERROR, 1, Map.of("tool", "throw_boom")));

        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1\nTool throw_boom ERROR: Tool failed: boom", summarizer.summarize(turn));
    }

    @Test
    void aDedupedRowAnswersWithTheOutputOfTheStepItPointsAtEvenFromAnEarlierTurn() {
        Conversation conversation = new Conversation("c-1", null);
        Turn first = conversation.turn("r-1", "order ORD-1");
        first.spans().add(new Span("r-1", 2, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "Order ORD-1: SHIPPED",
                Span.Status.OK, 1, Map.of("tool", "order_status")));
        Turn second = conversation.turn("r-2", "order ORD-1 again");
        second.spans().add(new Span("r-2", 5, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "unchanged, see step 2",
                Span.Status.DEDUPED, 0, Map.of("tool", "order_status", "ref", "2")));

        assertEquals("Order ORD-1: SHIPPED", summarizer.summarize(second));
    }
}
