package harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoaderTest {

    private static final Path AGENTS = Path.of("agents");

    @Test
    void supportAgentLoadsWithEveryField() {
        Agent agent = AgentLoader.load(AGENTS.resolve("support.json"));

        assertEquals(new Agent("support", "Answers order, shipment and stock questions",
                List.of("order_status", "shipping_status", "stock_status"), 5, 2000), agent);
    }

    @Test
    void theFolderLoadsEveryAgentInFileNameOrder() {
        List<Agent> agents = AgentLoader.loadAll(AGENTS);

        assertEquals(List.of("chaos", "inventory", "support"), agents.stream().map(Agent::name).toList());
        assertEquals(List.of("stock_status"), agents.get(1).toolNames());
        assertEquals(2, agents.get(1).maxPasses());
        assertEquals(1000, agents.get(0).toolTimeoutMs());
    }

    @Test
    void missingFieldIsRejectedByFileName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("partial.json");
        Files.writeString(file, "{ \"name\": \"partial\", \"description\": \"no tools\", \"maxPasses\": 1, \"toolTimeoutMs\": 1 }");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> AgentLoader.load(file));
        assertTrue(thrown.getMessage().contains("partial.json"));
        assertTrue(thrown.getMessage().contains("missing \"tools\""));
    }
}
