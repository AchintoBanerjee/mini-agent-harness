package harness.store;

import harness.core.Span;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rows and job rows kept in maps, for tests and embedding. Gone when the process ends. */
public class InMemoryRunStore implements RunStore {

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, List<Span>> runs = new LinkedHashMap<>();

    @Override
    public String newRun(String agent, String question) {
        String runId = RunStore.newId();
        while (runs.containsKey(runId)) {
            runId = RunStore.newId();
        }
        long now = System.currentTimeMillis();
        jobs.put(runId, new Job(runId, runId, agent, question, Job.Status.RUNNING, now, now));
        runs.put(runId, new ArrayList<>());
        return runId;
    }

    @Override
    public void append(String runId, Span span) {
        rows(runId).add(span);
    }

    @Override
    public List<Span> load(String runId) {
        return List.copyOf(rows(runId));
    }

    @Override
    public boolean exists(String runId) {
        return runs.containsKey(runId);
    }

    @Override
    public Job job(String runId) {
        rows(runId);
        return jobs.get(runId);
    }

    @Override
    public void setStatus(String runId, Job.Status status) {
        jobs.put(runId, job(runId).with(status, System.currentTimeMillis()));
    }

    private List<Span> rows(String runId) {
        List<Span> rows = runs.get(runId);
        if (rows == null) {
            throw new IllegalArgumentException("no run " + runId);
        }
        return rows;
    }
}
