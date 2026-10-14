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
        turn.spans().add(new Span("r-1", 2, 1, 1, Span.Kind.TOOL, "throw_boom {}", "Tool failed: boom",
                Span.Status.ERROR, 1, Map.of("tool", "throw_boom")));

        assertEquals("Tool throw_boom ERROR: Tool failed: boom", summarizer.summarize(turn));
    }

    @Test
    void aTimedOutToolIsNamedTheSameWay() {
        Turn turn = new Turn("hang");
        turn.spans().add(new Span("r-1", 2, 1, 1, Span.Kind.TOOL, "hang_forever {orderId=ORD-1}", "cut off after 1000 ms",
                Span.Status.TIMEOUT, 1000, Map.of("tool", "hang_forever", "orderId", "ORD-1")));
        turn.spans().add(new Span(Span.Kind.LLM, "pass 2", "Done[]"));

        assertEquals("Tool hang_forever TIMEOUT: cut off after 1000 ms", summarizer.summarize(turn));
    }
}
