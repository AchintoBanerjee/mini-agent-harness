package harness.core;

import java.util.List;

/**
 * Answer is the last tool output, unchanged. A tool that did not end OK is named with its
 * status so the failure reaches the user. No tool ran means no tool fit the question.
 */
public class RelaySummarizer implements Summarizer {

    public static final String NO_TOOL = "No tool for this question.";

    @Override
    public String summarize(Turn turn) {
        List<Span> spans = turn.spans();
        for (int i = spans.size() - 1; i >= 0; i--) {
            Span span = spans.get(i);
            if (span.kind() != Span.Kind.TOOL) {
                continue;
            }
            if (span.status() == Span.Status.OK) {
                return span.response();
            }
            return "Tool " + span.attrs().get("tool") + " " + span.status() + ": " + span.response();
        }
        return NO_TOOL;
    }
}
