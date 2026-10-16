package harness.tools;

import harness.agent.Agent;
import harness.core.Span;
import java.util.Map;
import java.util.function.Function;

/**
 * Something the agent can run. type names the adapter that built the handler (lookup,
 * sleep, fail, container, agent). inputField is the one input it needs, for example "orderId"; null
 * when the tool takes none. The handler does the work and returns text. A tool of type
 * agent is another agent run as a tool: agent names it, and the loop that offers the tool
 * binds the handler to itself, so the call runs a child loop and returns its answer.
 */
public record Tool(
        String name,
        String description,
        String type,
        String inputField,
        MatchingCondition matching,
        Agent agent,
        Function<Map<String, String>, String> handler) {

    /** A tool that is not another agent. */
    public Tool(String name, String description, String type, String inputField,
                MatchingCondition matching, Function<Map<String, String>, String> handler) {
        this(name, description, type, inputField, matching, null, handler);
    }

    public String run(Map<String, String> input) {
        return handler.apply(input);
    }

    public boolean isAgent() {
        return agent != null;
    }

    /** The kind of row the loop records for a call of this tool. */
    public Span.Kind kind() {
        return isAgent() ? Span.Kind.AGENT : Span.Kind.TOOL;
    }

    /** The same tool with another handler: how a loop binds an agent tool to itself. */
    public Tool bound(Function<Map<String, String>, String> handler) {
        return new Tool(name, description, type, inputField, matching, agent, handler);
    }
}
