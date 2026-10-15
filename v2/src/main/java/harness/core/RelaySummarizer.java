package harness.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Answer is what the last pass's tools gave back, unchanged; several tools in one pass are
 * joined with newlines in the order they were asked for. A tool that did not end OK is
 * named with its status so the failure reaches the user. No tool ran means no tool fit.
 */
public class RelaySummarizer implements Summarizer {

    public static final String NO_TOOL = "No tool for this question.";

    @Override
    public String summarize(Turn turn) {
        List<String> outputs = new ArrayList<>();
        int pass = -1;
        for (Span span : turn.spans()) {
            if (span.kind() != Span.Kind.TOOL) {
                continue;
            }
            if (span.pass() != pass) {
                outputs.clear();
                pass = span.pass();
            }
            outputs.add(span.status() == Span.Status.OK
                    ? span.response()
                    : "Tool " + span.attrs().get("tool") + " " + span.status() + ": " + span.response());
        }
        return outputs.isEmpty() ? NO_TOOL : String.join("\n", outputs);
    }
}
