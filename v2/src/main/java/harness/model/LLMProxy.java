package harness.model;

import harness.core.Context;
import harness.core.Turn;
import harness.tools.Tool;
import java.util.List;
import java.util.function.Consumer;

/** The model. Today a rule-based stub; later swapped for a real LLM behind this interface. */
public interface LLMProxy {

    ModelReply next(Context context, List<Tool> tools);

    /** The model's final reply, streamed to the loop as it is produced: each piece goes to out, the whole text is returned. */
    String answer(Turn turn, Consumer<String> out);
}
