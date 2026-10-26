package edu.assignment.hms;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** All persistence uses JDBC. SQL identifiers only come from server-side allowlists. */
@Component
public class Db {
    private final DataSource source;
    public Db(DataSource source) { this.source = source; }
    @FunctionalInterface public interface Work<T> { T run(Connection c) throws Exception; }
    public <T> T read(Work<T> work) {
        try (Connection c = source.getConnection()) { return work.run(c); }
        catch (ApiError e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Database operation failed", e); }
    }
    public <T> T tx(Work<T> work) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try { T result = work.run(c); c.commit(); return result; }
            catch (Exception e) { c.rollback(); throw e; }
        } catch (ApiError e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Database operation failed", e); }
    }
    private static PreparedStatement statement(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
        return p;
    }
    public static List<Map<String,Object>> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = statement(c, sql, args); ResultSet r = p.executeQuery()) {
            var rows = new ArrayList<Map<String,Object>>(); var meta = r.getMetaData();
            while (r.next()) {
                var row = new LinkedHashMap<String,Object>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = r.getObject(i);
                    if (value instanceof java.sql.Date || value instanceof Time || value instanceof Timestamp
                        || value instanceof java.time.temporal.TemporalAccessor) value = value.toString();
                    row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
                }
                rows.add(row);
            }
            return rows;
        }
    }
    public static Map<String,Object> one(Connection c, String sql, Object... args) throws SQLException {
        var rows = query(c, sql, args);
        if (rows.isEmpty()) throw new ApiError(404, "Record not found");
        return rows.get(0);
    }
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = statement(c, sql, args)) { return p.executeUpdate(); }
    }
    public static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = statement(c, sql, args)) {
            p.executeUpdate(); try (ResultSet r = p.getGeneratedKeys()) {
                if (!r.next()) throw new SQLException("No generated ID"); return r.getLong(1);
            }
        }
    }
    public static long count(Connection c, String sql, Object... args) throws SQLException {
        return ((Number)query(c, sql, args).get(0).values().iterator().next()).longValue();
    }
    public static void audit(Connection c, long user, String action, String entity, long id) throws SQLException {
        update(c, "INSERT INTO audit_logs(user_id,action,entity_type,entity_id) VALUES (?,?,?,?)", user,action,entity,id);
    }
}
