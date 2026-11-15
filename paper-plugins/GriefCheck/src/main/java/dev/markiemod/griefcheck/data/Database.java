package dev.markiemod.griefcheck.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage. All SQL runs on one background thread, so the server thread never waits on disk.
 * Events are queued and written in batches once a second.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection c) throws SQLException;
    }

    private static final String COLUMNS = "a.id, a.time, a.actor, a.actor_uuid, a.world, a.x, a.y, a.z, a.action, "
            + "a.material, a.data, a.old_data, a.amount, a.item, a.rolled_back";

    private final Logger log;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GriefCheck-DB");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<>();
    private Connection conn;
    private Consumer<List<ForeignAction>> foreignListener = l -> {
    };

    public Database(Logger log) {
        this.log = log;
    }

    public void open(File file) throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("CREATE TABLE IF NOT EXISTS actions (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, "
                    + "actor TEXT NOT NULL, actor_uuid TEXT, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, "
                    + "z INTEGER NOT NULL, action INTEGER NOT NULL, material TEXT, data TEXT, old_data TEXT, "
                    + "amount INTEGER DEFAULT 0, item BLOB, rolled_back INTEGER DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_loc ON actions (world, x, z, y)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_time ON actions (time)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_actor ON actions (actor COLLATE NOCASE, time)");
            s.execute("CREATE TABLE IF NOT EXISTS trust (owner TEXT NOT NULL, trusted TEXT NOT NULL, "
                    + "trusted_name TEXT, PRIMARY KEY (owner, trusted))");
        }
        conn.setAutoCommit(false);
        conn.commit();
        exec.scheduleWithFixedDelay(this::flushSafely, 1, 1, TimeUnit.SECONDS);
    }

    public void setForeignListener(Consumer<List<ForeignAction>> listener) {
        this.foreignListener = listener;
    }

    public void log(LogEntry entry) {
        queue.add(entry);
    }

    public int queued() {
        return queue.size();
    }

    /** Runs a task on the DB thread after writing everything still queued. */
    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                flush();
                future.complete(task.run(conn));
            } catch (Throwable t) {
                log.log(Level.WARNING, "Database task failed", t);
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public void close() {
        exec.shutdown();
        try {
            exec.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        flushSafely();
        try {
            if (conn != null) conn.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not close database", e);
        }
    }

    // ------------------------------------------------------------------ writing

    private void flushSafely() {
        try {
            flush();
        } catch (Throwable t) {
            log.log(Level.WARNING, "Could not write GriefCheck log entries", t);
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
        }
    }

    private void flush() throws SQLException {
        if (queue.isEmpty() || conn == null) return;
        List<ForeignAction> foreign = new ArrayList<>();
        try (PreparedStatement insert = conn.prepareStatement("INSERT INTO actions (time, actor, actor_uuid, world, x, y, z, "
                + "action, material, data, old_data, amount, item) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement placer = conn.prepareStatement("SELECT actor, actor_uuid FROM actions WHERE world=? AND x=? "
                     + "AND z=? AND y=? AND action=1 AND actor_uuid IS NOT NULL ORDER BY id DESC LIMIT 1")) {
            LogEntry e;
            while ((e = queue.poll()) != null) {
                if (e.actorUuid != null && (e.action == Action.BREAK || e.action == Action.EXPLODE || e.action == Action.TAKE)) {
                    placer.setString(1, e.world);
                    placer.setInt(2, e.x);
                    placer.setInt(3, e.z);
                    placer.setInt(4, e.y);
                    try (ResultSet rs = placer.executeQuery()) {
                        if (rs.next()) {
                            UUID owner = UUID.fromString(rs.getString(2));
                            if (!owner.equals(e.actorUuid)) {
                                foreign.add(new ForeignAction(e.actorUuid, e.actor, owner, rs.getString(1), e.world,
                                        e.x, e.y, e.z, e.action, e.time));
                            }
                        }
                    }
                }
                insert.setLong(1, e.time);
                insert.setString(2, e.actor);
                insert.setString(3, e.actorUuid == null ? null : e.actorUuid.toString());
                insert.setString(4, e.world);
                insert.setInt(5, e.x);
                insert.setInt(6, e.y);
                insert.setInt(7, e.z);
                insert.setInt(8, e.action.id);
                insert.setString(9, e.material);
                insert.setString(10, e.data);
                insert.setString(11, e.oldData);
                insert.setInt(12, e.amount);
                insert.setBytes(13, e.item);
                // Written row by row (inside one transaction) so placer lookups see earlier rows of this batch.
                insert.executeUpdate();
            }
        }
        conn.commit();
        if (!foreign.isEmpty()) foreignListener.accept(foreign);
    }

    // ------------------------------------------------------------------ reading

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    private static LogEntry read(ResultSet rs) throws SQLException {
        String uuid = rs.getString(4);
        LogEntry e = new LogEntry(rs.getLong(2), rs.getString(3), uuid == null ? null : UUID.fromString(uuid),
                rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), Action.byId(rs.getInt(9)),
                rs.getString(10), rs.getString(11), rs.getString(12), rs.getInt(13), rs.getBytes(14));
        e.id = rs.getLong(1);
        e.rolledBack = rs.getInt(15) != 0;
        return e;
    }

    public CompletableFuture<List<LogEntry>> lookup(Query q, int limit, int offset) {
        Query query = q.copy();
        return submit(c -> {
            List<Object> params = new ArrayList<>();
            String sql = "SELECT " + COLUMNS + " FROM actions a WHERE " + query.where(params)
                    + " ORDER BY a.id DESC LIMIT ? OFFSET ?";
            params.add(limit);
            params.add(offset);
            List<LogEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            return out;
        });
    }

    public CompletableFuture<Integer> count(Query q) {
        Query query = q.copy();
        return submit(c -> {
            List<Object> params = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM actions a WHERE " + query.where(params))) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Per-player summary of an area: who broke whose blocks, took from whose chests, etc. */
    public CompletableFuture<List<ReportRow>> report(Query q) {
        Query query = q.copy();
        return submit(c -> {
            List<Object> params = new ArrayList<>();
            String owner = "(SELECT p.actor_uuid FROM actions p WHERE p.world=a.world AND p.x=a.x AND p.z=a.z AND p.y=a.y "
                    + "AND p.action=1 AND p.id<a.id ORDER BY p.id DESC LIMIT 1)";
            String sql = "SELECT a.actor, MAX(a.actor_uuid), "
                    + "SUM(a.action=0), "
                    + "SUM(CASE WHEN a.action IN (0,4) AND a.actor_uuid IS NOT NULL AND " + owner + " <> a.actor_uuid THEN 1 ELSE 0 END), "
                    + "SUM(a.action=1), SUM(CASE WHEN a.action=2 THEN a.amount ELSE 0 END), "
                    + "SUM(CASE WHEN a.action=2 AND a.actor_uuid IS NOT NULL AND " + owner + " <> a.actor_uuid THEN a.amount ELSE 0 END), "
                    + "SUM(a.action=4), SUM(a.action IN (5,6)), SUM(a.action=7), MAX(a.time) "
                    + "FROM actions a WHERE " + query.where(params) + " GROUP BY a.actor COLLATE NOCASE";
            List<ReportRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String uuid = rs.getString(2);
                        rows.add(new ReportRow(rs.getString(1), uuid == null ? null : UUID.fromString(uuid),
                                rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                                rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getLong(11)));
                    }
                }
            }
            rows.sort((a, b) -> Integer.compare(b.score(), a.score()));
            return rows;
        });
    }

    /** Rows a rollback (or restore) would touch, newest first for rollbacks and oldest first for restores. */
    public CompletableFuture<List<LogEntry>> forRollback(Query q, boolean rollback) {
        Query query = q.copy();
        return submit(c -> {
            List<Object> params = new ArrayList<>();
            String sql = "SELECT " + COLUMNS + " FROM actions a WHERE " + query.where(params)
                    + " AND a.rolled_back = " + (rollback ? 0 : 1)
                    + " AND a.action IN (0,1,2,3,4,5) ORDER BY a.id " + (rollback ? "DESC" : "ASC");
            List<LogEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> markRolledBack(Collection<Long> ids, boolean rolledBack) {
        List<Long> list = new ArrayList<>(ids);
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE actions SET rolled_back=? WHERE id=?")) {
                for (long id : list) {
                    ps.setInt(1, rolledBack ? 1 : 0);
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            return null;
        });
    }

    public CompletableFuture<Integer> purge(long before) {
        return submit(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM actions WHERE time < ?")) {
                ps.setLong(1, before);
                n = ps.executeUpdate();
            }
            c.commit();
            return n;
        });
    }

    public CompletableFuture<Long> totalRows() {
        return submit(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM actions")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    // ------------------------------------------------------------------ trust

    public CompletableFuture<Map<UUID, Map<UUID, String>>> loadTrust() {
        return submit(c -> {
            Map<UUID, Map<UUID, String>> out = new ConcurrentHashMap<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT owner, trusted, trusted_name FROM trust")) {
                while (rs.next()) {
                    out.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new ConcurrentHashMap<>())
                            .put(UUID.fromString(rs.getString(2)), rs.getString(3) == null ? "?" : rs.getString(3));
                }
            }
            return out;
        });
    }

    public void setTrust(UUID owner, UUID trusted, String name, boolean add) {
        submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(add
                    ? "INSERT OR REPLACE INTO trust (owner, trusted, trusted_name) VALUES (?,?,?)"
                    : "DELETE FROM trust WHERE owner=? AND trusted=?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, trusted.toString());
                if (add) ps.setString(3, name);
                ps.executeUpdate();
            }
            c.commit();
            return null;
        });
    }
}
