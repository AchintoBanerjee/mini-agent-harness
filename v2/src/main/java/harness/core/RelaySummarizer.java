package harness.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Answer is what the last pass's tools gave back, unchanged; several tools in one pass are
 * joined with newlines in the order they were asked for. A DEDUPED row repeats an earlier
 * step, so its answer is that step's output. A tool that did not end OK is named with its
 * status so the failure reaches the user. No tool ran means no tool fit.
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
            if (span.status() == Span.Status.OK) {
                outputs.add(span.response());
            } else if (span.status() == Span.Status.DEDUPED) {
                outputs.add(original(turn, span));
            } else {
                outputs.add("Tool " + span.attrs().get("tool") + " " + span.status() + ": " + span.response());
            }
        }
        return outputs.isEmpty() ? NO_TOOL : String.join("\n", outputs);
    }

    /** Follows a DEDUPED row's ref to the step it repeats, anywhere in the conversation: that step's output. */
    private static String original(Turn turn, Span deduped) {
        int ref = Integer.parseInt(deduped.attrs().getOrDefault("ref", "-1"));
        List<Turn> turns = turn.conversation() == null ? List.of(turn) : turn.conversation().turns();
        for (Turn earlier : turns) {
            for (Span row : earlier.spans()) {
                if (row.span() == ref) {
                    return row.response();
                }
            }
        }
        return deduped.response();
    }
}
