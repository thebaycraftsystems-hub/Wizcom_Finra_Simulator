package com.wizcom.fix.simulator;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.sql.DataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.wizcom.fix.simulator.compliance.InMemoryLifecycleStore;
import com.wizcom.fix.simulator.compliance.JdbcLifecycleStore;
import com.wizcom.fix.simulator.compliance.LifecycleStateStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.quickfixj.jmx.JmxExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.CompositeLogFactory;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldConvertError;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.JdbcLogFactory;
import quickfix.JdbcStoreFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RuntimeError;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.field.Text;
import quickfix.fix44.Logout;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider.TemplateMapping;

import static quickfix.Acceptor.SETTING_ACCEPTOR_TEMPLATE;
import static quickfix.Acceptor.SETTING_SOCKET_ACCEPT_ADDRESS;
import static quickfix.Acceptor.SETTING_SOCKET_ACCEPT_PORT;

/**
 * Hello world!
 *
 */
public class Simulator {
	private final static Logger log = LoggerFactory.getLogger(Simulator.class);
	private final SocketAcceptor acceptor;
	private final Map<InetSocketAddress, List<TemplateMapping>> dynamicSessionMappings = new HashMap<>();
	/** HikariCP DataSource when DB is used; held for clean shutdown. */
	private final HikariDataSource jdbcDataSource;
	private volatile boolean stopped;
	/** When true, send Logout to initiator on shutdown (SendLogout_at_Shutdown=Y). */
	private final boolean sendLogoutAtShutdown;
	/** Stored for shutdown: send Logout to configured sessions. */
	private final SessionSettings sessionSettings;
	/** When non-null (UseJdbcStore=Y), used after sending Logout at shutdown to persist next sender seq so next Logon uses N+1. */
	private final SessionSequenceFromDB sessionSequenceFromDB;

    private final JmxExporter jmxExporter;
    private final ObjectName connectorObjectName;
    
    
    public Simulator(SessionSettings settings) throws ConfigError, FieldConvertError, JMException {
        this(settings, null);
    }

    public Simulator(SessionSettings settings, String configResourceName) throws ConfigError, FieldConvertError, JMException {
    	
    	String role = "PRIMARY";
    	try {
    	    if (settings.isSetting("SimulatorRole")) role = settings.getString("SimulatorRole").toUpperCase();
    	} catch (Exception ignored) {}
    	// So every log line in logs/simulator.log (and console) shows Primary or Secondary
    	String instanceLabel = ("PRIMARY".equals(role) ? "Primary" : "Secondary");
    	com.wizcom.fix.simulator.SimulatorInstanceHolder.set(instanceLabel);
    	// Big banner so you can easily see Primary vs Secondary when switching
    	String bannerLine = "################################################################################";
    	String blankLine   = "##                                                                              ##";
    	String switchLine  = "##            >>>>>  SWITCHED TO " + role + "  <<<<<                               ##";
    	String runLine     = "##            FINRA Simulator now running as " + role + "                          ##";
    	log.warn(bannerLine);
    	log.warn(blankLine);
    	log.warn(switchLine);
    	log.warn(runLine);
    	log.warn(blankLine);
    	log.warn(bannerLine);
    	System.out.println("\n" + bannerLine + "\n" + blankLine + "\n" + switchLine + "\n" + runLine + "\n" + blankLine + "\n" + bannerLine + "\n");
    	log.info("WELCOME TO WIZCOM FIX SIMULATOR — Role: {} | Version [6.0.0]", role);
    	System.out.println("WELCOME TO WIZCOM FIX SIMULATOR — Role: " + role + " | Version 6.0.0");
    	
        boolean logToFile = false;
        boolean logToDB = false;
        boolean logToScreen = false;
        boolean useJdbcStore = false;
        boolean sendLogoutAtShutdownConfig = false;
        try {
            logToFile = settings.getBool("LogToFile");
            logToDB = settings.getBool("LogToDB");
            logToScreen = settings.getBool("LogToScreen");
            useJdbcStore = settings.getBool("UseJdbcStore");
            if (settings.isSetting("SendLogout_at_Shutdown")) {
                sendLogoutAtShutdownConfig = settings.getBool("SendLogout_at_Shutdown");
            }
        } catch (FieldConvertError ex) {}
        this.sendLogoutAtShutdown = sendLogoutAtShutdownConfig;
        this.sessionSettings = settings;

        // Use HikariCP DataSource when DB is enabled to avoid Proxool + signed SQL Server driver conflict
        jdbcDataSource = (useJdbcStore || logToDB) ? createJdbcDataSource(settings) : null;
        if (useJdbcStore && jdbcDataSource != null && !isJdbcStoreSchemaValid(jdbcDataSource, settings)) {
            log.warn("JDBC store schema invalid (e.g. missing senderlocid/targetlocid or DB unreachable). Run sql/quickfixj_sqlserver_schema.sql. Falling back to file store — TRACE_FIX_MESSAGES and TRACE_FIX_SESSIONS will NOT be updated by this instance.");
            useJdbcStore = false;
        }
        LifecycleStateStore lifecycleStore = (jdbcDataSource != null) ? new JdbcLifecycleStore(jdbcDataSource) : new InMemoryLifecycleStore();
        if (lifecycleStore instanceof JdbcLifecycleStore) {
            log.info("Using JDBC lifecycle store — TRACE_LIFECYCLE_STATE will be updated (shared by Primary/Secondary).");
        }
        WizFixApplication wizFixApplication = new WizFixApplication(settings, configResourceName, lifecycleStore);
        SessionSequenceFromDB sessionSequenceFromDBInstance = null;
        if (useJdbcStore && jdbcDataSource != null) {
            String sessionsTable = "TRACE_FIX_SESSIONS";
            java.time.ZoneId sessionDateZone = java.time.ZoneId.of("America/New_York");
            try {
                SessionID any = getFirstSessionId(settings);
                if (settings.isSetting(any, "JdbcStoreSessionsTableName")) {
                    sessionsTable = settings.getString(any, "JdbcStoreSessionsTableName");
                }
                if (settings.isSetting(any, "SessionDateZone")) {
                    sessionDateZone = java.time.ZoneId.of(settings.getString(any, "SessionDateZone").trim());
                }
            } catch (Exception ignored) { }
            sessionSequenceFromDBInstance = new SessionSequenceFromDB(jdbcDataSource, sessionsTable, sessionDateZone);
            wizFixApplication.setSessionSequenceFromDB(sessionSequenceFromDBInstance);
            resetSequenceOnStartIfConfigured(settings, jdbcDataSource, sessionsTable);
        }
        this.sessionSequenceFromDB = sessionSequenceFromDBInstance;
		MessageStoreFactory messageStoreFactory = createMessageStoreFactory(settings, useJdbcStore, jdbcDataSource);

        LogFactory logFactory;
        if (logToFile && logToDB && logToScreen) {
            logFactory = new CompositeLogFactory(new LogFactory[] { new ScreenLogFactory(settings), new FileLogFactory(settings), createJdbcLogFactory(settings, logToDB, jdbcDataSource) });
        } else if (logToFile && logToDB) {
            logFactory = new CompositeLogFactory(new LogFactory[] { new FileLogFactory(settings), createJdbcLogFactory(settings, logToDB, jdbcDataSource) });
        } else if (logToFile && logToScreen) {
            logFactory = new CompositeLogFactory(new LogFactory[] { new ScreenLogFactory(settings), new FileLogFactory(settings) });
        } else if (logToDB && logToScreen) {
            logFactory = new CompositeLogFactory(new LogFactory[] { new ScreenLogFactory(settings), createJdbcLogFactory(settings, logToDB, jdbcDataSource) });
        } else if (logToFile) {
            logFactory = new CompositeLogFactory(new LogFactory[] { new FileLogFactory(settings) });
        } else if (logToDB) {
            logFactory = new CompositeLogFactory(new LogFactory[] { createJdbcLogFactory(settings, logToDB, jdbcDataSource) });
        } else {
            logFactory = new ScreenLogFactory(settings);
        }
        
        MessageFactory messageFactory = new DefaultMessageFactory();

        acceptor = new SocketAcceptor(wizFixApplication, messageStoreFactory, settings, logFactory, messageFactory);

        configureDynamicSessions(settings, wizFixApplication, messageStoreFactory, logFactory, messageFactory);

        jmxExporter = new JmxExporter();
        connectorObjectName = jmxExporter.register(acceptor);
        
        log.info("Acceptor registered with JMX, name={}", connectorObjectName);
    }

    /**
     * Creates a HikariCP DataSource from JDBC settings (JdbcURL, JdbcUser, JdbcPassword, JdbcDriver).
     * Used when LogToDB=Y or UseJdbcStore=Y to avoid Proxool + signed SQL Server driver conflict.
     */
    private HikariDataSource createJdbcDataSource(SessionSettings settings) throws ConfigError, FieldConvertError {
        SessionID anySession = getFirstSessionId(settings);
        String url = settings.getString(anySession, "JdbcURL");
        // Tag this connection so DB triggers can record Primary vs Secondary (simulator_instance column)
        String role = "Primary";
        try {
            if (settings.isSetting(anySession, "SimulatorRole")) {
                role = settings.getString(anySession, "SimulatorRole").trim();
            }
        } catch (Exception ignored) {}
        if (role.isEmpty()) role = "Primary";
        if (!url.toLowerCase().contains("applicationname=")) {
            url = url + ";applicationName=FixSimulator-" + role;
            log.info("JDBC applicationName set to FixSimulator-{} for DB simulator_instance tracking", role);
        }
        String user = settings.getString(anySession, "JdbcUser");
        String password = settings.getString(anySession, "JdbcPassword");
        String driverClassName = settings.getString(anySession, "JdbcDriver");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        log.info("Using HikariCP DataSource for JDBC store/log (bypasses Proxool)");
        return new HikariDataSource(config);
    }

    private SessionID getFirstSessionId(SessionSettings settings) throws ConfigError, FieldConvertError {
        Iterator<SessionID> it = settings.sectionIterator();
        if (!it.hasNext()) {
            throw new ConfigError("At least one [SESSION] or [DEFAULT] required for JDBC settings");
        }
        return it.next();
    }

    /**
     * When ResetSequenceOnStart=Y in [default], resets all rows in the sessions table to incoming_seqnum=1, outgoing_seqnum=1
     * so the simulator starts with sequence 1 (fresh data). Use for clean start / testing.
     */
    private void resetSequenceOnStartIfConfigured(SessionSettings settings, DataSource dataSource, String sessionsTableName) {
        boolean reset = false;
        try {
            SessionID any = getFirstSessionId(settings);
            if (settings.isSetting(any, "ResetSequenceOnStart")) {
                reset = settings.getBool(any, "ResetSequenceOnStart");
            }
        } catch (Exception ignored) { }
        if (!reset || dataSource == null || sessionsTableName == null || sessionsTableName.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            int updated = st.executeUpdate("UPDATE " + sessionsTableName + " SET incoming_seqnum = 1, outgoing_seqnum = 1");
            log.info("ResetSequenceOnStart=Y: set incoming_seqnum and outgoing_seqnum to 1 for {} row(s) in {}", updated, sessionsTableName);
        } catch (SQLException e) {
            log.warn("ResetSequenceOnStart=Y but failed to reset {}: {}", sessionsTableName, e.getMessage());
        }
    }

    private LogFactory createJdbcLogFactory(SessionSettings settings, boolean logToDB, DataSource dataSource) {
        JdbcLogFactory f = new JdbcLogFactory(settings);
        if (logToDB && dataSource != null) {
            f.setDataSource(dataSource);
        }
        return f;
    }

    /**
     * Returns true if the JDBC store tables have the required schema (e.g. senderlocid, targetlocid).
     * QuickFIX/J 2.x requires these columns; older DB schemas may lack them.
     * Validates both sessions and messages tables (both are used by JdbcStore).
     */
    private boolean isJdbcStoreSchemaValid(DataSource dataSource, SessionSettings settings) {
        String sessionsTable = "TRACE_FIX_SESSIONS";
        String messagesTable = "TRACE_FIX_MESSAGES";
        try {
            SessionID any = getFirstSessionId(settings);
            if (settings.isSetting(any, "JdbcStoreSessionsTableName")) {
                sessionsTable = settings.getString(any, "JdbcStoreSessionsTableName");
            }
            if (settings.isSetting(any, "JdbcStoreMessagesTableName")) {
                messagesTable = settings.getString(any, "JdbcStoreMessagesTableName");
            }
        } catch (ConfigError | FieldConvertError e) {
            return false;
        }
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeQuery("SELECT TOP 1 senderlocid, targetlocid FROM " + sessionsTable + " WHERE 1=0");
            st.executeQuery("SELECT TOP 1 senderlocid, targetlocid FROM " + messagesTable + " WHERE 1=0");
            return true;
        } catch (SQLException e) {
            log.warn("JDBC store schema check failed for {} / {}: {} — check DB reachability and run sql/quickfixj_sqlserver_schema.sql.", sessionsTable, messagesTable, e.getMessage());
            return false;
        }
    }

    /**
     * Creates the message store factory from config.
     * Set UseJdbcStore=Y (and JDBC settings) to persist sessions and messages in the database;
     * otherwise uses file-based store (FileStorePath).
     * When useJdbcStore and dataSource are set, injects DataSource to avoid Proxool.
     */
    private MessageStoreFactory createMessageStoreFactory(SessionSettings settings, boolean useJdbcStore, DataSource dataSource) throws ConfigError, FieldConvertError {
        if (useJdbcStore) {
            log.info("Using JDBC message store — TRACE_FIX_SESSIONS and TRACE_FIX_MESSAGES will be updated on each message.");
            JdbcStoreFactory factory = new JdbcStoreFactory(settings);
            if (dataSource != null) {
                factory.setDataSource(dataSource);
            }
            return factory;
        }
        log.info("Using file message store (FileStorePath)");
        return new FileStoreFactory(settings);
    }

    private void configureDynamicSessions(SessionSettings settings, WizFixApplication wizFixApplication, MessageStoreFactory messageStoreFactory, LogFactory logFactory, MessageFactory messageFactory) throws ConfigError, FieldConvertError {
        //
        // If a session template is detected in the settings, then
        // set up a dynamic session provider.
        //

        Iterator<SessionID> sectionIterator = settings.sectionIterator();
        while (sectionIterator.hasNext()) {
            SessionID sessionID = sectionIterator.next();
            if (isSessionTemplate(settings, sessionID)) {
                InetSocketAddress address = getAcceptorSocketAddress(settings, sessionID);
                getMappings(address).add(new TemplateMapping(sessionID, sessionID));
            }
        }

        for (Map.Entry<InetSocketAddress, List<TemplateMapping>> entry : dynamicSessionMappings.entrySet()) {
            acceptor.setSessionProvider(entry.getKey(), new DynamicAcceptorSessionProvider(settings, entry.getValue(), wizFixApplication, messageStoreFactory, logFactory, messageFactory));
        }
    }
    
    private boolean isSessionTemplate(SessionSettings settings, SessionID sessionID)
            throws ConfigError, FieldConvertError {
        return settings.isSetting(sessionID, SETTING_ACCEPTOR_TEMPLATE)
                && settings.getBool(sessionID, SETTING_ACCEPTOR_TEMPLATE);
    }
    
    private InetSocketAddress getAcceptorSocketAddress(SessionSettings settings, SessionID sessionID) throws ConfigError, FieldConvertError {
        String acceptorHost = "0.0.0.0";
        if (settings.isSetting(sessionID, SETTING_SOCKET_ACCEPT_ADDRESS)) {
            acceptorHost = settings.getString(sessionID, SETTING_SOCKET_ACCEPT_ADDRESS);
        }
        int acceptorPort = (int) settings.getLong(sessionID, SETTING_SOCKET_ACCEPT_PORT);

        return new InetSocketAddress(acceptorHost, acceptorPort);
    }
    
    private List<TemplateMapping> getMappings(InetSocketAddress address) {
        return dynamicSessionMappings.computeIfAbsent(address, k -> new ArrayList<>());
    }
		
    private void start() throws RuntimeError, ConfigError {
        acceptor.start();
    }

    /**
     * Sends Logout (35=5) to all logged-on sessions when SendLogout_at_Shutdown=Y.
     * After each Logout, persists the next sender sequence to DB (when UseJdbcStore=Y) so the next
     * Logon uses 34=(N+1) instead of reusing 34=N (avoids duplicate MsgSeqNum in TRACE_FIX_MESSAGES_LOG).
     */
    private void sendLogoutToAllSessions() {
        if (sessionSettings == null) {
            log.info("SendLogout_at_Shutdown=Y: no settings, skipping Logout.");
            return;
        }
        try {
            Iterator<SessionID> it = sessionSettings.sectionIterator();
            int sent = 0;
            while (it.hasNext()) {
                SessionID sessionID = it.next();
                try {
                    Session session = Session.lookupSession(sessionID);
                    if (session != null && session.isLoggedOn()) {
                        // Before send: read current next sender seq so we can persist (current+1) after send (engine may not flush before process exit)
                        int nextSeqToPersist = -1;
                        if (sessionSequenceFromDB != null) {
                            SessionSequenceFromDB.SessionSequence dbSeq = sessionSequenceFromDB.getSessionSequence(sessionID);
                            if (dbSeq != null) {
                                nextSeqToPersist = dbSeq.outgoingSeqNum + 1;
                            }
                        }
                        Logout logout = new Logout();
                        logout.setField(new Text("Simulator shutting down"));
                        Session.sendToTarget(logout, sessionID);
                        sent++;
                        log.info("SendLogout_at_Shutdown=Y: sent Logout to initiator for session {}", sessionID);
                        if (sessionSequenceFromDB != null && nextSeqToPersist >= 1) {
                            sessionSequenceFromDB.updateOutgoingSeqNum(sessionID, nextSeqToPersist);
                            log.debug("SendLogout_at_Shutdown: persisted next sender seq {} for {} so next Logon uses 34={}.", nextSeqToPersist, sessionID, nextSeqToPersist);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not send Logout to {}: {}", sessionID, e.getMessage());
                }
            }
            if (sent == 0) {
                log.info("SendLogout_at_Shutdown=Y: no logged-on sessions to send Logout.");
            }
            if (sent > 0) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.info("SendLogout_at_Shutdown=Y: sent Logout to {} session(s), waiting 1.5s before stop.", sent);
            }
        } catch (Exception e) {
            log.warn("SendLogout_at_Shutdown: could not send Logout to sessions: {}", e.getMessage());
        }
    }

    /**
     * Stops the acceptor and closes the connection pool so the JVM can exit cleanly
     * (avoids lingering threads and IllegalThreadStateException when run via exec:java).
     * Safe to call multiple times.
     */
    public void stop() {
        if (stopped) {
            return;
        }
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
        }
        log.info("Shutting down FIX Simulator...");
        if (sendLogoutAtShutdown) {
            sendLogoutToAllSessions();
        } else {
            log.info("SendLogout_at_Shutdown=N: not sending Logout to initiator.");
        }
        try {
            jmxExporter.getMBeanServer().unregisterMBean(connectorObjectName);
        } catch (Exception e) {
            log.debug("Failed to unregister acceptor from JMX: {}", e.getMessage());
        }
        try {
            // When SendLogout_at_Shutdown=N, use stop(true) so QuickFIX/J does not send Logout (engine's stop() otherwise logs out sessions).
            // When Y we already sent Logout above; stop(false) lets the engine close cleanly.
            acceptor.stop(!sendLogoutAtShutdown);
            if (!sendLogoutAtShutdown) {
                log.info("SendLogout_at_Shutdown=N: acceptor stopped with forceDisconnect=true (no Logout sent by engine).");
            }
        } catch (Exception e) {
            log.debug("Acceptor stop: {}", e.getMessage());
        }
        if (jdbcDataSource != null && !jdbcDataSource.isClosed()) {
            jdbcDataSource.close();
            log.info("JDBC connection pool closed.");
        }
        log.info("FIX Simulator stopped.");
    }

    public static void main(String[] args) throws Exception {
        Simulator simulator = null;
        try {
            InputStream inputStream = getSettingsInputStream(args);
            SessionSettings settings = new SessionSettings(inputStream);
            inputStream.close();

            // Do not reject any message for "Required tag missing" — accept all and send business response
            forceNoRequiredTagReject(settings);

            String configResourceName = getConfigResourceName(args);
            simulator = new Simulator(settings, configResourceName);
            final Simulator instance = simulator;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    instance.stop();
                } catch (Exception e) {
                    log.error("Error during shutdown", e);
                }
            }, "Simulator-Shutdown"));
            simulator.start();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            if (simulator != null) {
                simulator.stop();
            }
            throw e;
        }
    }
    
    /**
     * Forces UseDataDictionary=N for every section so the engine never rejects a message
     * with "Required tag missing" for any field. All messages are passed to the app and
     * get a business response (primary and secondary, and when using an external config).
     * SessionSettings.sectionIterator() excludes the [DEFAULT] section, so we must set
     * it explicitly; otherwise sessions inherit UseDataDictionary=Y from default.
     */
    private static void forceNoRequiredTagReject(SessionSettings settings) {
        try {
            SessionID defaultSection = new SessionID("DEFAULT", "", "");
            settings.setString(defaultSection, "UseDataDictionary", "N");
            Iterator<SessionID> it = settings.sectionIterator();
            while (it.hasNext()) {
                SessionID sid = it.next();
                settings.setString(sid, "UseDataDictionary", "N");
            }
            log.info("UseDataDictionary=N forced for all sections (including DEFAULT): no 'Required tag missing' reject for any message");
        } catch (Exception e) {
            log.warn("Could not force UseDataDictionary=N: {}", e.getMessage());
        }
    }

    /** Returns config name/path for primary/secondary (for dump). Null when explicit path given in args. */
    private static String getConfigResourceName(String[] args) {
        if (args.length > 0 && !args[0].equalsIgnoreCase("primary") && !args[0].equalsIgnoreCase("--primary")
                && !args[0].equalsIgnoreCase("secondary") && !args[0].equalsIgnoreCase("--secondary"))
            return null;
        if (args.length == 0 || (args.length >= 1 && (args[0].equalsIgnoreCase("primary") || args[0].equalsIgnoreCase("--primary"))))
            return "quickfixj-server.cfg";
        return "quickfixj-server-secondary.cfg";
    }

    /**
     * Load config: prefer external file in current directory over JAR so Linux (or any host) can use
     * an updated config (e.g. LogonDelay=N) without rebuilding the JAR.
     * <ul>
     *   <li>primary: use ./quickfixj-server.cfg if it exists, else from JAR</li>
     *   <li>secondary: use ./quickfixj-server-secondary.cfg if it exists, else from JAR</li>
     *   <li>path given: use that path</li>
     * </ul>
     */
    private static InputStream getSettingsInputStream(String[] args) throws FileNotFoundException {
        InputStream inputStream = null;
        String source = null;
        if (args.length > 0 && !args[0].equalsIgnoreCase("primary") && !args[0].equalsIgnoreCase("--primary")
                && !args[0].equalsIgnoreCase("secondary") && !args[0].equalsIgnoreCase("--secondary")) {
            inputStream = new FileInputStream(args[0]);
            source = "file [" + args[0] + "]";
        } else if (args.length == 0 || (args[0].equalsIgnoreCase("primary") || args[0].equalsIgnoreCase("--primary"))) {
            File external = new File("quickfixj-server.cfg");
            if (external.isFile()) {
                inputStream = new FileInputStream(external);
                source = "file [quickfixj-server.cfg] (current directory, overrides JAR)";
            } else {
                inputStream = Simulator.class.getResourceAsStream("quickfixj-server.cfg");
                source = "JAR [quickfixj-server.cfg]";
            }
        } else {
            File external = new File("quickfixj-server-secondary.cfg");
            if (external.isFile()) {
                inputStream = new FileInputStream(external);
                source = "file [quickfixj-server-secondary.cfg] (current directory, overrides JAR)";
            } else {
                inputStream = Simulator.class.getResourceAsStream("quickfixj-server-secondary.cfg");
                source = "JAR [quickfixj-server-secondary.cfg]";
            }
        }
        if (inputStream == null) {
            log.error("Configuration file not found. Usage: java -jar fix-simulator.jar [primary|secondary|<path-to.cfg>]");
            System.err.println("Configuration file not found. Usage: java -jar fix-simulator.jar [primary|secondary|<path-to.cfg>]");
            System.exit(1);
        }
        String roleLabel = (args.length > 0 && (args[0].equalsIgnoreCase("secondary") || args[0].equalsIgnoreCase("--secondary"))) ? "SECONDARY" : "PRIMARY";
        log.info("Loading config from {} - {}", source, roleLabel);
        return inputStream;
    }
}
