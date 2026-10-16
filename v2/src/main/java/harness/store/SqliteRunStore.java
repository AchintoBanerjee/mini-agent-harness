package harness.store;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 * One SQLite file, three tables: conversation (who talks to which agent), turn (one row per
 * turn, its id the job id: the question, the answer, whether the loop is QUEUED, RUNNING,
 * DONE or FAILED, which worker claimed it and how many times) and span (one row per
 * recorded step, attrs as a JSON text). RunQueue adds its queue table to the same file.
 * The CLI, the HTTP server and the worker all open harness.db; tests open a temp file. One
 * connection, every call synchronized: a worker's thread writes while the SSE threads read.
 */
public class SqliteRunStore implements RunStore, AutoCloseable {

    public static final Path DEFAULT_FILE = Path.of("harness.db");

    static final String CREATE_CONVERSATION = """
            CREATE TABLE IF NOT EXISTS conversation (
              id TEXT PRIMARY KEY, agent TEXT, created_at INTEGER)""";
    static final String CREATE_TURN = """
            CREATE TABLE IF NOT EXISTS turn (
              id TEXT PRIMARY KEY, conversation_id TEXT, question TEXT, answer TEXT, status TEXT,
              claimed_by TEXT, attempts INTEGER DEFAULT 0, created_at INTEGER, updated_at INTEGER)""";
    static final String CREATE_SPAN = """
            CREATE TABLE IF NOT EXISTS span (
              turn_id TEXT, span INTEGER, pass INTEGER, kind TEXT, request TEXT, response TEXT,
              status TEXT, ms INTEGER, attrs TEXT, PRIMARY KEY (turn_id, span))""";
    /** The turn row with its conversation's agent joined in. */
    static final String SELECT_TURN = """
            SELECT t.id, t.conversation_id, c.agent, t.question, t.answer, t.status, t.claimed_by, t.attempts,
                   t.created_at, t.updated_at
            FROM turn t JOIN conversation c ON c.id = t.conversation_id""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> ATTRS = new TypeReference<>() { };

    private final Path file;
    private final Connection db;

    /** Opens (or creates) the file and its tables. A file from before the rename (job, step) is refused. */
    public SqliteRunStore(Path file) {
        this.file = file;
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement statement = db.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                if (hasTable(statement, "job")) {
                    throw new IllegalStateException(file + " is from before the conversation, turn and span tables: delete it and start again");
                }
                statement.execute(CREATE_CONVERSATION);
                statement.execute(CREATE_TURN);
                statement.execute(CREATE_SPAN);
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
    public synchronized String newConversation(String agent) {
        String id = RunStore.newId("c");
        while (conversationExists(id)) {
            id = RunStore.newId("c");
        }
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO conversation (id, agent, created_at) VALUES (?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, agent);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open conversation " + id + " in " + file, e);
        }
        return id;
    }

    @Override
    public synchronized String newTurn(String conversationId, String question) {
        conversation(conversationId);
        String turnId = RunStore.newId("r");
        while (exists(turnId)) {
            turnId = RunStore.newId("r");
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO turn (id, conversation_id, question, answer, status, claimed_by, attempts, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NULL, ?, NULL, 0, ?, ?)")) {
            insert.setString(1, turnId);
            insert.setString(2, conversationId);
            insert.setString(3, question);
            insert.setString(4, TurnRow.Status.QUEUED.name());
            insert.setLong(5, now);
            insert.setLong(6, now);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open turn " + turnId + " in " + file, e);
        }
        return turnId;
    }

    @Override
    public synchronized void append(String turnId, Span span) {
        requireTurn(turnId);
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO span (turn_id, span, pass, kind, request, response, status, ms, attrs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, turnId);
            insert.setInt(2, span.span());
            insert.setInt(3, span.pass());
            insert.setString(4, span.kind().name());
            insert.setString(5, span.request());
            insert.setString(6, span.response());
            insert.setString(7, span.status().name());
            insert.setLong(8, span.ms());
            insert.setString(9, JSON.writeValueAsString(span.attrs()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write span " + span.span() + " of " + turnId + " to " + file, e);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<Span> load(String turnId) {
        requireTurn(turnId);
        List<Span> rows = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement(
                "SELECT span, pass, kind, request, response, status, ms, attrs FROM span WHERE turn_id = ? ORDER BY span")) {
            select.setString(1, turnId);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    Map<String, String> attrs = JSON.readValue(result.getString("attrs"), ATTRS);
                    rows.add(new Span(turnId, result.getInt("span"), result.getInt("pass"),
                            Span.Kind.valueOf(result.getString("kind")), result.getString("request"),
                            result.getString("response"), Span.Status.valueOf(result.getString("status")),
                            result.getLong("ms"), attrs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read turn " + turnId + " from " + file, e);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    @Override
    public synchronized boolean exists(String turnId) {
        try (PreparedStatement select = db.prepareStatement("SELECT 1 FROM turn WHERE id = ?")) {
            select.setString(1, turnId);
            try (ResultSet result = select.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    @Override
    public synchronized TurnRow turn(String turnId) {
        try (PreparedStatement select = db.prepareStatement(SELECT_TURN + " WHERE t.id = ?")) {
            select.setString(1, turnId);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("no turn " + turnId + " in " + file);
                }
                return turn(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read turn " + turnId + " from " + file, e);
        }
    }

    @Override
    public synchronized ConversationRow conversation(String conversationId) {
        try (PreparedStatement select = db.prepareStatement("SELECT id, agent, created_at FROM conversation WHERE id = ?")) {
            select.setString(1, conversationId);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("no conversation " + conversationId + " in " + file);
                }
                return new ConversationRow(result.getString("id"), result.getString("agent"), result.getLong("created_at"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read conversation " + conversationId + " from " + file, e);
        }
    }

    @Override
    public synchronized List<TurnRow> turns(String conversationId) {
        List<TurnRow> turns = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement(SELECT_TURN + " WHERE t.conversation_id = ? ORDER BY t.rowid")) {
            select.setString(1, conversationId);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    turns.add(turn(result));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read conversation " + conversationId + " from " + file, e);
        }
        return turns;
    }

    @Override
    public synchronized void setStatus(String turnId, TurnRow.Status status) {
        requireTurn(turnId);
        try (PreparedStatement update = db.prepareStatement("UPDATE turn SET status = ?, updated_at = ? WHERE id = ?")) {
            update.setString(1, status.name());
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, turnId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot update turn " + turnId + " in " + file, e);
        }
    }

    @Override
    public synchronized void done(String turnId, String answer) {
        requireTurn(turnId);
        try (PreparedStatement update = db.prepareStatement("UPDATE turn SET status = ?, answer = ?, updated_at = ? WHERE id = ?")) {
            update.setString(1, TurnRow.Status.DONE.name());
            update.setString(2, answer);
            update.setLong(3, System.currentTimeMillis());
            update.setString(4, turnId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot finish turn " + turnId + " in " + file, e);
        }
    }

    @Override
    public synchronized int claim(String turnId, String claimedBy) {
        requireTurn(turnId);
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE turn SET status = ?, claimed_by = ?, attempts = attempts + 1, updated_at = ? WHERE id = ? RETURNING attempts")) {
            update.setString(1, TurnRow.Status.RUNNING.name());
            update.setString(2, claimedBy);
            update.setLong(3, System.currentTimeMillis());
            update.setString(4, turnId);
            try (ResultSet result = update.executeQuery()) {
                result.next();
                return result.getInt("attempts");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot claim turn " + turnId + " in " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot close " + file, e);
        }
    }

    private boolean conversationExists(String id) {
        try (PreparedStatement select = db.prepareStatement("SELECT 1 FROM conversation WHERE id = ?")) {
            select.setString(1, id);
            try (ResultSet result = select.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static TurnRow turn(ResultSet result) throws SQLException {
        return new TurnRow(result.getString("id"), result.getString("conversation_id"), result.getString("agent"),
                result.getString("question"), result.getString("answer"), TurnRow.Status.valueOf(result.getString("status")),
                result.getString("claimed_by"), result.getInt("attempts"),
                result.getLong("created_at"), result.getLong("updated_at"));
    }

    private static boolean hasTable(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return result.next();
        }
    }

    private void requireTurn(String turnId) {
        if (!exists(turnId)) {
            throw new IllegalArgumentException("no turn " + turnId + " in " + file);
        }
    }
}
