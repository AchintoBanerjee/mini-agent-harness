package harness.core;

import java.util.List;

/** What the model sees: the question and the spans already recorded in this turn. */
public record Context(String question, List<Span> spans) {
}
