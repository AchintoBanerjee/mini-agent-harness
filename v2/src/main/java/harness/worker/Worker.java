package harness.worker;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.core.ConsoleListener;
import harness.core.CrashAfter;
import harness.core.Orchestrator;
import harness.core.RelaySummarizer;
import harness.core.RunListener;
import harness.core.Span;
import harness.model.LLMProxy;
import harness.model.RuleBasedLLM;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.store.TurnRow;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Runs the turns on the queue. One message at a time: receive it (the lease), claim the
 * turn row (RUNNING, this worker's name, attempts + 1), run the loop, ack. If the turn
 * already has rows, a worker before this one died on it, so the loop resumes from the last
 * saved step instead of starting over. Every row the loop writes extends the lease, so a
 * live worker keeps its turn for as long as it takes, and a dead one loses it one
 * visibility window after its last row. crashAfter(N) is the simulated death: exit 3 right
 * after row N is persisted, no ack, the lease simply runs out.
 */
public class Worker {

    public static final long DEFAULT_VISIBILITY_MS = 2000;

    /** How long an idle worker waits before asking the queue again. */
    public static final long POLL_MS = 500;

    private final Path agentsDir;
    private final Path toolsDir;
    private final SqliteRunStore store;
    private final RunQueue queue;
    private final String name;
    private final long visibilityMs;
    private int crashAfter;
    private LLMProxy llm = new RuleBasedLLM();
    private long pollMs = POLL_MS;
    private volatile boolean stopped;

    /** name goes on the turn row as claimed_by (the CLI passes the pid); visibilityMs is the lease. */
    public Worker(Path agentsDir, Path toolsDir, SqliteRunStore store, RunQueue queue, String name, long visibilityMs) {
        this.agentsDir = agentsDir;
        this.toolsDir = toolsDir;
        this.store = store;
        this.queue = queue;
        this.name = name;
        this.visibilityMs = visibilityMs;
    }

    /** --crash-after N: die right after row N is persisted (0 = never). */
    public void crashAfter(int step) {
        this.crashAfter = step;
    }

    /** The model the loop asks; tests put a counting one here. */
    public void model(LLMProxy llm) {
        this.llm = llm;
    }

    /** How long loop() waits when the queue is empty; tests shorten it. */
    public void pollMs(long pollMs) {
        this.pollMs = pollMs;
    }

    /** Until stop(): one message at a time, or a pause when the queue is empty. */
    public void loop() {
        while (!stopped) {
            if (!once()) {
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void stop() {
        stopped = true;
    }

    /** One receive. False when nothing was visible; true when a turn ran (or resumed) and was acked. */
    public boolean once() {
        Optional<RunQueue.Message> next = queue.receive(visibilityMs);
        if (next.isEmpty()) {
            return false;
        }
        String turnId = next.get().turnId();
        int attempt = store.claim(turnId, name);
        TurnRow turn = store.turn(turnId);
        List<Span> saved = store.load(turnId);
        if (next.get().receives() > 1) {
            System.out.println("received again: attempt " + attempt + ", lease had expired · " + turnId
                    + (saved.isEmpty() ? " starts over" : " resumes from step " + saved.getLast().span()));
        } else {
            System.out.println("received " + turnId + ": attempt " + attempt + " · agent " + turn.agent() + " · \"" + turn.question() + "\"");
        }

        long started = System.nanoTime();
        String answer;
        try {
            answer = saved.isEmpty() ? orchestrator(turn).start(turnId) : orchestrator(turn).resume(turnId);
        } catch (RuntimeException e) {
            queue.ack(turnId);
            System.out.println("failed " + turnId + ": " + e + " · " + store.turn(turnId).status() + " in " + store.file() + " · acked");
            return true;
        }
        queue.ack(turnId);
        System.out.println("answer: " + answer);
        System.out.printf("done %s · %d spans · %d ms · %s in %s · acked · handler calls: %s%n",
                turnId, store.load(turnId).size(), Span.millisSince(started), store.turn(turnId).status(), store.file(),
                ToolLoader.handlerCalls());
        return true;
    }

    /** The loop over this turn: the agent from the turn row, every written row extends the lease, then prints. */
    private Orchestrator orchestrator(TurnRow turn) {
        Agent agent = agent(turn.agent());
        ToolRegistry registry = ToolLoader.load(toolsDir);
        ToolCaller toolCaller = new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs()));
        RunListener print = crashAfter > 0 ? new CrashAfter(crashAfter, new ConsoleListener(), this::die) : new ConsoleListener();
        RunListener listener = span -> {
            queue.extend(turn.id(), visibilityMs);
            print.on(span);
        };
        return new Orchestrator(agent, registry, llm, toolCaller, new RelaySummarizer(), store, listener);
    }

    /** The CLI's crash is the real thing: the process ends, nothing after the row happens. */
    protected void die() {
        System.exit(CrashAfter.EXIT_CODE);
    }

    private Agent agent(String name) {
        return AgentLoader.loadAll(agentsDir).stream().filter(agent -> agent.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no agent named " + name + " in " + agentsDir + "/"));
    }
}
