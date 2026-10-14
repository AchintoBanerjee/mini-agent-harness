package harness;

/** One recorded step inside a turn: a model call or a tool call. */
public record Span(Kind kind, String request, String response) {

    public enum Kind { LLM, TOOL }
}
