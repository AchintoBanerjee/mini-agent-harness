package harness.tools;

import harness.core.Span;
import harness.model.ToolCall;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs tool calls, one or several together. Never throws and never hangs: a tool the agent
 * did not list is DENIED without running, a call that outlives the timeout is cancelled and
 * reported as TIMEOUT, anything a tool throws becomes an ERROR result, and each result
 * carries its own call's time. Every call runs on one long-lived virtual-thread executor
 * owned by this caller, never closed on the call path: a cut-off tool cannot take the next down.
 */
public class ToolCaller {

    /** What the one-argument constructor allows a call: 2 s, the support agent's setting. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private final ToolRegistry registry;
    private final Set<String> allowed;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Map<String, Tool> bound = Map.of();

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

    /**
     * The tools as the loop offers them, looked up before the registry: an agent tool is
     * bound to the loop that offers it, so its call runs that loop's child. Everything else
     * is the registry's copy either way.
     */
    public void bind(List<Tool> tools) {
        Map<String, Tool> byName = new HashMap<>();
        for (Tool tool : tools) {
            byName.put(tool.name(), tool);
        }
        bound = byName;
    }

    public ToolResult call(ToolCall call) {
        return await(executor.submit(() -> run(call)), System.nanoTime());
    }

    /** Runs the calls together, each within the timeout from this submission; results come back in request order. */
    public List<ToolResult> callAll(List<ToolCall> calls) {
        long submitted = System.nanoTime();
        List<Future<ToolResult>> futures = new ArrayList<>();
        for (ToolCall call : calls) {
            futures.add(executor.submit(() -> run(call)));
        }
        List<ToolResult> results = new ArrayList<>();
        for (Future<ToolResult> future : futures) {
            results.add(await(future, submitted));
        }
        return results;
    }

    /** On the executor: gate the call, run the handler, time it. Never throws. */
    private ToolResult run(ToolCall call) {
        Optional<Tool> tool = Optional.ofNullable(bound.get(call.toolName())).or(() -> registry.find(call.toolName()));
        if (tool.isEmpty()) {
            return new ToolResult("Unknown tool: " + call.toolName(), Span.Status.ERROR, 0);
        }
        if (!allowed.contains(call.toolName())) {
            return new ToolResult("not in this agent's tool list", Span.Status.DENIED, 0);
        }
        long started = System.nanoTime();
        try {
            return new ToolResult(tool.get().run(call.input()), Span.Status.OK, Span.millisSince(started));
        } catch (Throwable thrown) {
            return new ToolResult("Tool failed: " + message(thrown), Span.Status.ERROR, Span.millisSince(started));
        }
    }

    /** Waits for the call until its deadline (submission + timeout); past it the call is cancelled. */
    private ToolResult await(Future<ToolResult> future, long submittedNanos) {
        try {
            return future.get(Math.max(timeout.toNanos() - (System.nanoTime() - submittedNanos), 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new ToolResult("cut off after " + timeout.toMillis() + " ms", Span.Status.TIMEOUT, timeout.toMillis());
        } catch (ExecutionException e) {
            return new ToolResult("Tool failed: " + message(e.getCause()), Span.Status.ERROR, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ToolResult("Tool failed: the caller was interrupted", Span.Status.ERROR, 0);
        }
    }

    /** Whatever the tool threw, Exception or Error: its message, or its type when it has none. */
    private static String message(Throwable thrown) {
        return thrown.getMessage() == null ? thrown.getClass().getSimpleName() : thrown.getMessage();
    }
}
