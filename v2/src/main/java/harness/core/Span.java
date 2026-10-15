package harness.core;

import java.util.Map;

/**
 * One recorded step of a run: a model call, a tool call, or a note from the loop itself.
 * These fields are fixed for good; later steps add keys to attrs, never new fields.
 */
public record Span(
        String run,
        int step,
        int turn,
        int pass,
        Kind kind,
        String request,
        String response,
        Status status,
        long ms,
        Map<String, String> attrs) {

    public enum Kind { LLM, TOOL, NOTE }

    public enum Status { OK, ERROR, TIMEOUT, DENIED, DEDUPED, CUT_SHORT }

    /** Short form for tests and hand-built contexts: no run, no position, no timing, status OK. */
    public Span(Kind kind, String request, String response) {
        this("", 0, 0, 0, kind, request, response, Status.OK, 0, Map.of());
    }

    /** The ms a row carries: whole milliseconds since a System.nanoTime() reading. */
    public static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
