package harness;

import java.util.Map;
import java.util.function.Function;

/**
 * Something the agent can run. inputField is the one input it needs, for example
 * "orderId". The handler does the work and returns text.
 */
public record Tool(
        String name,
        String description,
        String inputField,
        MatchingCondition matching,
        Function<Map<String, String>, String> handler) {

    public String run(Map<String, String> input) {
        return handler.apply(input);
    }
}
