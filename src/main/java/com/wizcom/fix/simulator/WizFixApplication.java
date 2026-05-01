/**
 * 
 */
package com.wizcom.fix.simulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.quickfixj.jmx.mbean.session.SessionAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysql.jdbc.Field;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.DoNotSend;
import quickfix.FieldConvertError;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.IntField;
import quickfix.Message;
import quickfix.MessageStore;
import quickfix.MessageCracker;
import quickfix.MessageUtils;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.StringField;
import quickfix.UnsupportedMessageType;
import quickfix.field.*;
import quickfix.fix44.Heartbeat;
import quickfix.fix44.Message.Header;
import quickfix.fix44.TestRequest;
import quickfix.fix44.TradeCaptureReport;
import quickfix.fix44.TradeCaptureReportAck;
import quickfix.fix44.component.Instrument;
import quickfix.fix44.component.Parties;

import com.wizcom.fix.simulator.compliance.CompliancePipeline;
import com.wizcom.fix.simulator.compliance.LifecycleStateStore;

/**
 * @author subhash
 *
 */
public class WizFixApplication extends MessageCracker implements quickfix.Application {

	private static final Logger log = LoggerFactory.getLogger(WizFixApplication.class);
	private static int nextID = 1;
	private SessionSettings settings;
	
	private boolean heartBtDelay=false; // HearbeatDelay=True / false
	private int heartBtDelayTime=15;
	private int heartBtDelayCount=10;
	private int heartbeatcount = 0;
	
	private boolean responseMsgDelay=false;
	private int responseMsgDelayTime=15;

	int myOption=0;
//	TimedScan timedScanner = new TimedScan(System.in);
	private boolean traceNotAvailable = false;

	private final CompliancePipeline compliancePipeline;

	/** Sessions waiting to send Logon back to initiator (LogonDelay); ignore all messages until we send Logon. */
	private final Set<SessionID> pendingLogonResponseSessions = Collections.synchronizedSet(new HashSet<>());
	/** Sessions waiting to send Heartbeat to initiator (HeartBtDelay); app messages are queued and replayed after delay. */
	private final Set<SessionID> pendingHeartbeatResponseSessions = Collections.synchronizedSet(new HashSet<>());
	/** App messages (AE etc.) received during HeartBtDelay; replayed after delay completes. Key = SessionID, value = queue of raw FIX strings. */
	private final ConcurrentMap<SessionID, ConcurrentLinkedQueue<String>> heartbeatDelayAppMessageQueue = new ConcurrentHashMap<>();
	/** Single-thread executor to run queued app message processing after Heartbeat is sent (avoids sending SPEN from inside toAdmin). */
	private static final ExecutorService heartbeatDelayQueueExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HeartBtDelay-queue-processor");
		t.setDaemon(true);
		return t;
	});

	/** All from config at startup: primary = quickfixj-server.cfg, secondary = quickfixj-server-secondary.cfg. No defaults. */
	private final String simulatorRoleFromConfig;
	private final boolean logonRequiredFromConfig;
	private final boolean logonDelayFromConfig;
	private final int logonDelaySecsFromConfig;
	private final boolean heartBeatRequiredFromConfig;
	private final boolean heartBtDelayFromConfig;
	private final int heartBtDelayCountFromConfig;
	private final int heartBtDelayTimeFromConfig;
	private final boolean traceNotAvailableFromConfig;
	private final int traceNotAvailableIntervelFromConfig;
	private final boolean responseMsgDelayFromConfig;
	private final int responseMsgDelayTimeFromConfig;

	/** Wall-clock session timeout: after successful Logon, optional timer per SessionID (ignores traffic). Parsed with Boolean.parseBoolean (True/False). */
	private final boolean sessionTimeoutRequiredFromConfig;
	private final int sessionTimeoutSecondsFromConfig;
	/** Config key isLogoutRequiredatSessionTimeout: if True, send Logout when timeout fires; if False, do nothing. Missing key defaults True. */
	private final boolean isLogoutRequiredAtSessionTimeoutFromConfig;
	private volatile ScheduledExecutorService sessionTimeoutScheduler;
	private final ConcurrentMap<SessionID, ScheduledFuture<?>> sessionTimeoutTasks = new ConcurrentHashMap<>();

	/** Optional: on Logon, fetch max sequence from DB and align session (QuickFIX/J 2.3.0 Session API). Set by Simulator when UseJdbcStore=Y. */
	private volatile SessionSequenceFromDB sessionSequenceFromDB;
	/** Last "expecting N" from Logout 58, by session; used on next Logon so we never send lower than N if DB was overwritten. */
	private final ConcurrentMap<SessionID, Integer> lastExpectedFromUsBySession = new ConcurrentHashMap<>();

	/**
	 * SessionIDs from config where LogOnRejectRequired=Y. QuickFIX/J may pass a runtime SessionID that does not equal the
	 * config map key ({@code getOrCreateSessionProperties} then only inherits [default]); we match with normalized equality
	 * and optional sender/target swap.
	 */
	private final Set<SessionID> logonRejectRequiredConfiguredSessions;

	public void setSessionSequenceFromDB(SessionSequenceFromDB sessionSequenceFromDB) {
		this.sessionSequenceFromDB = sessionSequenceFromDB;
	}

	/**
	 * Returns the session's next sender sequence from the engine after refresh (e.g. after Logon refresh from JdbcStore).
	 * QuickFIX/J 2.3+ exposes {@link Session#getNextSenderMsgSeqNum()}; 2.1.x does not — use {@link MessageStore#getNextSenderMsgSeqNum()}.
	 */
	private static int getNextSenderMsgSeqNumFromSession(Session session) {
		if (session == null) {
			return -1;
		}
		java.lang.reflect.Method m = null;
		try {
			m = Session.class.getMethod("getNextSenderMsgSeqNum");
		} catch (NoSuchMethodException e) {
			// QFJ 2.1.x: use MessageStore below
		}
		if (m != null) {
			try {
				Object v = m.invoke(session);
				if (v != null) {
					int n = ((Number) v).intValue();
					if (n >= 1) {
						return n;
					}
				}
			} catch (Exception e) {
				log.trace("getNextSenderMsgSeqNum on Session: {}", e.toString());
			}
		}
		try {
			MessageStore store = session.getStore();
			if (store != null) {
				return store.getNextSenderMsgSeqNum();
			}
		} catch (IOException e) {
			log.debug("getNextSenderMsgSeqNum from MessageStore: {}", e.getMessage());
		}
		return -1;
	}

	/** Common FIX tag numbers to human-readable names (for incoming message log). */
	private static final java.util.Map<Integer, String> FIX_TAG_NAMES = new java.util.HashMap<>();
	static {
		FIX_TAG_NAMES.put(8, "BeginString");
		FIX_TAG_NAMES.put(9, "BodyLength");
		FIX_TAG_NAMES.put(35, "MsgType");
		FIX_TAG_NAMES.put(34, "MsgSeqNum (Sequence number)");
		FIX_TAG_NAMES.put(49, "SenderCompID");
		FIX_TAG_NAMES.put(50, "SenderSubID");
		FIX_TAG_NAMES.put(52, "SendingTime");
		FIX_TAG_NAMES.put(56, "TargetCompID");
		FIX_TAG_NAMES.put(57, "TargetSubID");
		FIX_TAG_NAMES.put(98, "EncryptMethod");
		FIX_TAG_NAMES.put(108, "HeartBtInt");
		FIX_TAG_NAMES.put(10, "CheckSum");
		FIX_TAG_NAMES.put(36, "NewSeqNo");
		FIX_TAG_NAMES.put(43, "PossDupFlag");
		FIX_TAG_NAMES.put(58, "Text");
		FIX_TAG_NAMES.put(112, "TestReqID");
		FIX_TAG_NAMES.put(123, "GapFillFlag");
		FIX_TAG_NAMES.put(789, "NextExpectedMsgSeqNum");
		FIX_TAG_NAMES.put(553, "Username");
		FIX_TAG_NAMES.put(554, "Password");
		FIX_TAG_NAMES.put(141, "ResetSeqNumFlag");
		FIX_TAG_NAMES.put(369, "LastMsgSeqNumProcessed");
		FIX_TAG_NAMES.put(37, "OrderID");
		FIX_TAG_NAMES.put(11, "ClOrdID");
		FIX_TAG_NAMES.put(17, "ExecID");
		FIX_TAG_NAMES.put(31, "LastPx");
		FIX_TAG_NAMES.put(32, "LastQty");
		FIX_TAG_NAMES.put(60, "TransactTime");
		FIX_TAG_NAMES.put(150, "ExecType");
		FIX_TAG_NAMES.put(151, "LeavesQty");
		FIX_TAG_NAMES.put(38, "OrderQty");
		FIX_TAG_NAMES.put(39, "OrdStatus");
		FIX_TAG_NAMES.put(54, "Side");
		FIX_TAG_NAMES.put(55, "Symbol");
		FIX_TAG_NAMES.put(1, "Account");
		FIX_TAG_NAMES.put(44, "Price");
		FIX_TAG_NAMES.put(6, "AvgPx");
		FIX_TAG_NAMES.put(14, "CumQty");
		FIX_TAG_NAMES.put(80, "AllocQty");
	}
	/** MsgType (35) value to name for log. */
	private static String msgTypeName(String value) {
		if (value == null) return "";
		switch (value) {
			case "A": return "Logon";
			case "5": return "Logout";
			case "0": return "Heartbeat";
			case "1": return "TestRequest";
			case "2": return "ResendRequest";
			case "4": return "SequenceReset";
			case "3": return "Reject";
			case "r": return "TradeCaptureReportRequest";
			case "AE": return "TradeCaptureReport";
			case "AR": return "TradeCaptureReportAck";
			default: return value;
		}
	}

	/**
	 * Log each tag of the incoming message with a human-readable description.
	 * Call after logging the raw message string.
	 */
	private void logIncomingMessageTags(Message msg) {
		String raw = msg.toString();
		// Split by SOH (FIX delimiter) or by pattern "digit(s)=" so we get tag=value pairs
		String[] pairs = raw.split("\u0001");
		if (pairs.length <= 1) {
			// No SOH: split by lookahead for \d+=
			pairs = raw.split("(?=\\d+=)");
		}
		for (String pair : pairs) {
			String p = pair.trim();
			if (p.isEmpty()) continue;
			int eq = p.indexOf('=');
			if (eq <= 0) continue;
			String tagStr = p.substring(0, eq);
			String value = p.substring(eq + 1);
			try {
				int tag = Integer.parseInt(tagStr);
				String name = FIX_TAG_NAMES.get(tag);
				if (tag == 35 && !value.isEmpty()) {
					String mt = msgTypeName(value);
					log.info("  {} = {}  -->  MsgType = {} ({})", tagStr, value, value, mt);
				} else if (name != null) {
					log.info("  {} = {}  -->  {} = {}", tagStr, value, name, value);
				} else {
					log.info("  {} = {}  -->  Tag {} = {}", tagStr, value, tag, value);
				}
			} catch (NumberFormatException e) {
				log.info("  {} = {}  -->  (tag) = {}", tagStr, value, value);
			}
		}
	}
	
		
//    private SessionID currentSession;
//    private DataDictionary dictionary;
    
    public SessionSettings getSettings() {
        return settings;
    }
    
	public WizFixApplication() {
		// No-arg constructor: config not loaded; caller must use WizFixApplication(settings) for primary/secondary
		this.simulatorRoleFromConfig = "Primary";
		this.logonRequiredFromConfig = true;
		this.logonDelayFromConfig = false;
		this.logonDelaySecsFromConfig = 0;
		this.heartBeatRequiredFromConfig = true;
		this.heartBtDelayFromConfig = false;
		this.heartBtDelayCountFromConfig = 0;
		this.heartBtDelayTimeFromConfig = 0;
		this.traceNotAvailableFromConfig = false;
		this.traceNotAvailableIntervelFromConfig = 120;
		this.responseMsgDelayFromConfig = false;
		this.responseMsgDelayTimeFromConfig = 0;
		this.sessionTimeoutRequiredFromConfig = false;
		this.sessionTimeoutSecondsFromConfig = 0;
		this.isLogoutRequiredAtSessionTimeoutFromConfig = true;
		this.logonRejectRequiredConfiguredSessions = Collections.emptySet();
		this.compliancePipeline = new CompliancePipeline();
	}

	public WizFixApplication(SessionSettings settings) throws ConfigError, FieldConvertError {
		this(settings, null);
	}

	public WizFixApplication(SessionSettings settings, String configResourceName) throws ConfigError, FieldConvertError {
		this(settings, configResourceName, null);
	}

	public WizFixApplication(SessionSettings settings, String configResourceName, LifecycleStateStore lifecycleStore) throws ConfigError, FieldConvertError {
		this.settings = settings;
		this.compliancePipeline = new CompliancePipeline(lifecycleStore);
		// Read all required keys from loaded config. No defaults — missing key throws ConfigError.
		this.simulatorRoleFromConfig = requireStringFromDefault(settings, "SimulatorRole");
		this.logonRequiredFromConfig = requireBoolFromDefault(settings, "LogonRequired");
		this.logonDelayFromConfig = requireBoolFromDefault(settings, "LogonDelay");
		this.logonDelaySecsFromConfig = requireIntFromDefault(settings, "LogonDelayinSecs");
		this.heartBeatRequiredFromConfig = requireBoolFromDefault(settings, "HeartBeat_Required");
		this.heartBtDelayFromConfig = requireBoolFromDefault(settings, "HeartBtDelay");
		this.heartBtDelayCountFromConfig = requireIntFromDefault(settings, "HeartBtDelayCount");
		this.heartBtDelayTimeFromConfig = requireIntFromDefault(settings, "HeartBtDelayTime");
		this.traceNotAvailableFromConfig = requireBoolFromDefault(settings, "TraceNotAvailable");
		this.traceNotAvailableIntervelFromConfig = requireIntFromDefault(settings, "TraceNotAvailableIntervel");
		this.responseMsgDelayFromConfig = requireBoolFromDefault(settings, "ResponseMsgDelay");
		this.responseMsgDelayTimeFromConfig = requireIntFromDefault(settings, "ResponseMsgDelayTime");
		this.sessionTimeoutRequiredFromConfig = readSessionTimeoutRequired(settings);
		this.sessionTimeoutSecondsFromConfig = readSessionTimeoutSeconds(settings);
		this.isLogoutRequiredAtSessionTimeoutFromConfig = readIsLogoutRequiredAtSessionTimeout(settings);
		this.logonRejectRequiredConfiguredSessions = Collections.unmodifiableSet(loadLogonRejectRequiredSessionIds(settings));
		if (!logonRejectRequiredConfiguredSessions.isEmpty()) {
			log.info("LogOnRejectRequired=Y for {} session(s): {}", logonRejectRequiredConfiguredSessions.size(), logonRejectRequiredConfiguredSessions);
		}
		if (sessionTimeoutRequiredFromConfig && sessionTimeoutSecondsFromConfig <= 0) {
			log.warn("SessionTimeoutRequired=True but SessionTimeoutSeconds is missing or <= 0 — wall-clock session timeout disabled.");
		}
		log.info("Config at startup ({}): LogonRequired={}, LogonDelay={}, LogonDelayinSecs={}, HeartBeat_Required={}, HeartBtDelay={}, HeartBtDelayTime={}, ResponseMsgDelay={}, ResponseMsgDelayTime={}, SessionTimeoutRequired={}, SessionTimeoutSeconds={}, isLogoutRequiredatSessionTimeout={}",
			simulatorRoleFromConfig, logonRequiredFromConfig, logonDelayFromConfig, logonDelaySecsFromConfig,
			heartBeatRequiredFromConfig, heartBtDelayFromConfig, heartBtDelayTimeFromConfig,
			responseMsgDelayFromConfig, responseMsgDelayTimeFromConfig,
			sessionTimeoutRequiredFromConfig, sessionTimeoutSecondsFromConfig, isLogoutRequiredAtSessionTimeoutFromConfig);
		String logonSecsLine = simulatorRoleFromConfig + " LogonDelayinSecs from config = " + logonDelaySecsFromConfig;
		log.info(logonSecsLine);
		System.out.println(logonSecsLine);
		if (configResourceName != null) {
			dumpConfigToConsoleAndLog(configResourceName);
		}
		// initializeValidOrderTypes(settings);
		// initializeMarketDataProvider(settings);
		// alwaysFillLimitOrders = settings.isSetting(ALWAYS_FILL_LIMIT_KEY) &&
		// settings.getBool(ALWAYS_FILL_LIMIT_KEY);
		//System.out.println("WizFixSimlatorVersion 5");
		//log.info("WizFixSimlatorVersion ["+5+"]");	
		
		if (traceNotAvailableFromConfig) {
			log.info("TraceNotAvailable Task started on " + new Date());
			TimerTask repeatedTask = new TimerTask() {
				public void run() {
					if (traceNotAvailable) {
						traceNotAvailable = false;
						log.info("TraceNotAvailable flag set to [false] ");
					} else {
						traceNotAvailable = true;
						log.info("TraceNotAvailable flag set to [true] ");
					}
				}
			};
			Timer timer = new Timer("Timer");
			long delay = 1000L;
			long period = 1000L * traceNotAvailableIntervelFromConfig;
			timer.scheduleAtFixedRate(repeatedTask, delay, period);
		}
	}

	/**
	 * @param messageHandler
	 */
	public WizFixApplication(Object messageHandler) {
		super(messageHandler);
		this.simulatorRoleFromConfig = "Primary";
		this.logonRequiredFromConfig = true;
		this.logonDelayFromConfig = false;
		this.logonDelaySecsFromConfig = 0;
		this.heartBeatRequiredFromConfig = true;
		this.heartBtDelayFromConfig = false;
		this.heartBtDelayCountFromConfig = 0;
		this.heartBtDelayTimeFromConfig = 0;
		this.traceNotAvailableFromConfig = false;
		this.traceNotAvailableIntervelFromConfig = 120;
		this.responseMsgDelayFromConfig = false;
		this.responseMsgDelayTimeFromConfig = 0;
		this.sessionTimeoutRequiredFromConfig = false;
		this.sessionTimeoutSecondsFromConfig = 0;
		this.isLogoutRequiredAtSessionTimeoutFromConfig = true;
		this.logonRejectRequiredConfiguredSessions = Collections.emptySet();
		this.compliancePipeline = new CompliancePipeline();
	}
	
	public void onCreate(SessionID arg0) {	}

	public void onLogon(SessionID arg0) {
		scheduleSessionWallClockTimeout(arg0);
	}

	public void onLogout(SessionID arg0) {
		cancelSessionWallClockTimeout(arg0);
	}

	/**
	 * Emit session-level Reject (MsgType 3) for the initiator's Logon. QuickFIX/J's {@link Session#send(Message)} uses
	 * {@code sendRaw}, which does not transmit 35=3 until the session is logged on, so the engine would never send this
	 * reject during failed logon. We mirror {@code Session.generateReject(Message, String)} and push the formatted string
	 * through {@code Session.send(String)}, then advance the sender sequence in the store.
	 *
	 * @return true if the FIX string was handed to the session transport
	 */
	private boolean sendSessionLevelRejectForIncomingLogon(SessionID sessionID, Message incomingLogon, String rejectText)
			throws FieldNotFound {
		Session session = Session.lookupSession(sessionID);
		if (session == null) {
			log.warn("sendSessionLevelRejectForIncomingLogon: no Session for {}", sessionID);
			return false;
		}
		String beginString = sessionID.getBeginString();
		Message reject = new DefaultMessageFactory().create(beginString, MsgType.REJECT);
		reject.reverseRoute(incomingLogon.getHeader());
		int refSeq = incomingLogon.getHeader().getInt(MsgSeqNum.FIELD);
		reject.setInt(RefSeqNum.FIELD, refSeq);
		reject.setString(RefMsgType.FIELD, MsgType.LOGON);
		reject.setInt(SessionRejectReason.FIELD, SessionRejectReason.OTHER);
		reject.setString(Text.FIELD, rejectText);

		int seq = session.getExpectedSenderNum();
		reject.getHeader().setInt(MsgSeqNum.FIELD, seq);
		reject.getHeader().setField(new SendingTime());

		final String fixString;
		try {
			fixString = reject.toString();
		} catch (Exception e) {
			log.warn("Failed to build session Reject message: {}", e.getMessage());
			return false;
		}

		try {
			Method sendString = Session.class.getDeclaredMethod("send", String.class);
			sendString.setAccessible(true);
			Object ok = sendString.invoke(session, fixString);
			if (ok instanceof Boolean && !((Boolean) ok)) {
				log.warn("Session.send(String) returned false for session-level Reject");
				return false;
			}
		} catch (Exception e) {
			log.warn("Failed to emit session Reject on wire: {}", e.getMessage());
			return false;
		}

		try {
			session.setNextSenderMsgSeqNum(seq + 1);
		} catch (Exception e) {
			log.warn("Failed to persist next sender seq after Reject: {}", e.getMessage());
		}
		log.info("Sent session-level Reject (35=3) for incoming Logon RefSeqNum={}, outgoing MsgSeqNum={}", refSeq, seq);
		return true;
	}
		
	public void fromAdmin(Message arg0, SessionID arg1) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
		String msgType = null;
		try { msgType = arg0.getHeader().getField(new MsgType()).getValue(); } catch (FieldNotFound ignored) { }

		// Reject Logon before pending-delay handling so LogOnRejectRequired=Y always applies to that session.
		if ("A".equals(msgType) && logonRequiredFromConfig && logOnRejectRequiredForSession(arg1)) {
			log.warn("{}: LogOnRejectRequired=Y — rejecting Logon for session {}", simulatorRoleFromConfig, arg1);
			String reason = "Logon rejected (LogOnRejectRequired=Y for this session)";
			boolean rejectOnWire = sendSessionLevelRejectForIncomingLogon(arg1, arg0, reason);
			// QFJ does not transmit 35=3 via Session.send(Message) before logon; we use send(String). If that failed, send Logout (35=5).
			throw new RejectLogon(reason, !rejectOnWire, -1);
		}

		if (pendingLogonResponseSessions.contains(arg1) || pendingHeartbeatResponseSessions.contains(arg1)) {
			log.info("Ignoring admin message until we send Logon/Heartbeat back to initiator: [ {} ]", arg0.toString());
			return;
		}
		if (!logonRequiredFromConfig) {
			log.info("Picked message from initiator: [ {} ]. Since we configured LogonRequired=N, not sending any response to initiator.", arg0.toString());
			if ("A".equals(msgType)) {
				String reason = "LogonRequired=N: simulator does not send Logon";
				boolean rejectOnWire = sendSessionLevelRejectForIncomingLogon(arg1, arg0, reason);
				throw new RejectLogon(reason, !rejectOnWire, -1);
			}
			return;
		}
		if ("A".equals(msgType)) {
			// Use values read from config at startup (primary or secondary)
			if (logonDelayFromConfig && logonDelaySecsFromConfig > 0) {
				log.info("LogonDelay=Y, LogonDelayinSecs={} ({} — from config loaded at startup)", logonDelaySecsFromConfig, simulatorRoleFromConfig);
					pendingLogonResponseSessions.add(arg1);
					log.warn("LogonDelay=Y: waiting {}s before accepting logon. Ignoring all messages until we send Logon back. Ensure the initiator's logon response timeout is greater than {}s.", logonDelaySecsFromConfig, logonDelaySecsFromConfig);
					try {
						Thread.sleep(logonDelaySecsFromConfig * 1000L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						pendingLogonResponseSessions.remove(arg1);
						log.warn("Logon delay interrupted");
					}
					log.info("LogonDelay: {}s elapsed, accepting logon and sending Logon to initiator for session {}", logonDelaySecsFromConfig, arg1);
			}
			// On receiving Logon: align sequence so engine sends correct 789 and we accept initiator's next message.
			// QuickFIX/J engine sends 789 = getNextTargetMsgSeqNum() + 1, then increments store. So we set store to
			// logonMsgSeqNum (the seq we just received); engine will send 789=logonMsgSeqNum+1 and incr store to that.
			try {
				int logonMsgSeqNum = arg0.getHeader().getField(new MsgSeqNum()).getValue();
				Session session = Session.lookupSession(arg1);
				if (session == null) {
					log.warn("Logon(34={}): session not found for {}; cannot set expected seq to {} (may cause 'expecting N but received {}').", logonMsgSeqNum, arg1, logonMsgSeqNum + 1, logonMsgSeqNum);
					return;
				}
				// Set next expected to the Logon's MsgSeqNum so engine sends 789 = logonMsgSeqNum+1 (never 0)
				int storeNextTarget = Math.max(1, logonMsgSeqNum);
				int nextSender = -1;
				SessionSequenceFromDB.SessionSequence dbSeq = null;

				// 1) Load shared TRACE_FIX_SESSIONS first (Primary/Secondary failover). Previously we applied initiator 789
				//    before DB and skipped the DB block when 789 set nextSender>=1 — wrong: Secondary's file store starts at 1
				//    but outgoing_seqnum must continue from the shared JDBC store after Primary handled the session.
				SessionSequenceFromDB loader = sessionSequenceFromDB;
				if (loader != null) {
					dbSeq = loader.getSessionSequence(arg1);
					if (dbSeq != null) {
						nextSender = dbSeq.outgoingSeqNum;
						log.info("Logon: DB outgoing_seqnum={} incoming_seqnum={} for {} (shared store / failover).",
								dbSeq.outgoingSeqNum, dbSeq.incomingSeqNum, arg1);
					}
				}

				// 2) Initiator may send 789 on Logon = next seq they expect from us; merge up, never down vs DB
				try {
					int initiatorExpectedFromUs = arg0.getField(new NextExpectedMsgSeqNum()).getValue();
					int hint = Math.max(1, initiatorExpectedFromUs);
					if (nextSender < 1) {
						nextSender = hint;
					} else {
						nextSender = Math.max(nextSender, hint);
					}
					log.info("Logon(789={}): merged next sender (with DB if any) = {}", initiatorExpectedFromUs, nextSender);
				} catch (FieldNotFound e) {
					// optional on Logon
				}

				if (dbSeq != null) {
					// Initiator is behind (e.g. reconnected with lower seq): align so we don't exceed their position
					if ((logonMsgSeqNum + 1) < nextSender) {
						nextSender = Math.max(1, logonMsgSeqNum + 1);
						log.info("Logon(34={}): initiator behind; aligning next sender to {} so first message to initiator is not higher (avoid 'expecting N but received 154').",
								logonMsgSeqNum, nextSender);
					}
					if (logonMsgSeqNum > dbSeq.incomingSeqNum) {
						log.info("Logon(34={}): sequence numbers were missing (DB had expected {}); gap {}..{} requested by engine via ResendRequest if configured.",
								logonMsgSeqNum, dbSeq.incomingSeqNum, dbSeq.incomingSeqNum, logonMsgSeqNum - 1);
					}
					if (dbSeq.incomingSeqNum > logonMsgSeqNum + 1) {
						log.debug("Logon(34={}): DB had expected {}; setting store so 789={} (initiator's next accepted).",
								logonMsgSeqNum, dbSeq.incomingSeqNum, logonMsgSeqNum + 1);
					}
					log.debug("Logon: DB incoming_seqnum={}, outgoing_seqnum={}, next sender after merge = {}",
							dbSeq.incomingSeqNum, dbSeq.outgoingSeqNum, nextSender);
				}

				// Start of day: no session row for current date (creation_time not today in SessionDateZone) → fresh day → first message to initiator is 34=1
				if (nextSender < 1) {
					nextSender = 1;
					log.info("Logon: fresh day (no session row in DB for current date); first message to initiator will be 34=1.");
				}
				// 3) Merge engine next sender (local file store often 1 on Secondary); take max so DB/shared state wins at failover
				int fromEngine = getNextSenderMsgSeqNumFromSession(session);
				if (fromEngine >= 1) {
					int merged = Math.max(nextSender, fromEngine);
					if (merged != nextSender) {
						log.debug("Logon: raised next sender from {} to {} (engine had higher; e.g. same-JVM reconnect).", nextSender, merged);
					}
					nextSender = merged;
				}
				// Never send lower than what initiator last said they expected (from previous Logout 58)
				Integer lastExpected = lastExpectedFromUsBySession.get(arg1);
				if (lastExpected != null && lastExpected > nextSender) {
					log.info("Logon: using last expected-from-us {} (from previous Logout 58) instead of {} so initiator does not reject.", lastExpected, nextSender);
					nextSender = lastExpected;
				}

				session.setNextTargetMsgSeqNum(storeNextTarget);
				log.debug("Logon(34={}): set store so engine sends 789={} (expect initiator next {}); store/DB updated.", logonMsgSeqNum, logonMsgSeqNum + 1, logonMsgSeqNum + 1);
				try {
					session.setNextSenderMsgSeqNum(nextSender);
					log.info("Logon: set next sender seq to {} (TRACE_FIX_SESSIONS / 789 / engine max — shared across Primary/Secondary when UseJdbcStore=Y).", nextSender);
				} catch (Exception e) {
					log.warn("Could not set next sender seq after Logon: {}", e.getMessage());
				}
			} catch (Exception e) {
				log.warn("Could not set sequence after Logon: {}", e.getMessage());
			}
		}
		log.info("Recieved Admin message from Gateway :: "+arg0.toString());
		logIncomingMessageTags(arg0);

		// On full SequenceReset (35=4): reset our max sequence to NewSeqNo (engine sets expected incoming); send next from NewSeqNo+1 (no hardcoded values).
		if ("4".equals(msgType)) {
			try {
				int newSeqNo = arg0.getField(new NewSeqNo()).getValue();
				boolean gapFill = false;
				try {
					gapFill = arg0.getField(new GapFillFlag()).getValue();
				} catch (FieldNotFound ignored) { }
				if (!gapFill) {
					Session session = Session.lookupSession(arg1);
					if (session != null) {
						int nextSender = Math.max(1, newSeqNo + 1);
						try {
							session.setNextSenderMsgSeqNum(nextSender);
							log.info("SequenceReset(36={}): set next outgoing seq to {} (NewSeqNo+1, never 0); DB updated via store.", newSeqNo, nextSender);
						} catch (Exception e) {
							log.warn("Failed to set next sender seq to {} after SequenceReset(36={}): {}", nextSender, newSeqNo, e.getMessage());
						}
					}
				}
			} catch (FieldNotFound e) {
				log.warn("SequenceReset received but NewSeqNo(36) not found: {}", e.getMessage());
			}
		}

		// On Logout (35=5): if initiator says "expecting N but received M", set session next sender to N so store/DB has N and we send N (this response and next connection).
		if ("5".equals(msgType)) {
			boolean didExpectingFix = false;
			try {
				String text58 = arg0.getField(new Text()).getValue();
				if (text58 != null && text58.matches(".*expecting\\s+(\\d+)\\s+but received\\s+\\d+.*")) {
					java.util.regex.Matcher m = java.util.regex.Pattern.compile("expecting\\s+(\\d+)\\s+but received\\s+\\d+").matcher(text58);
					if (m.find()) {
						int expectedFromUs = Integer.parseInt(m.group(1));
						if (expectedFromUs >= 1) {
							didExpectingFix = true;
							lastExpectedFromUsBySession.put(arg1, expectedFromUs);
							Session session = Session.lookupSession(arg1);
							if (session != null) {
								try {
									session.setNextSenderMsgSeqNum(expectedFromUs);
									log.info("Logout(58): auto-fix — set next sender to {} so initiator receives expected sequence (store/DB updated).", expectedFromUs);
								} catch (Exception e) {
									log.warn("Could not set next sender to {}: {}", expectedFromUs, e.getMessage());
								}
							}
							SessionSequenceFromDB loader = sessionSequenceFromDB;
							if (loader != null) {
								loader.updateOutgoingSeqNum(arg1, expectedFromUs);
							}
						}
					}
				}
			} catch (Exception e) {
				log.debug("Could not parse Logout 58 for auto-fix: {}", e.getMessage());
			}
			// When initiator sends Logout (any), persist next sender = current+1 so next Logon uses 34=(N+1) even with SendLogout_at_Shutdown=N
			if (!didExpectingFix && sessionSequenceFromDB != null) {
				Session session = Session.lookupSession(arg1);
				int nextToPersist = -1;
				if (session != null) {
					int cur = getNextSenderMsgSeqNumFromSession(session);
					if (cur >= 1) nextToPersist = cur + 1;
				}
				if (nextToPersist < 1) {
					SessionSequenceFromDB.SessionSequence dbSeq = sessionSequenceFromDB.getSessionSequence(arg1);
					if (dbSeq != null) nextToPersist = dbSeq.outgoingSeqNum + 1;
				}
				if (nextToPersist >= 1) {
					sessionSequenceFromDB.updateOutgoingSeqNum(arg1, nextToPersist);
					log.debug("Logout received: persisted next sender {} for {} so next Logon uses 34={}.", nextToPersist, arg1, nextToPersist);
				}
			}
		}
	}
	
	public void toAdmin(Message arg0, SessionID arg1) {
		try {
			String msgType = arg0.getHeader().getField(new MsgType()).getValue();
			if ("A".equals(msgType)) {
				pendingLogonResponseSessions.remove(arg1);
				if (!logonRequiredFromConfig) {
					log.info("LogonRequired=N: not sending any response (Logon) to initiator for session {}", arg1);
					throwDoNotSend();
				}
			}
			if ("0".equals(msgType)) {
				pendingHeartbeatResponseSessions.remove(arg1);
				if (!heartBeatRequiredFromConfig) {
					log.info("HeartBeat_Required=N: not sending Heartbeat to initiator for session {}", arg1);
					throwDoNotSend();
				}
				if (heartBeatRequiredFromConfig && heartBtDelayFromConfig) {
					int secs = heartBtDelayTimeFromConfig;
					if (secs > 0) {
						pendingHeartbeatResponseSessions.add(arg1);
						log.warn("HeartBtDelay=Y: waiting {}s before sending Heartbeat. App messages (trades) are queued and will be reported after delay.", secs);
						try {
							Thread.sleep(secs * 1000L);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							pendingHeartbeatResponseSessions.remove(arg1);
							log.warn("Heartbeat delay interrupted");
						}
						pendingHeartbeatResponseSessions.remove(arg1);
						log.info("HeartBtDelay: {}s elapsed, sending Heartbeat to initiator for session {}", secs, arg1);
						// Run queue processing after this callback returns so Heartbeat is sent first, then SPEN (avoids sending from inside toAdmin).
						final SessionID sessionIdForQueue = arg1;
						heartbeatDelayQueueExecutor.execute(() -> processQueuedAppMessagesAfterHeartbeatDelay(sessionIdForQueue));
					}
				}
			}
			// When we (simulator) send a full SequenceReset (35=4): set our next sender to NewSeqNo+1 so the next message we send uses that value (no hardcoded values).
			if ("4".equals(msgType)) {
				try {
					int newSeqNo = arg0.getField(new NewSeqNo()).getValue();
					boolean gapFill = false;
					try {
						gapFill = arg0.getField(new GapFillFlag()).getValue();
					} catch (FieldNotFound ignored) { }
					if (!gapFill) {
						Session session = Session.lookupSession(arg1);
						if (session != null) {
							int nextSender = Math.max(1, newSeqNo + 1);
							try {
								session.setNextSenderMsgSeqNum(nextSender);
								log.info("Sending SequenceReset(36={}): set next outgoing seq to {} (NewSeqNo+1, never 0); DB updated via store.", newSeqNo, nextSender);
							} catch (Exception e) {
								log.warn("Failed to set next sender seq to {} when sending SequenceReset(36={}): {}", nextSender, newSeqNo, e.getMessage());
							}
						}
					}
				} catch (FieldNotFound e) {
					log.warn("Sending SequenceReset but NewSeqNo(36) not found: {}", e.getMessage());
				}
			}
			crack(arg0, arg1);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	/** Throws DoNotSend without declaring it (Application.toAdmin does not declare throws DoNotSend). */
	@SuppressWarnings("unchecked")
	private static void throwDoNotSend() {
		throw (RuntimeException) (Throwable) new DoNotSend();
	}

	private boolean getBoolSetting(SessionID sessionID, String key, boolean defaultValue) {
		try {
			if (sessionID != null && settings.isSetting(sessionID, key))
				return settings.getBool(sessionID, key);
			return settings.getBool(key);
		} catch (Exception ignored) {
			return defaultValue;
		}
	}

	/**
	 * Collect {@code [session]} blocks with LogOnRejectRequired=Y at startup (reliable config keys on parsed SessionIDs).
	 */
	private static Set<SessionID> loadLogonRejectRequiredSessionIds(SessionSettings settings) throws ConfigError, FieldConvertError {
		Set<SessionID> out = new HashSet<>();
		Iterator<SessionID> it = settings.sectionIterator();
		while (it.hasNext()) {
			SessionID sid = it.next();
			try {
				if (settings.isSetting(sid, "LogOnRejectRequired") && settings.getBool(sid, "LogOnRejectRequired"))
					out.add(sid);
			} catch (Exception e) {
				log.debug("LogOnRejectRequired skip {}: {}", sid, e.getMessage());
			}
		}
		return out;
	}

	private static String normSessionPart(String s) {
		if (s == null || s.isEmpty())
			return "";
		if (SessionID.NOT_SET.equals(s))
			return "";
		return s;
	}

	/** True if both IDs identify the same FIX session (handles NOT_SET vs empty and field alignment). */
	private static boolean sessionIdsFixEquivalent(SessionID a, SessionID b) {
		if (a == null || b == null)
			return false;
		return Objects.equals(a.getBeginString(), b.getBeginString())
				&& Objects.equals(normSessionPart(a.getSenderCompID()), normSessionPart(b.getSenderCompID()))
				&& Objects.equals(normSessionPart(a.getTargetCompID()), normSessionPart(b.getTargetCompID()))
				&& Objects.equals(normSessionPart(a.getSenderSubID()), normSessionPart(b.getSenderSubID()))
				&& Objects.equals(normSessionPart(a.getTargetSubID()), normSessionPart(b.getTargetSubID()))
				&& Objects.equals(normSessionPart(a.getSenderLocationID()), normSessionPart(b.getSenderLocationID()))
				&& Objects.equals(normSessionPart(a.getTargetLocationID()), normSessionPart(b.getTargetLocationID()))
				&& Objects.equals(normSessionPart(a.getSessionQualifier()), normSessionPart(b.getSessionQualifier()));
	}

	/** Swap sender/target halves (some stacks use initiator-first SessionID vs acceptor config order). */
	private static SessionID swapSessionSenderTarget(SessionID sid) {
		return new SessionID(sid.getBeginString(),
				sid.getTargetCompID(), sid.getTargetSubID(), sid.getTargetLocationID(),
				sid.getSenderCompID(), sid.getSenderSubID(), sid.getSenderLocationID(),
				sid.getSessionQualifier());
	}

	private static boolean matchesLogonRejectConfigured(SessionID runtime, Set<SessionID> configured) {
		if (runtime == null || configured.isEmpty())
			return false;
		if (configured.contains(runtime))
			return true;
		for (SessionID cfg : configured) {
			if (sessionIdsFixEquivalent(cfg, runtime))
				return true;
		}
		SessionID swapped = swapSessionSenderTarget(runtime);
		if (configured.contains(swapped))
			return true;
		for (SessionID cfg : configured) {
			if (sessionIdsFixEquivalent(cfg, swapped))
				return true;
		}
		return false;
	}

	/**
	 * Per-[session] LogOnRejectRequired=Y: reject incoming Logon for that session only. Uses startup session list plus
	 * normalized SessionID match so lookup works when runtime SessionID is not the same object as the config section key.
	 */
	private boolean logOnRejectRequiredForSession(SessionID sid) {
		if (sid == null)
			return false;
		if (matchesLogonRejectConfigured(sid, logonRejectRequiredConfiguredSessions))
			return true;
		if (settings == null)
			return false;
		try {
			if (settings.isSetting(sid, "LogOnRejectRequired"))
				return settings.getBool(sid, "LogOnRejectRequired");
		} catch (Exception e) {
			log.trace("LogOnRejectRequired: {}", e.getMessage());
		}
		return false;
	}

	private int getIntSetting(SessionID sessionID, String key, int defaultValue) {
		try {
			if (sessionID != null && settings.isSetting(sessionID, key))
				return (int) settings.getLong(sessionID, key);
			return (int) settings.getLong(key);
		} catch (Exception ignored) {
			return defaultValue;
		}
	}

	/** Require key in config; throw ConfigError if missing. No defaults. */
	private static String requireStringFromDefault(SessionSettings s, String key) throws ConfigError {
		if (s == null || !s.isSetting(key))
			throw new ConfigError("Missing required key in config: " + key);
		return s.getString(key);
	}
	private static boolean requireBoolFromDefault(SessionSettings s, String key) throws ConfigError, FieldConvertError {
		if (s == null || !s.isSetting(key))
			throw new ConfigError("Missing required key in config: " + key);
		return s.getBool(key);
	}
	private static int requireIntFromDefault(SessionSettings s, String key) throws ConfigError, FieldConvertError {
		if (s == null || !s.isSetting(key))
			throw new ConfigError("Missing required key in config: " + key);
		return (int) s.getLong(key);
	}

	/** SessionTimeoutRequired: True/False (Boolean.parseBoolean). Optional in [default]; missing = false. */
	private static boolean readSessionTimeoutRequired(SessionSettings s) {
		try {
			if (s == null || !s.isSetting("SessionTimeoutRequired")) return false;
			return Boolean.parseBoolean(s.getString("SessionTimeoutRequired").trim());
		} catch (Exception e) {
			return false;
		}
	}

	/** SessionTimeoutSeconds: optional in [default]; missing or invalid = 0. */
	private static int readSessionTimeoutSeconds(SessionSettings s) {
		try {
			if (s == null || !s.isSetting("SessionTimeoutSeconds")) return 0;
			return Integer.parseInt(s.getString("SessionTimeoutSeconds").trim());
		} catch (Exception e) {
			return 0;
		}
	}

	/** isLogoutRequiredatSessionTimeout: True/False. Optional; missing = True (send Logout on timeout). */
	private static boolean readIsLogoutRequiredAtSessionTimeout(SessionSettings s) {
		try {
			if (s == null || !s.isSetting("isLogoutRequiredatSessionTimeout")) return true;
			return Boolean.parseBoolean(s.getString("isLogoutRequiredatSessionTimeout").trim());
		} catch (Exception e) {
			return true;
		}
	}

	private boolean sessionTimeoutRequiredEffective(SessionID sid) {
		try {
			if (sid != null && settings.isSetting(sid, "SessionTimeoutRequired"))
				return Boolean.parseBoolean(settings.getString(sid, "SessionTimeoutRequired").trim());
		} catch (Exception ignored) { }
		return sessionTimeoutRequiredFromConfig;
	}

	private int sessionTimeoutSecondsEffective(SessionID sid) {
		try {
			if (sid != null && settings.isSetting(sid, "SessionTimeoutSeconds"))
				return Math.max(0, Integer.parseInt(settings.getString(sid, "SessionTimeoutSeconds").trim()));
		} catch (Exception ignored) { }
		return Math.max(0, sessionTimeoutSecondsFromConfig);
	}

	private boolean isLogoutRequiredAtSessionTimeoutEffective(SessionID sid) {
		try {
			if (sid != null && settings.isSetting(sid, "isLogoutRequiredatSessionTimeout"))
				return Boolean.parseBoolean(settings.getString(sid, "isLogoutRequiredatSessionTimeout").trim());
		} catch (Exception ignored) { }
		return isLogoutRequiredAtSessionTimeoutFromConfig;
	}

	private ScheduledExecutorService getOrCreateSessionTimeoutScheduler() {
		if (sessionTimeoutScheduler != null) return sessionTimeoutScheduler;
		synchronized (this) {
			if (sessionTimeoutScheduler == null) {
				sessionTimeoutScheduler = Executors.newScheduledThreadPool(1, r -> {
					Thread t = new Thread(r, "SessionWallClockTimeout-" + simulatorRoleFromConfig);
					t.setDaemon(true);
					return t;
				});
			}
			return sessionTimeoutScheduler;
		}
	}

	/** After successful FIX Logon: schedule wall-clock timeout for this SessionID (ignores traffic). Logout only if isLogoutRequiredatSessionTimeout=True. */
	private void scheduleSessionWallClockTimeout(SessionID sid) {
		cancelSessionWallClockTimeout(sid);
		boolean req = sessionTimeoutRequiredEffective(sid);
		int sec = sessionTimeoutSecondsEffective(sid);
		if (!req || sec <= 0) return;
		ScheduledExecutorService sched = getOrCreateSessionTimeoutScheduler();
		final boolean sendLogoutOnTimeout = isLogoutRequiredAtSessionTimeoutEffective(sid);
		final ScheduledFuture<?>[] taskRef = new ScheduledFuture<?>[1];
		taskRef[0] = sched.schedule(() -> {
			try {
				Session s = Session.lookupSession(sid);
				if (s != null && s.isLoggedOn()) {
					if (sendLogoutOnTimeout) {
						log.info("{}: wall-clock session timeout ({}s) — sending Logout for {}", simulatorRoleFromConfig, sec, sid);
						s.logout("Session timeout (" + sec + "s wall clock)");
					} else {
						log.info("{}: wall-clock session timeout ({}s) for {} — isLogoutRequiredatSessionTimeout=False; not sending Logout",
							simulatorRoleFromConfig, sec, sid);
					}
				}
			} catch (Exception e) {
				log.warn("Session wall-clock timeout failed for {}: {}", sid, e.getMessage());
			} finally {
				sessionTimeoutTasks.remove(sid, taskRef[0]);
			}
		}, sec, TimeUnit.SECONDS);
		sessionTimeoutTasks.put(sid, taskRef[0]);
		log.info("{}: wall-clock session timeout scheduled: {}s for {} (isLogoutRequiredatSessionTimeout={})",
			simulatorRoleFromConfig, sec, sid, sendLogoutOnTimeout);
	}

	private void cancelSessionWallClockTimeout(SessionID sid) {
		ScheduledFuture<?> f = sessionTimeoutTasks.remove(sid);
		if (f != null) f.cancel(false);
	}

	/** Print whole config file to console and logs. Prefer file in current directory (same as load). */
	private void dumpConfigToConsoleAndLog(String configResourceName) {
		String header = "========== Loaded config: " + configResourceName + " ==========";
		log.info(header);
		System.out.println(header);
		InputStream in = null;
		File file = new File(configResourceName);
		if (file.isFile()) {
			try {
				in = new FileInputStream(file);
			} catch (Exception e) {
				log.warn("Could not open config file: {}", e.getMessage());
			}
		}
		if (in == null)
			in = WizFixApplication.class.getResourceAsStream(configResourceName);
		if (in == null) {
			log.warn("Config not found: {}", configResourceName);
			System.out.println("Config not found: " + configResourceName);
			return;
		}
		try (InputStream stream = in) {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					log.info("  {}", line);
					System.out.println("  " + line);
				}
			}
		} catch (Exception e) {
			log.warn("Could not dump config {}: {}", configResourceName, e.getMessage());
			System.out.println("Could not dump config: " + e.getMessage());
		}
		log.info("========== End of config: {} ==========", configResourceName);
		System.out.println("========== End of config: " + configResourceName + " ==========");
	}

	public void fromApp(Message arg0, SessionID arg1) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
		if (pendingLogonResponseSessions.contains(arg1)) {
			log.info("Ignoring app message until we send Logon back to initiator: [ {} ]", arg0.toString());
			return;
		}
		// During HeartBtDelay: queue app messages; they will be processed and reported after the delay completes.
		if (pendingHeartbeatResponseSessions.contains(arg1)) {
			heartbeatDelayAppMessageQueue.computeIfAbsent(arg1, k -> new ConcurrentLinkedQueue<>()).add(arg0.toString());
			log.info("HeartBtDelay: queued app message (will be reported after delay): [ {} ]", arg0.toString());
			return;
		}
		if (!logonRequiredFromConfig) {
			log.info("Picked message from initiator: [ {} ]. Since we configured LogonRequired=N, not sending any response to initiator.", arg0.toString());
			return;
		}
		log.info("Request message received from Gateway :: [ "+ arg0.toString() +" ]");
		logIncomingMessageTags(arg0);
		if (compliancePipeline.processIncoming(arg0, arg1)) return;
		crack(arg0, arg1);
	}

	/** Process all app messages queued during HeartBtDelay; send trade responses (e.g. SPEN) to initiator after delay completed. */
	private void processQueuedAppMessagesAfterHeartbeatDelay(SessionID sessionId) {
		ConcurrentLinkedQueue<String> queue = heartbeatDelayAppMessageQueue.remove(sessionId);
		if (queue == null || queue.isEmpty()) {
			log.debug("HeartBtDelay completed: no queued app messages for session {} (queue empty or not present).", sessionId);
			return;
		}
		int count = queue.size();
		log.info("HeartBtDelay completed: processing {} queued app message(s) for session {} (sending SPEN/response after Heartbeat).", count, sessionId);
		String raw;
		int processed = 0;
		int skipped = 0;
		while ((raw = queue.poll()) != null) {
			try {
				// Parse with MessageFactory so AE becomes TradeCaptureReport; compliance and crack then build SPEN (generic Message breaks SpecValidationEngine cast and crack dispatch).
				Message msg = MessageUtils.parse(new DefaultMessageFactory(), null, raw);
				log.info("Request message (after delay) received from Gateway :: [ {} ]", raw);
				logIncomingMessageTags(msg);
				if (compliancePipeline.processIncoming(msg, sessionId)) {
					skipped++;
					log.info("HeartBtDelay: skipped queued message (compliance handled or rejected); no SPEN sent for this one.");
					continue;
				}
				crack(msg, sessionId);
				processed++;
			} catch (Exception e) {
				// Fallback: parse as generic Message so at least crack may dispatch (e.g. if MessageUtils fails).
				try {
					Message msg = new Message(raw);
					log.info("Request message (after delay) received from Gateway [fallback] :: [ {} ]", raw);
					logIncomingMessageTags(msg);
					if (compliancePipeline.processIncoming(msg, sessionId)) {
						skipped++;
						continue;
					}
					crack(msg, sessionId);
					processed++;
				} catch (Exception e2) {
					log.warn("Failed to process queued app message after HeartBtDelay: {} — raw length={} preview={}", e.getMessage(), raw != null ? raw.length() : 0, raw != null && raw.length() > 200 ? raw.substring(0, 200) + "..." : raw, e);
				}
			}
		}
		log.info("HeartBtDelay queue done for {}: processed={}, skipped={}.", sessionId, processed, skipped);
	}

	public void toApp(Message msg, SessionID arg1) throws DoNotSend {
		log.info("Response message sending :: [ "+msg.toString() +" ]");
		/**TradeCaptureReport resTrdCapRpt = (TradeCaptureReport) msg;
		try {
		quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
		
		log.info("NoSide :: ["+resTrdCapRpt.getNoSides().getValue()+"]");
		for(int grpIndex = 1; grpIndex<= resTrdCapRpt.getNoSides().getValue(); grpIndex += 1) {
			// get 'grpIndex' sidesGroup
			resTrdCapRpt.getGroup(grpIndex, sidesGroup);
			
			log.info("Side Value ["+sidesGroup.getSide().getValue()+"]");
			
			// NoPartyIds subgroup
			quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs partyIdsGroup = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();
			
			log.info("NoPartyIDs :: ["+sidesGroup.getParties().getNoPartyIDs().getValue()+"]");
			for(int partyIndex =1;  partyIndex <= sidesGroup.getParties().getNoPartyIDs().getValue(); partyIndex +=1 ) {
				
				// get 'partyIndex' partyIdsGroup from sidesGroup
				sidesGroup.getGroup(partyIndex, partyIdsGroup);					
				log.info("Before :: PartyID ["+partyIdsGroup.getPartyID().getValue()+"]   PartyRole ["+partyIdsGroup.getPartyRole().getValue()+"]");
				
				if(17 == partyIdsGroup.getPartyRole().getValue()) {
				//	sidesGroup.removeGroup(partyIdsGroup);						
				//	partyIdsGroup.set(new PartyID("JPMS"));						
					log.info("After :: PartyID ["+partyIdsGroup.getPartyID().getValue()+"]   PartyRole ["+partyIdsGroup.getPartyRole().getValue()+"]");
					//sidesGroup.addGroup(partyIdsGroup);	
					break;
				}					
			}
		}
		}
		catch(Exception e) {
			
		}
		**/
	}
			
	// TradeReportTransType : 0=new, 1=cancel, 2=Replace, ----------IN
	public void onMessage(TradeCaptureReport msg, SessionID sessionID) throws FieldNotFound, SessionNotFound, ConfigError, FieldConvertError, InterruptedException {
		
		if (responseMsgDelayFromConfig && responseMsgDelayTimeFromConfig > 0) {
			System.out.println();
			System.out.println("Response processing delay time is :: " + responseMsgDelayTimeFromConfig);
			try {
				Thread.sleep(responseMsgDelayTimeFromConfig * 1000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			onMessageHold(msg, sessionID);
		} else {
			onMessageHold(msg, sessionID);
		}
	}
	
	public synchronized void onMessageHold(TradeCaptureReport msg, SessionID sessionID) throws FieldNotFound, SessionNotFound, ConfigError, FieldConvertError {
		
		//log.info("message rescieved :: "+msg);	
		//REJECT processing ... send trade with price = 555 .. will send reject for that trades
		if(traceNotAvailable) {
			processingREJ(msg, sessionID, msg.getHeader().getField(new TargetSubID()).getValue());
		}else if(555 == msg.getLastPx().getValue()) {
			processingREJ(msg, sessionID, msg.getHeader().getField(new TargetSubID()).getValue());			
		}else if(666 == msg.getLastPx().getValue()) {
			processingREJ(msg, sessionID, msg.getHeader().getField(new TargetSubID()).getValue());			
		}else {
			//NEW
			if (0 == msg.getTradeReportTransType().getValue()) {
				processingTradeEntry(msg, sessionID, msg.getHeader().getField(new TargetSubID()).getValue());
				
				/*	if("SP".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeEntry(msg, sessionID, "SPEN");
				}else if("CA".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeEntry(msg, sessionID, "CAEN");
				}else if("TS".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeEntry(msg, sessionID, "TSEN");
				}	*/				
				
			}//CANCEL 
			else if (1 == msg.getTradeReportTransType().getValue()) {
				
				if(!msg.isSetField(22009)) {
					processingTradeCancel(msg, sessionID, msg.getHeader().getField(new TargetSubID()).getValue());
				}else {
					processingTradeReversal(msg, sessionID, msg.getHeader().getField(new TargetSubID()).getValue());
				}
				
				/*if("SP".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					if(!msg.isSetField(22009)) {
						processingTradeCancel(msg, sessionID, "SPCX");
					}else {
						processingTradeReversal(msg, sessionID, "SPHX");
					}
				}else if("CA".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					if(!msg.isSetField(22009)) {
						processingTradeCancel(msg, sessionID, "CACX");
					}else {
						processingTradeReversal(msg, sessionID, "CAHX");
					}
				}else if("TS".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeCancel(msg, sessionID, "TSCX");
				}
				*/
			}//MODIFY
			else if (2 == msg.getTradeReportTransType().getValue()) {
				processingTradeCorrection(msg, sessionID, msg.getHeader().getField(new TargetSubID()).getValue()); 
				/*
				 * if("SP".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) { processingTradeCorrection(msg, sessionID, "SPCR"); }else if("CA".equalsIgnoreCase( msg.getHeader().getField(new
				 * TargetSubID()).getValue())) { processingTradeCorrection(msg, sessionID, "CACR"); }else if("TS".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
				 * processingTradeCorrection(msg, sessionID, "TSCR"); }
				 */
				
			} else if (4 == msg.getTradeReportTransType().getValue()) {
				processingTradeReversal(msg, sessionID, "TS");
			}
		}
	}
	
	// REJECT
	private void processingREJ(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType) throws FieldNotFound, SessionNotFound {
		
		TradeCaptureReportAck resTrdCapRpt = new TradeCaptureReportAck();
		
		if("TS".equalsIgnoreCase(securityType)) {
			resTrdCapRpt.setField(new TradeReportID(getTrdRptID()));
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue()));				
		}else {
			resTrdCapRpt.setField(new TradeReportID(reqTrdCapRpt.getTradeReportID().getValue()));
			//resTrdCapRpt.setField(new TradeReportID(getTrdRptID()));
			//resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue()));			
		}		
		
		//resTrdCapRpt.setField(new TradeReportTransType(TradeReportTransType.NEW));
		resTrdCapRpt.setField(new TradeReportTransType(reqTrdCapRpt.getTradeReportTransType().getValue()));
		
		//resTrdCapRpt.setField(new TradeReportType(TradeReportType.SUBMIT));
		resTrdCapRpt.setField(new TradeReportType(reqTrdCapRpt.getTradeReportType().getValue()));
		
		resTrdCapRpt.setField(new ExecType(ExecType.REJECTED));
		resTrdCapRpt.setField(new TradeRptStatus(TradeRptStatus.REJECTED));
		
		/*
		 * Instrument instrument = new Instrument(); instrument.set(new
		 * SecurityID(reqTrdCapRpt.getSecurityID().getValue())); instrument.set(new
		 * SecurityIDSource(reqTrdCapRpt.getSecurityIDSource().getValue()));
		 * resTrdCapRpt.set(instrument);
		 */
		resTrdCapRpt.set(reqTrdCapRpt.getInstrument());

		//572 -> 571
		// 571 
		//resTrdCapRpt.setField(new TradeReportRejectReason(TradeReportRejectReason.INVALID_PARTY_ONFORMATION));
		
		if(traceNotAvailable) {
			resTrdCapRpt.setField(new TradeReportRejectReason(4069));
			resTrdCapRpt.setField(new Text("!REJ - TRACE TEMPORARILY NOT AVAILABLE"));			
			log.info("TraceNotAvailable ... REJ processed...");
		}else {
			resTrdCapRpt.setField(new TradeReportRejectReason(4005));
			resTrdCapRpt.setField(new Text("!REJ - INVALID PRICE"));				
		}

		resTrdCapRpt.reverseRoute(reqTrdCapRpt.getHeader());
		
		resTrdCapRpt.getHeader().setField(new MsgType("AR"));

		GatewayAllocQtyEcho.copyTag80IfPresent(reqTrdCapRpt, resTrdCapRpt);
		Session.sendToTarget(resTrdCapRpt, sessionID);
	}
	
	/** On outgoing AE to initiator: remove from message BODY only tags that must not appear at body level.
	 * 37, 54, 44, 447, 448, 452, 453, 58, 523, 528, 802, 803 only valid inside NoSides groups; initiator rejects them at body (e.g. "Tag not defined, field=58"). */
	private void removeOrderIDFromTradeCaptureReport(TradeCaptureReport msg) {
		try { msg.removeField(37); } catch (Exception e) { log.trace("Remove 37 from body: {}", e.getMessage()); }
		try { msg.removeField(54); } catch (Exception e) { log.trace("Remove 54 from body: {}", e.getMessage()); }
		try { msg.removeField(44); } catch (Exception e) { log.trace("Remove 44 from body: {}", e.getMessage()); }
		try { msg.removeField(447); } catch (Exception e) { log.trace("Remove 447 from body: {}", e.getMessage()); }
		try { msg.removeField(448); } catch (Exception e) { log.trace("Remove 448 from body: {}", e.getMessage()); }
		try { msg.removeField(452); } catch (Exception e) { log.trace("Remove 452 from body: {}", e.getMessage()); }
		try { msg.removeField(453); } catch (Exception e) { log.trace("Remove 453 from body: {}", e.getMessage()); }
		try { msg.removeField(58); } catch (Exception e) { log.trace("Remove 58 from body: {}", e.getMessage()); }
		try { msg.removeField(523); } catch (Exception e) { log.trace("Remove 523 from body: {}", e.getMessage()); }
		try { msg.removeField(528); } catch (Exception e) { log.trace("Remove 528 from body: {}", e.getMessage()); }
		try { msg.removeField(802); } catch (Exception e) { log.trace("Remove 802 from body: {}", e.getMessage()); }
		try { msg.removeField(803); } catch (Exception e) { log.trace("Remove 803 from body: {}", e.getMessage()); }
		try { msg.removeField(583); } catch (Exception e) { log.trace("Remove 583 from body: {}", e.getMessage()); }
		// Remove 44 from each NoSides group (447 stays in NoPartyIDs — we set it in buildSidesGroup)
		try {
			int n = msg.getNoSides().getValue();
			for (int i = 1; i <= n; i++) {
				quickfix.fix44.TradeCaptureReport.NoSides grp = new quickfix.fix44.TradeCaptureReport.NoSides();
				msg.getGroup(i, grp);
				try {
					if (grp.isSetField(44)) {
						grp.removeField(44);
						msg.replaceGroup(i, grp);
					}
				} catch (Exception e) { log.trace("Remove 44 from group {}: {}", i, e.getMessage()); }
			}
		} catch (Exception e) { log.trace("NoSides iteration: {}", e.getMessage()); }
	}

	// NEW
	private void processingTradeEntry(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType) {

		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();

		try {
			resTrdCapRpt = reqTrdCapRpt;
			
			if(999 == reqTrdCapRpt.getLastPx().getValue()) {
				log.info("*****    752 tag is not building ....");
				//resTrdCapRpt.setField(new TradeReportRefID()); // TradeReportRefID
			}else {
				resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID
			}
			
			resTrdCapRpt.setField(new MessageEventSource(securityType+"EN")); // MessageEventSource
			resTrdCapRpt.setField(new TradeReportID(getTrdRptID())); // TradeReportID
			resTrdCapRpt.setField(new StringField(22011, getControlDate()));// ControlDate
			resTrdCapRpt.setField(new TradeID(getFinraControlNo(securityType)));// TradeID

			Instrument instrument = new Instrument();
			resTrdCapRpt.get(instrument);

			instrument.set(new NoSecurityAltID(1)); 
			TradeCaptureReport.NoSecurityAltID altIDGrp = new TradeCaptureReport.NoSecurityAltID();
			altIDGrp.set(new SecurityAltIDSource(FinraSecurityIDSource.SYMBOL));
			altIDGrp.set(new SecurityAltID("Need2Generate"));
			instrument.addGroup(altIDGrp);
			resTrdCapRpt.set(instrument);

			resTrdCapRpt.setField(new TradeModifier3(TradeModifier3.T));// TradeModifier3
			
		//	resTrdCapRpt.setField(new CopyMsgIndicator(false));
			
			resTrdCapRpt.reverseRoute(reqTrdCapRpt.getHeader());
			// Outgoing AE pipeline: body cleanup (37,54 only in groups), sides with 54/37, required FIX tags
			removeOrderIDFromTradeCaptureReport(resTrdCapRpt);
			ensureResponseHasSides(resTrdCapRpt);
			ensureMandatoryFieldsForAE(resTrdCapRpt);
			ensureExpectedFieldsForInitiator(resTrdCapRpt);
			ensureDefaultsForENResponse(resTrdCapRpt);    // fill blank/null body fields with FIX defaults for EN
			ensureENHasTwoSidesWithStructure(resTrdCapRpt); // CAEN/SPEN/TSEN: 552=2, first side 802/523/803/528/58, second BCAP/17
			removeTagsNotExpectedForEN(resTrdCapRpt);   // strip 939, 22002 so format matches valid initiator sample
			GatewayAllocQtyEcho.copyTag80IfPresent(reqTrdCapRpt, resTrdCapRpt);
			Session.sendToTarget(resTrdCapRpt, sessionID);
			compliancePipeline.getLifecycleEngine().markAccepted(reqTrdCapRpt.getTradeReportID().getValue(), sessionID);

			if(777 == reqTrdCapRpt.getLastPx().getValue()) {
				log.info("*****    Processing NEW ALLEGE request....");				
				processingAllege(resTrdCapRpt, sessionID, securityType, "SPAL");				
			}
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
			log.error("Error ::",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
		
	// MODIFICATION
	private void processingTradeCorrection(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType) {
		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();

		try {
			resTrdCapRpt = reqTrdCapRpt;
			
			String originalCtrlDate = reqTrdCapRpt.getField(new StringField(22011)).getValue();			
			resTrdCapRpt.setField(new StringField(22012, originalCtrlDate));// originalCtrlDate
			
			String originalTradeID = reqTrdCapRpt.getField(new StringField(1003)).getValue();
			resTrdCapRpt.setField(new StringField(1126, originalTradeID));// originalTradeID
			
			//resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID
			if(999 == reqTrdCapRpt.getLastPx().getValue()) {
				log.info("*****    752 tag is not building ....");
				//resTrdCapRpt.setField(new TradeReportRefID()); // TradeReportRefID
			}else {
				resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID
			}

			resTrdCapRpt.setField(new MessageEventSource(securityType+"CR")); // MessageEventSource
			resTrdCapRpt.setField(new TradeReportID(getTrdRptID())); // TradeReportID
			resTrdCapRpt.setField(new StringField(22011, getControlDate()));// ControlDate
			resTrdCapRpt.setField(new TradeID(getFinraControlNo(securityType)));// TradeID			
			
			Instrument instrument = new Instrument();
			resTrdCapRpt.get(instrument);

			instrument.set(new NoSecurityAltID(1)); //
			TradeCaptureReport.NoSecurityAltID altIDGrp = new TradeCaptureReport.NoSecurityAltID();
			altIDGrp.set(new SecurityAltIDSource(FinraSecurityIDSource.SYMBOL));
			altIDGrp.set(new SecurityAltID("Need2Generate"));
			instrument.addGroup(altIDGrp);
			resTrdCapRpt.set(instrument);

			resTrdCapRpt.setField(new TradeModifier3(TradeModifier3.T));// TradeModifier3

			resTrdCapRpt.reverseRoute(reqTrdCapRpt.getHeader());
			removeOrderIDFromTradeCaptureReport(resTrdCapRpt);
			ensureResponseHasSides(resTrdCapRpt);
			ensureMandatoryFieldsForAE(resTrdCapRpt);
			ensureExpectedFieldsForInitiator(resTrdCapRpt);
			ensureDefaultsForENResponse(resTrdCapRpt);    // fill blank/null body fields with FIX defaults for CR
			ensureENHasTwoSidesWithStructure(resTrdCapRpt); // CACR/SPCR/TSCR: 552=2, same two sides as valid format
			removeTagsNotExpectedForEN(resTrdCapRpt);     // strip 939, 22002 for CR
			GatewayAllocQtyEcho.copyTag80IfPresent(reqTrdCapRpt, resTrdCapRpt);
			Session.sendToTarget(resTrdCapRpt, sessionID);
			
			if(777 == reqTrdCapRpt.getLastPx().getValue()) {
				log.info("Processing ALLEGE for Trade Correction....");
				processingAllege(resTrdCapRpt, sessionID, securityType, securityType+"CR");				
			}
			
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
			log.error("Error ::",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	// CANCEL
	private void processingTradeCancel(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType)
			throws FieldNotFound, SessionNotFound {
		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();

		try {
			resTrdCapRpt = reqTrdCapRpt;
			
			//resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID
			if(999 == reqTrdCapRpt.getLastPx().getValue()) {
				log.info("*****    752 tag is not building ....");
				//resTrdCapRpt.setField(new TradeReportRefID()); // TradeReportRefID
			}else {
				resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID
			}

			resTrdCapRpt.setField(new MessageEventSource(securityType+"CX")); // MessageEventSource
			resTrdCapRpt.setField(new TradeReportID(getTrdRptID())); // TradeReportID
			resTrdCapRpt.setField(new StringField(22011, getControlDate()));//ControlDate
			// resTrdCapRpt.setField(new TradeID(getFinraControlNo()));//TradeID

			// resTrdCapRpt.setField(new TradeReportTransType(TradeReportTransType.CANCEL));
			// need to set how
			// resTrdCapRpt.setField(new PreviouslyReported(false));

			/**
			 * Instrument instrument = new Instrument(); resTrdCapRpt.get(instrument);
			 * instrument.set(new NoSecurityAltID(1)); TradeCaptureReport.NoSecurityAltID
			 * altIDGrp = new TradeCaptureReport.NoSecurityAltID(); altIDGrp.set(new
			 * SecurityAltIDSource(FinraSecurityIDSource.SYMBOL)); altIDGrp.set(new
			 * SecurityAltID("Need2Generate")); instrument.addGroup(altIDGrp);
			 * resTrdCapRpt.set(instrument);
			 **/

			resTrdCapRpt.setField(new TradeModifier3(TradeModifier3.T));//TradeModifier3

			resTrdCapRpt.reverseRoute(reqTrdCapRpt.getHeader());
			removeOrderIDFromTradeCaptureReport(resTrdCapRpt);
			ensureResponseHasSides(resTrdCapRpt);
			ensureMandatoryFieldsForAE(resTrdCapRpt);
			ensureExpectedFieldsForInitiator(resTrdCapRpt);
			// Initiator expected list for CACX/SPCX/TSCX: no 64, 454, 455, 456, 939, 22002, 9854, 22004, 22005, 22006, 22009, 22013, 22016, 22036
			for (int tag : new int[] { 64, 454, 455, 456, 939, 22002, 9854, 22004, 22005, 22006, 22009, 22013, 22016, 22036 }) {
				try { resTrdCapRpt.removeField(tag); } catch (Exception ignored) { }
			}
			ensureCXHasOneSideWithStructure(resTrdCapRpt); // 552=1, one side: 54=2, 37=NONE, 453=1, 448=JPMS, 447=C, 452=1
			GatewayAllocQtyEcho.copyTag80IfPresent(reqTrdCapRpt, resTrdCapRpt);
			Session.sendToTarget(resTrdCapRpt, sessionID);
						
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
			log.error("Error ::",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	//Historic
	private void processingTradeReversal(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType) {
		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();

		try {
			resTrdCapRpt = reqTrdCapRpt;
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID

			resTrdCapRpt.setField(new MessageEventSource(securityType+"HX")); // MessageEventSource
			resTrdCapRpt.setField(new TradeReportID(getTrdRptID())); // TradeReportID
			resTrdCapRpt.setField(new StringField(22011, getControlDate()));//ControlDate
			resTrdCapRpt.setField(new TradeID(getFinraControlNo(securityType)));//TradeID

			// resTrdCapRpt.setField(new TradeReportTransType(TradeReportTransType.CANCEL));
			// need to set how
			// resTrdCapRpt.setField(new PreviouslyReported(false));

			Instrument instrument = new Instrument(); 
			reqTrdCapRpt.get(instrument);
			
			instrument.set(new NoSecurityAltID(1));
			
			TradeCaptureReport.NoSecurityAltID altIDGrp = new TradeCaptureReport.NoSecurityAltID(); 
			altIDGrp.set(new SecurityAltIDSource(FinraSecurityIDSource.SYMBOL)); 
			altIDGrp.set(new SecurityAltID("Need2Generate")); 
			instrument.addGroup(altIDGrp);
			
			resTrdCapRpt.set(instrument);
			 
			
			/*
			 * StringField tradeID = new StringField(1003); resTrdCapRpt.setField(new
			 * StringField(1126, reqTrdCapRpt.getField(tradeID).getValue()));// Orginal
			 */			
			resTrdCapRpt.setField(new TradeModifier3(TradeModifier3.T));//TradeModifier3

			resTrdCapRpt.reverseRoute(reqTrdCapRpt.getHeader());
			removeOrderIDFromTradeCaptureReport(resTrdCapRpt);
			ensureResponseHasSides(resTrdCapRpt);
			ensureMandatoryFieldsForAE(resTrdCapRpt);
			ensureExpectedFieldsForInitiator(resTrdCapRpt);
			ensureDefaultsForHXResponse(resTrdCapRpt);     // fill blank/null body fields with FIX defaults for HX
			removeTagsNotExpectedForHX(resTrdCapRpt);       // CAHX/SPHX/TSHX: no 939, 22002, 22036
			ensureHXHasTwoSidesWithStructure(resTrdCapRpt); // 552=2, first side 453=2 TEST/JPMB, 528/58; second side C/17
			GatewayAllocQtyEcho.copyTag80IfPresent(reqTrdCapRpt, resTrdCapRpt);
			Session.sendToTarget(resTrdCapRpt, sessionID);
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
			log.error("Error ::",sessionNotFound);
		} catch (FieldNotFound ex) {
			log.error("Error ::",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	//Allege
	private void processingAllege(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType, String messageEventSource) {

		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();
	//	log.info("Processing ALLEGE request....");
		try {
			resTrdCapRpt = (TradeCaptureReport) reqTrdCapRpt.clone();
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID

			resTrdCapRpt.setField(new MessageEventSource(messageEventSource)); // MessageEventSource
			resTrdCapRpt.setField(new TradeReportID(getTrdRptID())); // TradeReportID
			resTrdCapRpt.setField(new StringField(22011, getControlDate()));// ControlDate
			resTrdCapRpt.setField(new TradeID(getFinraControlNo(securityType)));// TradeID

			if(messageEventSource.contains("AL"))
				resTrdCapRpt.removeField(572);
			removeOrderIDFromTradeCaptureReport(resTrdCapRpt);
			ensureResponseHasSides(resTrdCapRpt);
			ensureMandatoryFieldsForAE(resTrdCapRpt);
			ensureExpectedFieldsForInitiator(resTrdCapRpt);
			
			quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
			String targetCompID = resTrdCapRpt.getHeader().getField(new StringField(56)).getValue();
			
		//	log.info("NoSide :: ["+resTrdCapRpt.getNoSides().getValue()+"]");
			for(int grpIndex = 1; grpIndex<= resTrdCapRpt.getNoSides().getValue(); grpIndex += 1) {
				
				// get 'grpIndex' sidesGroup
				resTrdCapRpt.getGroup(grpIndex, sidesGroup);
				
				// NoPartyIds subgroup
				quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs partyIdsGroup = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();
				
			//	log.info("NoPartyIDs :: ["+sidesGroup.getParties().getNoPartyIDs().getValue()+"]");
				for(int partyIndex =1;  partyIndex <= sidesGroup.getParties().getNoPartyIDs().getValue(); partyIndex +=1 ) {
					
					// get 'partyIndex' partyIdsGroup from sidesGroup
					sidesGroup.getGroup(partyIndex, partyIdsGroup);					
		//			log.info("Before :: PartyID ["+partyIdsGroup.getPartyID().getValue()+"]   PartyRole ["+partyIdsGroup.getPartyRole().getValue()+"]");
					
					if(17 == partyIdsGroup.getPartyRole().getValue()) {						
						targetCompID = partyIdsGroup.getPartyID().getValue(); // changing target comp id with CPID						
						log.info("Existing TargetCompID [ "+ resTrdCapRpt.getHeader().getField(new StringField(56)).getValue()+" ] \tContra Party / Setting as new TargetCompID [ "+ targetCompID+" ]");						
						resTrdCapRpt.getHeader().setField(new TargetCompID(targetCompID));
					}
				}
			}

			log.debug("Changed TargetCompID :: "+ resTrdCapRpt.getHeader().getField(new StringField(56)).getValue());

			GatewayAllocQtyEcho.copyTag80IfPresent(reqTrdCapRpt, resTrdCapRpt);
			for( final Iterator<SessionID> i = settings.sectionIterator(); i.hasNext(); ) {
			  final SessionID id = i.next();
			  if( id.getTargetCompID().startsWith(targetCompID) && id.getTargetSubID().startsWith(resTrdCapRpt.getHeader().getField(new StringField(57)).getValue()) ) {
				// settings.setString( id, "SocketConnectHost", "123.101.202.010" );
				 Session.sendToTarget(resTrdCapRpt, id);				  
			  }					  
			}			
			// Session.sendToTarget(resTrdCapRpt, "FNRA", targetCompID);			
			// Session.sendToTarget(message, senderCompID 49, targetCompID 56);
			// Session.sendToTarget(resTrdCapRpt, reqTrdCapRpt.getHeader().getField(new StringField(49)).getValue(), reqTrdCapRpt.getHeader().getField(new StringField(56)).getValue());			
			
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
			log.error("ERROR :: ",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
		
	}
	
	private void processingAllegeOld(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType, String messageEventSource) {

		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();
	//	log.info("Processing ALLEGE request....");
		try {
			resTrdCapRpt = reqTrdCapRpt;
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID

			resTrdCapRpt.setField(new MessageEventSource(messageEventSource)); // MessageEventSource
			resTrdCapRpt.setField(new TradeReportID(getTrdRptID())); // TradeReportID
			resTrdCapRpt.setField(new StringField(22011, getControlDate()));// ControlDate
			resTrdCapRpt.setField(new TradeID(getFinraControlNo(securityType)));// TradeID

			//resTrdCapRpt.setField(new TradeReportTransType(TradeReportTransType.NEW));
			//resTrdCapRpt.setField(new TradeReportType(TradeReportType.ALLEGED));
			if(messageEventSource.contains("AL"))
				resTrdCapRpt.removeField(572);
			removeOrderIDFromTradeCaptureReport(resTrdCapRpt);
			ensureResponseHasSides(resTrdCapRpt);
			ensureMandatoryFieldsForAE(resTrdCapRpt);
			ensureExpectedFieldsForInitiator(resTrdCapRpt);
			
			// NoSide group
			quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
			
			String rpid="", cpid="JPMS";
			Character rpidSide=null, cpidSide=null;
						
			log.info("NoSide :: ["+resTrdCapRpt.getNoSides().getValue()+"]");
			for(int grpIndex = 1; grpIndex<= resTrdCapRpt.getNoSides().getValue(); grpIndex += 1) {
				// get 'grpIndex' sidesGroup
				resTrdCapRpt.getGroup(grpIndex, sidesGroup);
				// sidesGroup.removeField(54);
				
				// NoPartyIds subgroup
				quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs partyIdsGroup = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();
				
				log.debug("NoPartyIDs :: ["+sidesGroup.getParties().getNoPartyIDs().getValue()+"]");
				for(int partyIndex =1;  partyIndex <= sidesGroup.getParties().getNoPartyIDs().getValue(); partyIndex +=1 ) {
					
					// get 'partyIndex' partyIdsGroup from sidesGroup
					sidesGroup.getGroup(partyIndex, partyIdsGroup);					
					log.debug("Before :: PartyID ["+partyIdsGroup.getPartyID().getValue()+"]   PartyRole ["+partyIdsGroup.getPartyRole().getValue()+"]");
					
					if(17 == partyIdsGroup.getPartyRole().getValue()) {
						
						rpid = partyIdsGroup.getPartyID().getValue();
						
						if(sidesGroup.getSide().getValue() == '1'){
							rpidSide = '2';
							cpidSide = '1';
						}else {
							rpidSide = '1';
							cpidSide = '2';
						}
																	
					}					
										
				}
				sidesGroup.removeGroup(partyIdsGroup);
								
			}
			resTrdCapRpt.removeGroup(sidesGroup);
			
			resTrdCapRpt.addGroup( buildSidesGroup(rpidSide, rpid, 1));
			
			resTrdCapRpt.addGroup( buildSidesGroup(cpidSide, cpid, 17));

			GatewayAllocQtyEcho.copyTag80IfPresent(reqTrdCapRpt, resTrdCapRpt);
			Session.sendToTarget(resTrdCapRpt, sessionID);
			
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
			log.error("ERROR :: ",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
		
	}
	
	
	/**
	 * Ensure mandatory FIX / FINRA TRACE fields are present on outgoing AE (TradeCaptureReport) to Trace gateway (initiator).
	 * Only applied to responses we send; never modifies incoming messages.
	 * Required per spec_required_tags: 571, 32, 31, 75, 60, 552, 48, 22, 64, 939 (+ conditional 487, 22011, etc.).
	 */
	private void ensureMandatoryFieldsForAE(TradeCaptureReport msg) {
		try {
			if (!msg.isSetField(new SettlDate())) {
				try {
					msg.setField(new SettlDate(msg.getTradeDate().getValue()));
				} catch (FieldNotFound e) {
					msg.setField(new SettlDate(getControlDate()));
				}
				log.debug("ensureMandatoryFieldsForAE: set SettlDate(64) from TradeDate or ControlDate.");
			}
		} catch (Exception e) { log.trace("SettlDate check: {}", e.getMessage()); }
		try {
			if (!msg.isSetField(new TradeReportType())) {
				msg.setField(new TradeReportType(TradeReportType.SUBMIT));
				log.debug("ensureMandatoryFieldsForAE: set TradeReportType(939)=SUBMIT.");
			}
		} catch (Exception e) { log.trace("TradeReportType check: {}", e.getMessage()); }
		try {
			if (!msg.isSetField(new TradeReportTransType())) {
				msg.setField(new TradeReportTransType(TradeReportTransType.NEW));
				log.debug("ensureMandatoryFieldsForAE: set TradeReportTransType(487)=NEW.");
			}
		} catch (Exception e) { log.trace("TradeReportTransType check: {}", e.getMessage()); }
		try {
			if (!msg.isSetField(new StringField(22011))) {
				msg.setField(new StringField(22011, getControlDate()));
				log.debug("ensureMandatoryFieldsForAE: set ControlDate(22011).");
			}
		} catch (Exception e) { log.trace("ControlDate check: {}", e.getMessage()); }
		try {
			if (!msg.isSetField(939)) {
				msg.setField(new IntField(939, 0)); // 939 per FINRA spec (report type / status)
				log.debug("ensureMandatoryFieldsForAE: set 939=0.");
			}
		} catch (Exception e) { log.trace("939 check: {}", e.getMessage()); }
	}

	/** Fill default values for CAEN/SPEN/TSEN (and CACR/SPCR/TSCR) body fields when missing or blank so FIX engine and initiator accept. */
	private void ensureDefaultsForENResponse(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || eventSource.length() < 2) return;
		String suffix = eventSource.substring(eventSource.length() - 2);
		if (!"EN".equals(suffix) && !"CR".equals(suffix)) return;
		try {
			// 22 SecurityIDSource — default 8 (ISO 10962)
			if (!msg.isSetField(22)) msg.setField(new IntField(22, 8));
			// 31 LastPx — default 0
			if (!msg.isSetField(31)) msg.setField(new LastPx(0.0));
			// 32 LastQty — default 0
			if (!msg.isSetField(32)) msg.setField(new LastQty(0.0));
			// 48 SecurityID — default SYMBOL (Instrument)
			try {
				Instrument inst = new Instrument();
				msg.get(inst);
				if (!inst.isSetField(48) || inst.getSecurityID().getValue().trim().isEmpty()) {
					inst.set(new SecurityID("SYMBOL"));
					msg.set(inst);
				}
			} catch (Exception e) { log.trace("Instrument 48 default: {}", e.getMessage()); }
			// 60 TransactTime — default current UTC (format yyyyMMdd-HH:mm:ss.SSS to avoid Date/LocalDateTime mismatch)
			if (!msg.isSetField(60)) {
				java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat("yyyyMMdd-HH:mm:ss.SSS");
				utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
				msg.setField(new StringField(60, utc.format(new Date())));
			}
			// 64 SettlDate — already set in ensureMandatoryFieldsForAE; fallback here if still missing
			if (!msg.isSetField(64)) msg.setField(new SettlDate(getControlDate()));
			// 75 TradeDate — default control date
			if (!msg.isSetField(75)) msg.setField(new TradeDate(getControlDate()));
			// 1003 FirmTradeID — default generated when missing or blank
			String secType = eventSource.length() >= 2 ? eventSource.substring(0, 2) : "CA";
			if (!msg.isSetField(1003)) msg.setField(new StringField(1003, getFinraControlNo(secType)));
			else try { if (msg.getField(new StringField(1003)).getValue().trim().isEmpty()) msg.setField(new StringField(1003, getFinraControlNo(secType))); } catch (Exception e) { }
		} catch (Exception e) { log.trace("ensureDefaultsForENResponse: {}", e.getMessage()); }
	}

	/** Fill default values for CAHX/SPHX/TSHX body fields when missing or blank so FIX engine and initiator accept. */
	private void ensureDefaultsForHXResponse(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || !eventSource.endsWith("HX")) return;
		try {
			if (!msg.isSetField(22)) msg.setField(new IntField(22, 8));
			if (!msg.isSetField(31)) msg.setField(new LastPx(0.0));
			if (!msg.isSetField(32)) msg.setField(new LastQty(0.0));
			try {
				Instrument inst = new Instrument();
				msg.get(inst);
				if (!inst.isSetField(48) || inst.getSecurityID().getValue().trim().isEmpty()) {
					inst.set(new SecurityID("SYMBOL"));
					msg.set(inst);
				}
			} catch (Exception e) { log.trace("Instrument 48 default HX: {}", e.getMessage()); }
			if (!msg.isSetField(60)) {
				java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat("yyyyMMdd-HH:mm:ss.SSS");
				utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
				msg.setField(new StringField(60, utc.format(new Date())));
			}
			if (!msg.isSetField(64)) msg.setField(new SettlDate(getControlDate()));
			if (!msg.isSetField(75)) msg.setField(new TradeDate(getControlDate()));
			String secType = eventSource.length() >= 2 ? eventSource.substring(0, 2) : "CA";
			if (!msg.isSetField(1003)) msg.setField(new StringField(1003, getFinraControlNo(secType)));
			else try { if (msg.getField(new StringField(1003)).getValue().trim().isEmpty()) msg.setField(new StringField(1003, getFinraControlNo(secType))); } catch (Exception e) { }
		} catch (Exception e) { log.trace("ensureDefaultsForHXResponse: {}", e.getMessage()); }
	}

	/** Set initiator-expected fields (CAEN/SPEN/TSEN, CACR/SPCR/TSCR, CACX/SPCX/TSCX, CAHX/SPHX/TSHX) when missing. */
	private void ensureExpectedFieldsForInitiator(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || eventSource.length() < 2) return;
		String suffix = eventSource.substring(eventSource.length() - 2);
		boolean isCX = "CX".equals(suffix);
		boolean isHX = "HX".equals(suffix);
		try {
			// 856 (TradeReportType) per response type: EN=0, CR=5, CX=6, HX=6 (samples); always set for initiator
			int tag856 = isCX ? 6 : (isHX ? 6 : ("CR".equals(suffix) ? 5 : 0));
			msg.setField(new IntField(856, tag856));
			// 570=N (PreviouslyReported) expected by initiator
			if (!msg.isSetField(570)) msg.setField(new StringField(570, "N"));
			if (isCX) {
				// CX: valid format has 1015=1, 22003=T, 22011 (always set)
				msg.setField(new IntField(1015, 1));
				if (!msg.isSetField(22003)) msg.setField(new StringField(22003, "T"));
				return;
			}
			// EN/CR/HX: initiator expects 1015=1 (always set, overwrite request value)
			msg.setField(new IntField(1015, 1));
			if (!msg.isSetField(9854)) msg.setField(new StringField(9854, "N"));
			if (!msg.isSetField(22003)) msg.setField(new StringField(22003, "T"));
			if (!msg.isSetField(22004)) msg.setField(new StringField(22004, "W"));
			if (!msg.isSetField(22005)) msg.setField(new StringField(22005, "N"));
			if (!msg.isSetField(22006)) msg.setField(new StringField(22006, "N"));
			if (!msg.isSetField(22009)) msg.setField(new StringField(22009, new SimpleDateFormat("HH:mm:ss").format(new Date())));
			if (!msg.isSetField(22011)) msg.setField(new StringField(22011, getControlDate()));
			if (!msg.isSetField(22013)) msg.setField(new StringField(22013, "N"));
			if (!msg.isSetField(22016)) msg.setField(new StringField(22016, "S1"));
			if (!isHX && !msg.isSetField(22036)) msg.setField(new StringField(22036, "BCAP"));
		} catch (Exception e) { log.trace("ensureExpectedFieldsForInitiator: {}", e.getMessage()); }
	}

	/**
	 * Ensure outgoing TradeCaptureReport has at least one NoSides group with Side (54), OrderID (37), party info
	 * so Trace gateway (initiator) does not reject. Only applied to responses we send to initiator.
	 */
	private void ensureResponseHasSides(TradeCaptureReport msg) {
		try {
			int n = msg.getNoSides().getValue();
			if (n >= 1) {
				quickfix.fix44.TradeCaptureReport.NoSides first = new quickfix.fix44.TradeCaptureReport.NoSides();
				msg.getGroup(1, first);
				boolean hasSide = first.isSetField(new Side());
				boolean hasOrderID = false;
				try { first.getField(new OrderID()); hasOrderID = true; } catch (FieldNotFound ignored) { }
				if (hasSide && hasOrderID) return;
				// First group missing Side(54) or OrderID(37); add one full group so JPMS gets required fields
			}
		} catch (FieldNotFound ignored) { }
		msg.addGroup(buildSidesGroup('1', "SIM", 1));
		log.debug("ensureResponseHasSides: added NoSides group with Side(54), OrderID(37), PartyID, PartyRole.");
	}

	/** Build NoSides group for outgoing AE to Trace gateway (initiator): Side(54), OrderID(37), PartyID, PartyRole. */
	private quickfix.fix44.TradeCaptureReport.NoSides buildSidesGroup(Character bsindicator, String partyID, int partyRole)
			{
		quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
		try {
		sidesGroup.set(new Side(bsindicator));
		sidesGroup.set(new OrderID("NONE")); // 37=NONE expected by initiator (CAEN/CACR/CACX/CAHX samples)

		// NoPartyIds subgroup
		quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs partyIdsGroup = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();

		partyIdsGroup.set(new PartyID(partyID));
		partyIdsGroup.set(new PartyRole(partyRole));
		partyIdsGroup.set(new PartyIDSource('C')); // 447 expected by initiator (C=General Identifier)
		sidesGroup.addGroup(partyIdsGroup);
		
		}catch (Exception ex) {
			log.error("ERROR :: ",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
			
		}
		return sidesGroup;

	}

	/** Build first side for CAEN/SPEN/TSEN: 54=2, 37=NONE, 453=1, 448=JPMS, 447=C, 452=1, 802=1, 523=T, 803=24, 528=P, 58=UBS. */
	private quickfix.fix44.TradeCaptureReport.NoSides buildFirstSideForEN() {
		quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
		sidesGroup.set(new Side('2'));
		sidesGroup.set(new OrderID("NONE"));
		quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs partyIdsGroup = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();
		partyIdsGroup.set(new PartyID("JPMS"));
		partyIdsGroup.set(new PartyIDSource('C'));
		partyIdsGroup.set(new PartyRole(1));
		quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs.NoPartySubIDs subGrp = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs.NoPartySubIDs();
		subGrp.set(new PartySubID("T"));
		subGrp.set(new PartySubIDType(24));
		partyIdsGroup.addGroup(subGrp);
		sidesGroup.addGroup(partyIdsGroup);
		sidesGroup.set(new OrderCapacity('P'));
		sidesGroup.set(new Text("UBS"));
		return sidesGroup;
	}

	/** Build second side for CAEN/SPEN/TSEN: 54=1, 37=NONE, 453=1, 448=BCAP, 447=C, 452=17. */
	private quickfix.fix44.TradeCaptureReport.NoSides buildSecondSideForEN() {
		return buildSidesGroup('1', "BCAP", 17);
	}

	/** Build first side for CAHX/SPHX/TSHX: 54=2, 37=NONE, 453=2 with 448=TEST,447=C,452=1 and 448=JPMB,447=C,452=7, then 528=P, 58=UBS. */
	private quickfix.fix44.TradeCaptureReport.NoSides buildFirstSideForHX() {
		quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
		sidesGroup.set(new Side('2'));
		sidesGroup.set(new OrderID("NONE"));
		quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs party1 = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();
		party1.set(new PartyID("TEST"));
		party1.set(new PartyIDSource('C'));
		party1.set(new PartyRole(1));
		sidesGroup.addGroup(party1);
		quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs party2 = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();
		party2.set(new PartyID("JPMB"));
		party2.set(new PartyIDSource('C'));
		party2.set(new PartyRole(7));
		sidesGroup.addGroup(party2);
		sidesGroup.set(new OrderCapacity('P'));
		sidesGroup.set(new Text("UBS"));
		return sidesGroup;
	}

	/** Build second side for CAHX/SPHX/TSHX: 54=1, 37=NONE, 453=1, 448=C, 447=C, 452=17. */
	private quickfix.fix44.TradeCaptureReport.NoSides buildSecondSideForHX() {
		return buildSidesGroup('1', "C", 17);
	}

	/** Remove body tags not in valid CAHX/SPHX/TSHX format. Valid sample has no 939, 22002, 22036. */
	private void removeTagsNotExpectedForHX(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || !eventSource.endsWith("HX")) return;
		try { msg.removeField(939); } catch (Exception e) { log.trace("Remove 939 from HX: {}", e.getMessage()); }
		try { msg.removeField(22002); } catch (Exception e) { log.trace("Remove 22002 from HX: {}", e.getMessage()); }
		try { msg.removeField(22036); } catch (Exception e) { log.trace("Remove 22036 from HX: {}", e.getMessage()); }
	}

	/** For CAHX/SPHX/TSHX: ensure 552=2 with two sides matching valid format (first side 453=2 TEST/JPMB, 528/58; second side C/17). */
	private void ensureHXHasTwoSidesWithStructure(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || !eventSource.endsWith("HX")) return;
		try {
			int n = msg.getNoSides().getValue();
			quickfix.fix44.TradeCaptureReport.NoSides firstSide = buildFirstSideForHX();
			quickfix.fix44.TradeCaptureReport.NoSides secondSide = buildSecondSideForHX();
			if (n == 0) {
				msg.addGroup(firstSide);
				msg.addGroup(secondSide);
				log.debug("ensureHXHasTwoSidesWithStructure: added two sides for HX.");
			} else if (n == 1) {
				msg.replaceGroup(1, firstSide);
				msg.addGroup(secondSide);
				log.debug("ensureHXHasTwoSidesWithStructure: replaced first side and added second for HX.");
			} else {
				msg.replaceGroup(1, firstSide);
				msg.replaceGroup(2, secondSide);
				log.debug("ensureHXHasTwoSidesWithStructure: replaced both sides for HX.");
			}
		} catch (Exception e) { log.trace("ensureHXHasTwoSidesWithStructure: {}", e.getMessage()); }
	}

	/** Remove body tags not in valid EN/CR format (initiator expects exact tag set). Valid CAEN/SPEN/TSEN and CACR/SPCR/TSCR samples have no 939, 22002. */
	private void removeTagsNotExpectedForEN(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || eventSource.length() < 2) return;
		String suffix = eventSource.substring(eventSource.length() - 2);
		if (!"EN".equals(suffix) && !"CR".equals(suffix)) return;
		try { msg.removeField(939); } catch (Exception e) { log.trace("Remove 939: {}", e.getMessage()); }
		try { msg.removeField(22002); } catch (Exception e) { log.trace("Remove 22002: {}", e.getMessage()); }
	}

	/** For CAEN/SPEN/TSEN and CACR/SPCR/TSCR: ensure 552=2 with two sides matching valid format (first side 802/523/803/528/58, second side BCAP/17). */
	private void ensureENHasTwoSidesWithStructure(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || eventSource.length() < 2) return;
		String suffix = eventSource.substring(eventSource.length() - 2);
		if (!"EN".equals(suffix) && !"CR".equals(suffix)) return;
		try {
			int n = msg.getNoSides().getValue();
			quickfix.fix44.TradeCaptureReport.NoSides firstSide = buildFirstSideForEN();
			quickfix.fix44.TradeCaptureReport.NoSides secondSide = buildSecondSideForEN();
			if (n == 0) {
				msg.addGroup(firstSide);
				msg.addGroup(secondSide);
				log.debug("ensureENHasTwoSidesWithStructure: added two sides for EN.");
			} else if (n == 1) {
				msg.replaceGroup(1, firstSide);
				msg.addGroup(secondSide);
				log.debug("ensureENHasTwoSidesWithStructure: replaced first side and added second for EN.");
			} else {
				msg.replaceGroup(1, firstSide);
				msg.replaceGroup(2, secondSide);
				log.debug("ensureENHasTwoSidesWithStructure: replaced both sides for EN.");
			}
		} catch (Exception e) { log.trace("ensureENHasTwoSidesWithStructure: {}", e.getMessage()); }
	}

	/** For CACX/SPCX/TSCX: ensure 552=1 with exactly one side matching valid format: 54=2, 37=NONE, 453=1, 448=JPMS, 447=C, 452=1 (no 802/523/803/528/58). */
	private void ensureCXHasOneSideWithStructure(TradeCaptureReport msg) {
		String eventSource = null;
		try { eventSource = msg.getField(new StringField(1011)).getValue(); } catch (Exception e) { return; }
		if (eventSource == null || !eventSource.endsWith("CX")) return;
		try {
			quickfix.fix44.TradeCaptureReport.NoSides cxSide = buildSidesGroup('2', "JPMS", 1); // 54=2, 37=NONE, 448=JPMS, 447=C, 452=1
			int n = msg.getNoSides().getValue();
			if (n == 0) {
				msg.addGroup(cxSide);
			} else {
				msg.replaceGroup(1, cxSide);
			}
			msg.setField(new quickfix.field.NoSides(1)); // valid CX has 552=1
			log.debug("ensureCXHasOneSideWithStructure: set 552=1, one side (54=2, JPMS).");
		} catch (Exception e) { log.trace("ensureCXHasOneSideWithStructure: {}", e.getMessage()); }
	}
	
	public void onMessage(Message msg, SessionID sessionID) {
		try { 
			log.info(msg.toString());  
		} catch (Exception e) { 
		  e.printStackTrace(); 
		}		 
	}
	
	/*
	 * int logOnCount=0; public void onMessage(Logon msg, SessionID sessionID) { try {
	 * 
	 * Thread.sleep(20000); } catch (Exception e) { e.printStackTrace(); } }
	 */
	
	public void onMessage(TestRequest msg, SessionID sessionID) {
		try {
			if (msg.getHeader().getField(new SenderCompID()).getValue().equalsIgnoreCase(sessionID.getSenderCompID())) {
				log.info("Sending TestRequest message to Gateway :: SeqNumber [" + msg.getHeader().getField(new MsgSeqNum()) + "] :: " + msg.toString());
			
			}else {
				log.info("Received TestRequest message from gateway");
				log.info(msg.toString());				
			}
		} catch (FieldNotFound e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		log.info(msg.toString());
	}
	
	public void onMessage(Heartbeat msg, SessionID sessionID) throws FieldNotFound {

		try {
			String senderCompID = msg.getHeader().getField(new SenderCompID()).getValue(); 
			log.info("");
			
			if ( senderCompID.equalsIgnoreCase(sessionID.getSenderCompID())) {
				heartbeatcount++;
				if (heartBeatRequiredFromConfig) {
					log.info("HeartBtDelay :: {} \t HeartBtDelayCount :: {} Current HeartBtCount :: {}",
						heartBtDelayFromConfig, heartBtDelayCountFromConfig, heartbeatcount);
					if (heartBtDelayFromConfig && heartbeatcount == heartBtDelayCountFromConfig) {
						log.info("Heart Beat Delay Time is [{}]", heartBtDelayTimeFromConfig);
						Thread.sleep(heartBtDelayTimeFromConfig * 1000L);
						log.info("Heartbeat message from FINRA-{} to Gateway is :: {}", simulatorRoleFromConfig.toUpperCase(), msg.toString());
						heartbeatcount = 0;
					}
				}
				log.info("Sending Heartbeat message from FINRA-{} to Gateway :: SeqNumber [{}] {}", simulatorRoleFromConfig.toUpperCase(), msg.getHeader().getField(new MsgSeqNum()), msg.toString());
			} else {
				log.info("Received Heartbeat message from gateway");
				log.info(msg.toString());
			}
		
		} catch (Exception e) {
			
			e.printStackTrace();
		}

	}
	
	
	private String getTrdRptID() {
		return Long.valueOf(System.nanoTime() + (nextID++)).toString();
	}

	/** Unique OrderID for NoSides group (37); JPMS requires 37 in each side. */
	private String getOrderID() {
		return "ORD" + (System.nanoTime() + (nextID++));
	}

	private String getControlDate() {
		SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd");
		return f.format(new Date());
	}

	/*private String getFinraControlNo() {
		return Long.valueOf(System.currentTimeMillis() + (nextID++)).toString().substring(0, 9);
	}*/
	
	private String getFinraControlNo(String secType) {
		
		
		if("TS".equalsIgnoreCase( secType)) {
			// return "7"+Long.valueOf((nextID++) * System.nanoTime()).toString().substring(0, 9);
			return "7"+nextID++;
		}else {
			//return "1"+Long.valueOf((nextID++) * System.nanoTime()).toString().substring(0, 9);
			return "1"+nextID++;
		}
		
	}
		 
	private static class TradeRptStatus extends IntField {
		static final long serialVersionUID = 20050620;

		public static final int FIELD = 939;
		public static final int REJECTED = 1;

		public TradeRptStatus() {
			super(FIELD);
		}

		public TradeRptStatus(int data) {
			super(939, data);
		}
	}

}

class FinraSecurityIDSource extends SecurityIDSource {
	static final long serialVersionUID = 20050618;

	public static final int FIELD = 22;
	public static final String SYMBOL = "8";

	public FinraSecurityIDSource() {
		super();
	}

	public FinraSecurityIDSource(String data) {
		super(data);
	}
}