package harness;

import java.util.List;

/** The model. Today a rule-based stub; later swapped for a real LLM behind this interface. */
public interface LLMProxy {

    ModelReply next(Context context, List<Tool> tools);
}
