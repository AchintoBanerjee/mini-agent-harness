package harness.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolLoaderTest {

    private static final Path TOOLS = Path.of("tools");

    @Test
    void theFolderBecomesARegistryWithEveryToolByName() {
        ToolRegistry registry = ToolLoader.load(TOOLS);

        assertEquals(List.of("ask_logistics", "ask_loop", "hang_forever", "order_status", "refund_order", "shipping_status",
                "stock_status", "throw_boom"), registry.all().stream().map(Tool::name).toList());
    }

    @Test
    void agentToolNamesTheAgentFromTheFolderNextDoorAndRunsOnlyInsideATurn() {
        Tool tool = ToolLoader.loadOne(TOOLS.resolve("ask_logistics.json"));

        assertEquals("agent", tool.type());
        assertTrue(tool.isAgent());
        assertEquals("logistics", tool.agent().name());
        assertEquals(List.of("shipping_status"), tool.agent().toolNames(), "the child agent's own tool list, from agents/logistics.json");
        assertEquals("goal", tool.inputField());
        assertEquals(Span.Kind.AGENT, tool.kind());
        assertEquals("SHP-1", tool.matching().match("where is shipment SHP-1").orElseThrow(), "the stub picks it like any tool");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tool.run(Map.of("goal", "anything")));
        assertTrue(thrown.getMessage().contains("runs only inside a turn"), thrown.getMessage());
        assertFalse(ToolLoader.loadOne(TOOLS.resolve("order_status.json")).isAgent());
    }

    @Test
    void agentToolWithNoAgentFileIsRejectedByFileName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ask_nobody.json");
        Files.writeString(file, "{ \"name\": \"ask_nobody\", \"description\": \"?\", \"type\": \"agent\", \"agent\": \"nobody\", \"match\": { \"keywords\": [] } }");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> ToolLoader.loadOne(file));
        assertTrue(thrown.getMessage().contains("ask_nobody.json"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("no agent file"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("nobody.json"), thrown.getMessage());
    }

    @Test
    void lookupToolAnswersFromItsDataAndItsTemplates() {
        Tool tool = ToolLoader.loadOne(TOOLS.resolve("order_status.json"));

        assertEquals("lookup", tool.type());
        assertEquals("orderId", tool.inputField());
        assertEquals(List.of("order"), tool.matching().keywords());
        assertEquals("ORD-1", tool.matching().match("status of order ORD-1").orElseThrow());
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", tool.run(Map.of("orderId", "ORD-1")));
        assertEquals("Order ORD-9 not found", tool.run(Map.of("orderId", "ORD-9")));
    }

    @Test
    void sleepToolSleepsAndCanBeInterrupted() throws InterruptedException {
        Tool tool = ToolLoader.loadOne(TOOLS.resolve("hang_forever.json"));
        assertEquals("sleep", tool.type());

        Thread sleeper = new Thread(() -> assertThrows(IllegalStateException.class, () -> tool.run(Map.of("orderId", "ORD-1"))));
        sleeper.start();
        sleeper.interrupt();
        sleeper.join(1000);

        assertFalse(sleeper.isAlive(), "an interrupted sleep tool must return at once, not after 60 s");
    }

    @Test
    void failToolThrowsItsMessageAndTakesNoInput() {
        Tool tool = ToolLoader.loadOne(TOOLS.resolve("throw_boom.json"));

        assertEquals("fail", tool.type());
        assertNull(tool.inputField());
        assertTrue(tool.matching().match("boom").isPresent());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tool.run(Map.of()));
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void unknownTypeIsRejectedByFileName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mystery.json");
        Files.writeString(file, "{ \"name\": \"mystery\", \"description\": \"?\", \"type\": \"http\", \"match\": { \"keywords\": [] } }");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> ToolLoader.loadOne(file));
        assertTrue(thrown.getMessage().contains("unknown tool type \"http\""));
        assertTrue(thrown.getMessage().contains("mystery.json"));
    }

    @Test
    void missingFieldIsRejectedByFileName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("half.json");
        Files.writeString(file, "{ \"name\": \"half\", \"description\": \"no type\" }");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> ToolLoader.loadOne(file));
        assertTrue(thrown.getMessage().contains("missing \"type\""));
    }
}
