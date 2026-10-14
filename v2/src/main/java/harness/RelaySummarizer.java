package harness;

import java.util.List;

/** Answer is the last tool output, unchanged. No tool ran means no tool fit the question. */
public class RelaySummarizer implements Summarizer {

    public static final String NO_TOOL = "No tool for this question.";

    @Override
    public String summarize(Turn turn) {
        List<Span> spans = turn.spans();
        for (int i = spans.size() - 1; i >= 0; i--) {
            if (spans.get(i).kind() == Span.Kind.TOOL) {
                return spans.get(i).response();
            }
        }
        return NO_TOOL;
    }
}
