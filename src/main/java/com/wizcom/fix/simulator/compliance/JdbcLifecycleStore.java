package com.wizcom.fix.simulator.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lifecycle state store backed by TRACE_LIFECYCLE_STATE. Used when LogToDB=Y or UseJdbcStore=Y
 * so that Primary and Secondary both read/write the same state when switching.
 */
public final class JdbcLifecycleStore implements LifecycleStateStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcLifecycleStore.class);

    private final DataSource dataSource;
    private final String tableName;
    /** Cache so we don't hit DB on every get when the row was just put in this process. */
    private final ConcurrentMap<String, LifecycleState> cache = new ConcurrentHashMap<>();

    public JdbcLifecycleStore(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName != null && !tableName.isEmpty() ? tableName : "TRACE_LIFECYCLE_STATE";
    }

    public JdbcLifecycleStore(DataSource dataSource) {
        this(dataSource, "TRACE_LIFECYCLE_STATE");
    }

    private static String key(String tradeReportId, SessionID sessionID) {
        return sessionID + "|" + tradeReportId;
    }

    private static String sessionKey(SessionID sessionID) {
        return sessionID.getBeginString() + "-" + sessionID.getSenderCompID() + "-" + sessionID.getSenderSubID()
                + "-" + sessionID.getTargetCompID() + "-" + sessionID.getTargetSubID()
                + (sessionID.getSessionQualifier() != null ? "-" + sessionID.getSessionQualifier() : "");
    }

    @Override
    public LifecycleState get(String tradeReportId, SessionID sessionID) {
        String k = key(tradeReportId, sessionID);
        LifecycleState cached = cache.get(k);
        if (cached != null) {
            return cached;
        }
        String sessionKey = sessionKey(sessionID);
        String sql = "SELECT state FROM " + tableName + " WHERE session_key = ? AND trade_report_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionKey);
            ps.setString(2, tradeReportId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String stateStr = rs.getString("state");
                    LifecycleState state = LifecycleState.valueOf(stateStr);
                    cache.put(k, state);
                    return state;
                }
            }
        } catch (SQLException e) {
            log.debug("JdbcLifecycleStore get failed: {} — treating as absent.", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.trace("Unknown state in DB: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void put(String tradeReportId, SessionID sessionID, LifecycleState state) {
        String k = key(tradeReportId, sessionID);
        cache.put(k, state);
        String sessionKey = sessionKey(sessionID);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + tableName + " SET state = ?, updated_at = ? WHERE session_key = ? AND trade_report_id = ?")) {
                ps.setString(1, state.name());
                ps.setTimestamp(2, now);
                ps.setString(3, sessionKey);
                ps.setString(4, tradeReportId);
                int updated = ps.executeUpdate();
                if (updated > 0) return;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + tableName + " (session_key, trade_report_id, state, updated_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, sessionKey);
                ps.setString(2, tradeReportId);
                ps.setString(3, state.name());
                ps.setTimestamp(4, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("JdbcLifecycleStore put failed for {} {}: {} — state may be lost on failover.", tradeReportId, sessionID, e.getMessage());
        }
    }

    @Override
    public boolean contains(String tradeReportId, SessionID sessionID) {
        return get(tradeReportId, sessionID) != null;
    }
}
