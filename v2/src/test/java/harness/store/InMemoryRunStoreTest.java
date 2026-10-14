package harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryRunStoreTest {

    private final InMemoryRunStore store = new InMemoryRunStore();

    @Test
    void replayFromStepThreeIsExactlyTheTail() {
        String runId = store.newRun("stubborn", "anything");
        List<Span> rows = List.of(
                row(runId, 1, Span.Kind.LLM), row(runId, 2, Span.Kind.TOOL), row(runId, 3, Span.Kind.LLM),
                row(runId, 4, Span.Kind.TOOL), row(runId, 5, Span.Kind.NOTE));
        for (Span row : rows) {
            store.append(runId, row);
        }

        assertEquals(rows, store.load(runId));
        assertEquals(rows.subList(2, 5), store.load(runId, 3));
        assertEquals(List.of(3, 4, 5), store.load(runId, 3).stream().map(Span::step).toList());
        assertEquals(List.of(), store.load(runId, 6));
    }

    @Test
    void runIdsAreShortHexAndUnknownRunsAreRefused() {
        String runId = store.newRun("support", "q");

        assertTrue(runId.matches("r-[0-9a-f]{4}"), runId);
        assertTrue(store.exists(runId));
        assertFalse(store.exists("r-nope"));
        assertThrows(IllegalArgumentException.class, () -> store.load("r-nope"));
        assertThrows(IllegalArgumentException.class, () -> store.append("r-nope", row("r-nope", 1, Span.Kind.NOTE)));
    }

    private static Span row(String runId, int step, Span.Kind kind) {
        return new Span(runId, step, 1, (step + 1) / 2, kind, kind + " " + step, "", Span.Status.OK, 0, Map.of());
    }
}
