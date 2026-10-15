package harness.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * The run queue: a table in the store's file with the three operations of SQS (Amazon's
 * Simple Queue Service). receive hands out the oldest visible message and hides it for the
 * visibility window, in one UPDATE: that is the lease. extend pushes the window forward,
 * ack deletes the message. A worker that dies never acks, so its message becomes visible
 * again when the window runs out, and the next receive gets it. A message received more
 * than maxReceives times is not delivered: its job is set DEAD and the message dropped;
 * GET /dlq lists those jobs. Swap the table for SQS and the three calls stay the same.
 */
public class RunQueue {

    public static final int DEFAULT_MAX_RECEIVES = 3;

    static final String CREATE_QUEUE = """
            CREATE TABLE IF NOT EXISTS queue (
              job_id TEXT PRIMARY KEY, visible_at INTEGER, receives INTEGER)""";

    /** What receive hands out; dead = received once too often, the job is DEAD and the message gone. */
    public record Message(String jobId, int receives, boolean dead) {
    }

    private final SqliteRunStore store;
    private final Connection db;
    private final int maxReceives;

    public RunQueue(SqliteRunStore store) {
        this(store, DEFAULT_MAX_RECEIVES);
    }

    /** Opens the queue table in the store's file; a message is delivered at most maxReceives times. */
    public RunQueue(SqliteRunStore store, int maxReceives) {
        this.store = store;
        this.db = store.db();
        this.maxReceives = maxReceives;
        synchronized (store) {
            try (Statement statement = db.createStatement()) {
                statement.execute(CREATE_QUEUE);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot open the queue in " + store.file(), e);
            }
        }
    }

    public int maxReceives() {
        return maxReceives;
    }

    /** The job row (QUEUED) and its message, in one transaction; the job id comes back at once. */
    public String submit(String agent, String question) {
        synchronized (store) {
            try {
                db.setAutoCommit(false);
                String jobId = store.newRun(agent, question);
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO queue (job_id, visible_at, receives) VALUES (?, ?, 0)")) {
                    insert.setString(1, jobId);
                    insert.setLong(2, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                db.commit();
                return jobId;
            } catch (SQLException | RuntimeException e) {
                rollback();
                throw new IllegalStateException("cannot submit to the queue in " + store.file(), e);
            } finally {
                autoCommit();
            }
        }
    }

    /**
     * The lease: the oldest message whose visible_at has passed is hidden until now +
     * visibilityMs and counted, in one UPDATE, so two workers cannot both get it. Empty
     * when nothing is visible. A message past maxReceives comes back marked dead instead.
     */
    public Optional<Message> receive(long visibilityMs) {
        synchronized (store) {
            Message message = lease(visibilityMs);
            if (message != null && message.receives() > maxReceives) {
                store.setStatus(message.jobId(), Job.Status.DEAD);
                ack(message.jobId());
                return Optional.of(new Message(message.jobId(), message.receives(), true));
            }
            return Optional.ofNullable(message);
        }
    }

    /** The one UPDATE: hide the oldest visible message until now + visibilityMs and count the receive. */
    private Message lease(long visibilityMs) {
        long now = System.currentTimeMillis();
        try (PreparedStatement lease = db.prepareStatement("""
                UPDATE queue SET visible_at = ?, receives = receives + 1
                WHERE job_id = (SELECT job_id FROM queue WHERE visible_at <= ? ORDER BY rowid LIMIT 1)
                RETURNING job_id, receives""")) {
            lease.setLong(1, now + visibilityMs);
            lease.setLong(2, now);
            try (ResultSet result = lease.executeQuery()) {
                return result.next() ? new Message(result.getString("job_id"), result.getInt("receives"), false) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot receive from the queue in " + store.file(), e);
        }
    }

    /** The worker is alive and still on it: the message stays hidden until now + visibilityMs. */
    public void extend(String jobId, long visibilityMs) {
        synchronized (store) {
            try (PreparedStatement update = db.prepareStatement("UPDATE queue SET visible_at = ? WHERE job_id = ?")) {
                update.setLong(1, System.currentTimeMillis() + visibilityMs);
                update.setString(2, jobId);
                update.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("cannot extend " + jobId + " in " + store.file(), e);
            }
        }
    }

    /** The job is done (or given up): the message is gone for good. */
    public void ack(String jobId) {
        synchronized (store) {
            try (PreparedStatement delete = db.prepareStatement("DELETE FROM queue WHERE job_id = ?")) {
                delete.setString(1, jobId);
                delete.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("cannot ack " + jobId + " in " + store.file(), e);
            }
        }
    }

    /** How many messages the queue holds, visible or not. */
    public int size() {
        synchronized (store) {
            try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM queue")) {
                result.next();
                return result.getInt(1);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read the queue in " + store.file(), e);
            }
        }
    }

    private void rollback() {
        try {
            db.rollback();
        } catch (SQLException e) {
            // the transaction is lost either way; the caller gets the first error
        }
    }

    private void autoCommit() {
        try {
            db.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot leave the transaction in " + store.file(), e);
        }
    }
}
