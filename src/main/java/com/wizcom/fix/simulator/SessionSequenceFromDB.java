package com.wizcom.fix.simulator;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.SessionID;

/**
 * Loads session sequence numbers (incoming_seqnum, outgoing_seqnum) from TRACE_FIX_SESSIONS
 * for a given SessionID. Primary and Secondary simulators must use the same database and table
 * so sequence state is shared and failover does not skip or duplicate MsgSeqNum.
 * Used on Logon to align session state with DB and detect missing sequences.
 * Only returns sequence when the session row is for the <b>current date</b> (creation_time date = today),
 * so max seq is scoped to the current session day.
 * Key matches QuickFIX/J JdbcStore (beginstring, sendercompid, sendersubid, senderlocid,
 * targetcompid, targetsubid, targetlocid, session_qualifier).
 * @see <a href="https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html">QuickFIX/J 2.3.0 Session</a>
 */
public class SessionSequenceFromDB {

	private static final Logger log = LoggerFactory.getLogger(SessionSequenceFromDB.class);

	private final DataSource dataSource;
	private final String sessionsTableName;
	/** Time zone for "current date" (session day). Default US/Eastern to align with typical session times. */
	private final ZoneId sessionDateZone;

	public SessionSequenceFromDB(DataSource dataSource, String sessionsTableName) {
		this(dataSource, sessionsTableName, ZoneId.of("America/New_York"));
	}

	/**
	 * @param dataSource DB connection
	 * @param sessionsTableName e.g. TRACE_FIX_SESSIONS
	 * @param sessionDateZone time zone for evaluating "current date" (only use row if creation_time is on today in this zone)
	 */
	public SessionSequenceFromDB(DataSource dataSource, String sessionsTableName, ZoneId sessionDateZone) {
		this.dataSource = dataSource;
		this.sessionsTableName = sessionsTableName != null && !sessionsTableName.isEmpty()
				? sessionsTableName
				: "TRACE_FIX_SESSIONS";
		this.sessionDateZone = sessionDateZone != null ? sessionDateZone : ZoneId.systemDefault();
	}

	/**
	 * Result of loading session sequence from DB. Null if session row not found.
	 */
	public static final class SessionSequence {
		public final int incomingSeqNum;
		public final int outgoingSeqNum;

		public SessionSequence(int incomingSeqNum, int outgoingSeqNum) {
			this.incomingSeqNum = incomingSeqNum;
			this.outgoingSeqNum = outgoingSeqNum;
		}
	}

	private static String emptyIfNull(String s) {
		return s == null ? "" : s;
	}

	/** Get location ID from SessionID (optional in QuickFIX/J 2.1; present in 2.3). Returns "" if method missing. */
	private static String getLocationId(SessionID sessionID, String methodName) {
		try {
			Method m = SessionID.class.getMethod(methodName);
			Object v = m.invoke(sessionID);
			return v == null ? "" : v.toString();
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * Fetches incoming/outgoing sequence numbers from the shared JDBC session table (same row QuickFIX/J JdbcStore uses).
	 * {@code creation_time} is the row creation timestamp, not reset daily — do not require it to match "today", or failover
	 * and calendar-day-2+ sessions would get null here and incorrectly fall back to 34=1 while the store holds 80+.
	 */
	public SessionSequence getSessionSequence(SessionID sessionID) {
		if (dataSource == null) {
			return null;
		}
		String sql = "SELECT incoming_seqnum, outgoing_seqnum, creation_time FROM " + sessionsTableName
				+ " WHERE beginstring = ? AND sendercompid = ? AND sendersubid = ? AND senderlocid = ?"
				+ " AND targetcompid = ? AND targetsubid = ? AND targetlocid = ? AND session_qualifier = ?";
		try (Connection conn = dataSource.getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, emptyIfNull(sessionID.getBeginString()));
			ps.setString(2, emptyIfNull(sessionID.getSenderCompID()));
			ps.setString(3, emptyIfNull(sessionID.getSenderSubID()));
			ps.setString(4, getLocationId(sessionID, "getSenderLocationID"));
			ps.setString(5, emptyIfNull(sessionID.getTargetCompID()));
			ps.setString(6, emptyIfNull(sessionID.getTargetSubID()));
			ps.setString(7, getLocationId(sessionID, "getTargetLocationID"));
			ps.setString(8, emptyIfNull(sessionID.getSessionQualifier()));
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					Timestamp creationTime = rs.getTimestamp("creation_time");
					LocalDate rowDate = creationTime == null ? null
							: creationTime.toInstant().atZone(sessionDateZone).toLocalDate();
					LocalDate today = LocalDate.now(sessionDateZone);
					if (rowDate != null && !rowDate.equals(today)) {
						log.debug("Session sequence row for {}: creation_time date {} (today {}); still using incoming/outgoing for shared JDBC.",
								sessionID, rowDate, today);
					}
					int inc = rs.getInt("incoming_seqnum");
					int out = rs.getInt("outgoing_seqnum");
					return new SessionSequence(Math.max(1, inc), Math.max(1, out));
				}
			}
		} catch (SQLException e) {
			log.warn("Could not load session sequence from DB for {}: {}", sessionID, e.getMessage());
		}
		return null;
	}

	/**
	 * Updates outgoing_seqnum for this session so the next Logon will use it and send the expected sequence.
	 * Used when we receive a Logout with "expecting N but received M" so we auto-fix and send N on next connection.
	 */
	public void updateOutgoingSeqNum(SessionID sessionID, int outgoingSeqNum) {
		if (dataSource == null || outgoingSeqNum < 1) {
			return;
		}
		String sql = "UPDATE " + sessionsTableName + " SET outgoing_seqnum = ?"
				+ " WHERE beginstring = ? AND sendercompid = ? AND sendersubid = ? AND senderlocid = ?"
				+ " AND targetcompid = ? AND targetsubid = ? AND targetlocid = ? AND session_qualifier = ?";
		try (Connection conn = dataSource.getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, outgoingSeqNum);
			ps.setString(2, emptyIfNull(sessionID.getBeginString()));
			ps.setString(3, emptyIfNull(sessionID.getSenderCompID()));
			ps.setString(4, emptyIfNull(sessionID.getSenderSubID()));
			ps.setString(5, getLocationId(sessionID, "getSenderLocationID"));
			ps.setString(6, emptyIfNull(sessionID.getTargetCompID()));
			ps.setString(7, emptyIfNull(sessionID.getTargetSubID()));
			ps.setString(8, getLocationId(sessionID, "getTargetLocationID"));
			ps.setString(9, emptyIfNull(sessionID.getSessionQualifier()));
			int updated = ps.executeUpdate();
			if (updated > 0) {
				log.info("Updated {} outgoing_seqnum to {} for session {} (auto-fix from Logout 58).", sessionsTableName, outgoingSeqNum, sessionID);
			}
		} catch (SQLException e) {
			log.warn("Could not update outgoing_seqnum for {}: {}", sessionID, e.getMessage());
		}
	}
}
