package harness.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import harness.core.Span;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One SQLite file, two tables: job (one row per run: who asked what, whether the loop is
 * QUEUED, RUNNING, DONE, FAILED or DEAD, which worker claimed it and how many times) and
 * step (one row per recorded step, attrs as a JSON text). RunQueue adds its queue table to
 * the same file. The CLI, the HTTP server and the worker all open harness.db; tests open
 * a temp file. One connection, every call synchronized: a worker's thread writes while
 * the SSE threads read.
 */
public class SqliteRunStore implements RunStore, AutoCloseable {

    public static final Path DEFAULT_FILE = Path.of("harness.db");

    static final String CREATE_JOB = """
            CREATE TABLE IF NOT EXISTS job (
              id TEXT PRIMARY KEY, conversation_id TEXT, agent TEXT, question TEXT,
              status TEXT, claimed_by TEXT, attempts INTEGER DEFAULT 0, created_at INTEGER, updated_at INTEGER)""";
    static final String CREATE_STEP = """
            CREATE TABLE IF NOT EXISTS step (
              job_id TEXT, step INTEGER, turn INTEGER, pass INTEGER, kind TEXT, request TEXT,
              response TEXT, status TEXT, ms INTEGER, attrs TEXT, PRIMARY KEY (job_id, step))""";
    static final String JOB_COLUMNS = "id, conversation_id, agent, question, status, claimed_by, attempts, created_at, updated_at";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> ATTRS = new TypeReference<>() { };

    private final Path file;
    private final Connection db;

    /** Opens (or creates) the file and its two tables; a file from before the worker gets the two worker columns added. */
    public SqliteRunStore(Path file) {
        this.file = file;
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement statement = db.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute(CREATE_JOB);
                statement.execute(CREATE_STEP);
                if (!hasColumn(statement, "job", "claimed_by")) {
                    statement.execute("ALTER TABLE job ADD COLUMN claimed_by TEXT");
                    statement.execute("ALTER TABLE job ADD COLUMN attempts INTEGER DEFAULT 0");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open " + file, e);
        }
    }

    public Path file() {
        return file;
    }

    /** The one connection, for the queue that lives in the same file; callers hold this store's lock. */
    Connection db() {
        return db;
    }

    @Override
    public synchronized String newRun(String agent, String question) {
        String runId = RunStore.newId();
        while (exists(runId)) {
            runId = RunStore.newId();
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO job (" + JOB_COLUMNS + ") VALUES (?, ?, ?, ?, ?, NULL, 0, ?, ?)")) {
            insert.setString(1, runId);
            insert.setString(2, runId);
            insert.setString(3, agent);
            insert.setString(4, question);
            insert.setString(5, Job.Status.QUEUED.name());
            insert.setLong(6, now);
            insert.setLong(7, now);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open run " + runId + " in " + file, e);
        }
        return runId;
    }

    @Override
    public synchronized void append(String runId, Span span) {
        requireRun(runId);
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO step (job_id, step, turn, pass, kind, request, response, status, ms, attrs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, runId);
            insert.setInt(2, span.step());
            insert.setInt(3, span.turn());
            insert.setInt(4, span.pass());
            insert.setString(5, span.kind().name());
            insert.setString(6, span.request());
            insert.setString(7, span.response());
            insert.setString(8, span.status().name());
            insert.setLong(9, span.ms());
            insert.setString(10, JSON.writeValueAsString(span.attrs()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write step " + span.step() + " of " + runId + " to " + file, e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<Span> load(String runId) {
        requireRun(runId);
        List<Span> rows = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement(
                "SELECT step, turn, pass, kind, request, response, status, ms, attrs FROM step WHERE job_id = ? ORDER BY step")) {
            select.setString(1, runId);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    Map<String, String> attrs = JSON.readValue(result.getString("attrs"), ATTRS);
                    rows.add(new Span(runId, result.getInt("step"), result.getInt("turn"), result.getInt("pass"),
                            Span.Kind.valueOf(result.getString("kind")), result.getString("request"),
                            result.getString("response"), Span.Status.valueOf(result.getString("status")),
                            result.getLong("ms"), attrs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read run " + runId + " from " + file, e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    @Override
    public synchronized boolean exists(String runId) {
        try (PreparedStatement select = db.prepareStatement("SELECT 1 FROM job WHERE id = ?")) {
            select.setString(1, runId);
            try (ResultSet result = select.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    @Override
    public synchronized Job job(String runId) {
        try (PreparedStatement select = db.prepareStatement("SELECT " + JOB_COLUMNS + " FROM job WHERE id = ?")) {
            select.setString(1, runId);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("no run " + runId + " in " + file);
                }
                return job(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read run " + runId + " from " + file, e);
        }
    }

    @Override
    public synchronized void setStatus(String runId, Job.Status status) {
        requireRun(runId);
        try (PreparedStatement update = db.prepareStatement("UPDATE job SET status = ?, updated_at = ? WHERE id = ?")) {
            update.setString(1, status.name());
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, runId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot update run " + runId + " in " + file, e);
        }
    }

    @Override
    public synchronized int claim(String runId, String claimedBy) {
        requireRun(runId);
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE job SET status = ?, claimed_by = ?, attempts = attempts + 1, updated_at = ? WHERE id = ? RETURNING attempts")) {
            update.setString(1, Job.Status.RUNNING.name());
            update.setString(2, claimedBy);
            update.setLong(3, System.currentTimeMillis());
            update.setString(4, runId);
            try (ResultSet result = update.executeQuery()) {
                result.next();
                return result.getInt("attempts");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot claim run " + runId + " in " + file, e);
        }
    }

    @Override
    public synchronized List<Job> withStatus(Job.Status status) {
        List<Job> jobs = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement(
                "SELECT " + JOB_COLUMNS + " FROM job WHERE status = ? ORDER BY created_at, id")) {
            select.setString(1, status.name());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    jobs.add(job(result));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
        return jobs;
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot close " + file, e);
        }
    }

    private static Job job(ResultSet result) throws SQLException {
        return new Job(result.getString("id"), result.getString("conversation_id"), result.getString("agent"),
                result.getString("question"), Job.Status.valueOf(result.getString("status")),
                result.getString("claimed_by"), result.getInt("attempts"),
                result.getLong("created_at"), result.getLong("updated_at"));
    }

    private static boolean hasColumn(Statement statement, String table, String column) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (result.getString("name").equals(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void requireRun(String runId) {
        if (!exists(runId)) {
            throw new IllegalArgumentException("no run " + runId + " in " + file);
        }
    }
}
