package harness.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(List.of("hang_forever", "order_status", "refund_order", "shipping_status", "stock_status", "throw_boom"),
                registry.all().stream().map(Tool::name).toList());
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
