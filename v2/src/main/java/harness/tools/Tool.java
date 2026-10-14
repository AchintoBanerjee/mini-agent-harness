package harness.tools;

import java.util.Map;
import java.util.function.Function;

/**
 * Something the agent can run. type names the adapter that built the handler (lookup,
 * sleep, fail). inputField is the one input it needs, for example "orderId"; null when
 * the tool takes none. The handler does the work and returns text.
 */
public record Tool(
        String name,
        String description,
        String type,
        String inputField,
        MatchingCondition matching,
        Function<Map<String, String>, String> handler) {

    public String run(Map<String, String> input) {
        return handler.apply(input);
    }
}
