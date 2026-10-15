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
import harness.store.Job;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Runs the jobs on the queue. One message at a time: receive it (the lease), claim the job
 * row (RUNNING, this worker's name, attempts + 1), run the loop, ack. If the job already
 * has steps, a worker before this one died on it, so the loop resumes from the last saved
 * step instead of starting over. Every step the loop writes extends the lease, so a live
 * worker keeps its job for as long as the turn takes, and a dead one loses it one
 * visibility window after its last step. crashAfter(N) is the simulated death: exit 3
 * right after row N is persisted, no ack, the lease simply runs out.
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

    /** name goes on the job row as claimed_by (the CLI passes the pid); visibilityMs is the lease. */
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

    /**
     * One receive. False when nothing was visible. True when a message was handled: its
     * job ran (or resumed) and was acked, or it had been received too often and the queue
     * dead-lettered it.
     */
    public boolean once() {
        Optional<RunQueue.Message> next = queue.receive(visibilityMs);
        if (next.isEmpty()) {
            return false;
        }
        RunQueue.Message message = next.get();
        String jobId = message.jobId();
        if (message.dead()) {
            Job job = store.job(jobId);
            System.out.println("dead-lettered " + jobId + ": received " + message.receives() + " times, more than max "
                    + queue.maxReceives() + " · " + job.status() + " in " + store.file() + " after " + job.attempts() + " attempts");
            return true;
        }

        int attempt = store.claim(jobId, name);
        Job job = store.job(jobId);
        List<Span> saved = store.load(jobId);
        if (message.receives() > 1) {
            System.out.println("received again: attempt " + attempt + ", lease had expired · " + jobId
                    + (saved.isEmpty() ? " starts over" : " resumes from step " + saved.getLast().step()));
        } else {
            System.out.println("received " + jobId + ": attempt " + attempt + " · agent " + job.agent() + " · \"" + job.question() + "\"");
        }

        long started = System.nanoTime();
        String answer;
        try {
            answer = saved.isEmpty() ? orchestrator(job, jobId).start(jobId, job.question()) : orchestrator(job, jobId).resume(jobId);
        } catch (RuntimeException e) {
            queue.ack(jobId);
            System.out.println("failed " + jobId + ": " + e + " · " + store.job(jobId).status() + " in " + store.file() + " · acked");
            return true;
        }
        queue.ack(jobId);
        System.out.println("answer: " + answer);
        System.out.printf("done %s · %d steps · %d ms · %s in %s · acked · handler calls: %s%n",
                jobId, store.load(jobId).size(), Span.millisSince(started), store.job(jobId).status(), store.file(),
                ToolLoader.handlerCalls());
        return true;
    }

    /** The loop over this job: the agent from the job row, every written step extends the lease, then prints. */
    private Orchestrator orchestrator(Job job, String jobId) {
        Agent agent = agent(job.agent());
        ToolRegistry registry = ToolLoader.load(toolsDir);
        ToolCaller toolCaller = new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs()));
        RunListener print = crashAfter > 0 ? new CrashAfter(crashAfter, new ConsoleListener(), this::die) : new ConsoleListener();
        RunListener listener = span -> {
            queue.extend(jobId, visibilityMs);
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
