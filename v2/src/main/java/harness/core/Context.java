package harness.core;

import java.util.List;

/**
 * What the model sees: the question, the rows already recorded in this turn, and the
 * earlier turns of the conversation as "Q: ... -> A: ..." lines, oldest first.
 */
public record Context(String question, List<Span> spans, List<String> history) {

    /** A first turn, or a hand-built context: nothing came before it. */
    public Context(String question, List<Span> spans) {
        this(question, spans, List.of());
    }
}
