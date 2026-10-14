package harness.model;

import harness.core.Context;
import harness.tools.Tool;
import java.util.List;

/** The model. Today a rule-based stub; later swapped for a real LLM behind this interface. */
public interface LLMProxy {

    ModelReply next(Context context, List<Tool> tools);
}
