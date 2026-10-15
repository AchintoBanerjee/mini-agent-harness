package harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import harness.core.Context;
import harness.core.Span;
import harness.tools.Tool;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pass 1 reads the question; later passes read the results and chain into what they name. */
class RuleBasedLLMTest {

    private static final String QUESTION = "What is the status of order ORD-1?";
    private static final Span ORDER_ROW = okRow(2, "order_status", "orderId", "ORD-1", "Order ORD-1: SHIPPED, shipment SHP-1");
    private static final Span SHIPMENT_ROW = okRow(4, "shipping_status", "shipmentId", "SHP-1", "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16");

    private final RuleBasedLLM llm = new RuleBasedLLM();
    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final List<Tool> tools = List.of(
            registry.require("order_status"), registry.require("shipping_status"), registry.require("stock_status"));

    @Test
    void orderQuestionPicksOrderTool() {
        ModelReply reply = llm.next(new Context(QUESTION, List.of()), tools);

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
    void doneOnceAResultNamesNothingNew() {
        List<Span> spans = List.of(okRow(2, "order_status", "orderId", "ORD-2", "Order ORD-2: PROCESSING"));

        ModelReply reply = llm.next(new Context("What is the status of order ORD-2?", spans), tools);

        assertInstanceOf(Done.class, reply, "the result names only the order that was looked up");
    }

    @Test
    void aResultThatNamesAShipmentChainsIntoTheShippingTool() {
        ModelReply reply = llm.next(new Context(QUESTION, List.of(ORDER_ROW)), tools);

        assertEquals(new ToolCall("shipping_status", Map.of("shipmentId", "SHP-1")), reply,
                "decided from the row, not from the question, which says nothing about shipments");
    }

    @Test
    void doneOnceTheNamedShipmentHasItsOwnRow() {
        ModelReply reply = llm.next(new Context(QUESTION, List.of(ORDER_ROW, SHIPMENT_ROW)), tools);

        assertInstanceOf(Done.class, reply, "both results name ids that already ran");
    }

    @Test
    void aDedupedRowIsAReferenceAndNamesNothing() {
        Span deduped = new Span("r-2", 7, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "unchanged, see step 2",
                Span.Status.DEDUPED, 0, Map.of("tool", "order_status", "orderId", "ORD-1", "ref", "2"));

        ModelReply reply = llm.next(new Context(QUESTION + " again", List.of(deduped)), tools);

        assertInstanceOf(Done.class, reply);
    }

    @Test
    void aFailedRowIsAMessageAndNamesNothing() {
        Span timedOut = new Span("r-1", 2, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "cut off after 300 ms, shipment SHP-1 unknown",
                Span.Status.TIMEOUT, 300, Map.of("tool", "order_status", "orderId", "ORD-1"));

        ModelReply reply = llm.next(new Context(QUESTION, List.of(timedOut)), tools);

        assertInstanceOf(Done.class, reply);
    }

    @Test
    void aResultNamingTwoIdsForOneToolAsksForBothInOneBatch() {
        Span split = okRow(2, "order_status", "orderId", "ORD-9", "Order ORD-9: SPLIT, shipments SHP-1 and SHP-3");

        ModelReply reply = llm.next(new Context("status of order ORD-9", List.of(split)), tools);

        assertEquals(new ToolCalls(List.of(
                new ToolCall("shipping_status", Map.of("shipmentId", "SHP-1")),
                new ToolCall("shipping_status", Map.of("shipmentId", "SHP-3")))), reply, "in the order found, each once");
    }

    @Test
    void anAgentToolIsNeverChainedInto() {
        List<Tool> concierge = List.of(registry.require("order_status"), registry.require("ask_logistics"));

        ModelReply reply = llm.next(new Context(QUESTION, List.of(ORDER_ROW)), concierge);

        assertInstanceOf(Done.class, reply, "SHP-1 fits ask_logistics's pattern, but an agent takes a goal, not an id");
    }

    @Test
    void aToolWithoutAnIdPatternIsCalledWithNoInput() {
        List<Tool> chaos = List.of(registry.require("throw_boom"));

        ModelReply reply = llm.next(new Context("please go boom", List.of()), chaos);

        assertEquals(new ToolCall("throw_boom", Map.of()), reply);
    }

    @Test
    void aQuestionThatFitsTwoToolsComesBackAsOneBatchInToolOrder() {
        ModelReply reply = llm.next(new Context("Status of order ORD-2 and is product PRD-1 available?", List.of()), tools);

        assertEquals(new ToolCalls(List.of(
                new ToolCall("order_status", Map.of("orderId", "ORD-2")),
                new ToolCall("stock_status", Map.of("productId", "PRD-1")))), reply);
        assertEquals(2, reply.calls().size());
    }

    /** A TOOL row as the loop writes it: the tool and its input in attrs, status OK. */
    private static Span okRow(int span, String tool, String field, String id, String response) {
        return new Span("r-1", span, (span + 1) / 2, Span.Kind.TOOL, tool + " {" + field + "=" + id + "}", response,
                Span.Status.OK, 0, Map.of("tool", tool, field, id));
    }
}
