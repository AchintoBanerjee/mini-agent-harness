package harness.tools;

import harness.core.Span;
import harness.model.ToolCall;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one tool call and returns its output. Never throws and never hangs: a registered
 * tool the agent did not list is DENIED without running, a call that outlives the timeout
 * is cancelled and reported as TIMEOUT, and anything a tool throws becomes an ERROR
 * result. Each call runs on one long-lived virtual-thread executor owned by this caller;
 * it is never closed on the call path, so a cut-off tool cannot take the next call down.
 */
public class ToolCaller {

    /** What the one-argument constructor allows a call: 2 s, the support agent's setting. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private final ToolRegistry registry;
    private final Set<String> allowed;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Every registered tool allowed, the default timeout: for tests and embedding. */
    public ToolCaller(ToolRegistry registry) {
        this(registry, registry.all().stream().map(Tool::name).toList(), DEFAULT_TIMEOUT);
    }

    /** The agent's tool list and its per-call timeout gate every call. */
    public ToolCaller(ToolRegistry registry, List<String> allowedToolNames, Duration timeout) {
        this.registry = registry;
        this.allowed = Set.copyOf(allowedToolNames);
        this.timeout = timeout;
    }

    public ToolResult call(ToolCall call) {
        Optional<Tool> tool = registry.find(call.toolName());
        if (tool.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.toolName(), Span.Status.ERROR);
        }
        if (!allowed.contains(call.toolName())) {
            return new ToolResult("not in this agent's tool list", Span.Status.DENIED);
        }
        Future<String> future = executor.submit(() -> tool.get().run(call.input()));
        try {
            return new ToolResult(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS), Span.Status.OK);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new ToolResult("cut off after " + timeout.toMillis() + " ms", Span.Status.TIMEOUT);
        } catch (ExecutionException e) {
            return new ToolResult("Tool failed: " + message(e.getCause()), Span.Status.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ToolResult("Tool failed: the caller was interrupted", Span.Status.ERROR);
        }
    }

    /** Whatever the tool threw, Exception or Error: its message, or its type when it has none. */
    private static String message(Throwable thrown) {
        return thrown.getMessage() == null ? thrown.getClass().getSimpleName() : thrown.getMessage();
    }
}
