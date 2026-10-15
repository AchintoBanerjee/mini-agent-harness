package harness.agent;

import java.util.List;

/**
 * The bundle of tools this agent may use, by name (each must exist in the ToolRegistry),
 * with its loop cap, the time one tool call may take, and the model calls one turn may
 * spend in all, children included. An agent is data: agents/*.json.
 */
public record Agent(String name, String description, List<String> toolNames, int maxPasses, long toolTimeoutMs,
                    int maxModelCalls) {

    /** What a turn may spend on the model when the agent file does not say: parent and children together. */
    public static final int DEFAULT_MAX_MODEL_CALLS = 20;

    public Agent(String name, String description, List<String> toolNames, int maxPasses, long toolTimeoutMs) {
        this(name, description, toolNames, maxPasses, toolTimeoutMs, DEFAULT_MAX_MODEL_CALLS);
    }
}
