package com.wizcom.fix.simulator;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.sql.DataSource;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
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

    private final JmxExporter jmxExporter;
    private final ObjectName connectorObjectName;
    
    
    public Simulator(SessionSettings settings) throws ConfigError, FieldConvertError, JMException {
    	
    	String role = "PRIMARY";
    	try {
    	    if (settings.isSetting("SimulatorRole")) role = settings.getString("SimulatorRole").toUpperCase();
    	} catch (Exception ignored) {}
    	log.info("*************************************************************************************");
    	log.info("* WELCOME TO WIZCOM FIX SIMULATOR — Role: {} ", role);
    	log.info("* Version [6.0.0]");
    	log.info("*************************************************************************************");
    	System.out.println("WELCOME TO WIZCOM FIX SIMULATOR — Role: " + role + "\nVersion 6.0.0");
    	
    	WizFixApplication wizFixApplication = new WizFixApplication(settings);
        boolean logToFile = false;
        boolean logToDB = false;
        boolean logToScreen = false;
        boolean useJdbcStore = false;
        try {
            logToFile = settings.getBool("LogToFile");
            logToDB = settings.getBool("LogToDB");
            logToScreen = settings.getBool("LogToScreen");
            useJdbcStore = settings.getBool("UseJdbcStore");
        } catch (FieldConvertError ex) {}

        // Use HikariCP DataSource when DB is enabled to avoid Proxool + signed SQL Server driver conflict
        jdbcDataSource = (useJdbcStore || logToDB) ? createJdbcDataSource(settings) : null;
        if (useJdbcStore && jdbcDataSource != null && !isJdbcStoreSchemaValid(jdbcDataSource, settings)) {
            log.warn("JDBC store schema invalid (e.g. missing senderlocid/targetlocid). Run sql/quickfixj_sqlserver_schema.sql in trace_fix. Falling back to file store.");
            useJdbcStore = false;
        }
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
            log.debug("JDBC store schema check failed: {}", e.getMessage());
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
            log.info("Using JDBC message store (sessions and messages in database)");
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
        try {
            jmxExporter.getMBeanServer().unregisterMBean(connectorObjectName);
        } catch (Exception e) {
            log.debug("Failed to unregister acceptor from JMX: {}", e.getMessage());
        }
        try {
            acceptor.stop();
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

            simulator = new Simulator(settings);
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
    
    private static InputStream getSettingsInputStream(String[] args) throws FileNotFoundException {
        InputStream inputStream = null;
        if (args.length == 0 || (args.length >= 1 && (args[0].equalsIgnoreCase("primary") || args[0].equalsIgnoreCase("--primary")))) {
            inputStream = Simulator.class.getResourceAsStream("quickfixj-server.cfg");
            log.info("Loading [quickfixj-server.cfg] — PRIMARY simulator");
        } else if (args.length >= 1 && (args[0].equalsIgnoreCase("secondary") || args[0].equalsIgnoreCase("--secondary"))) {
            inputStream = Simulator.class.getResourceAsStream("quickfixj-server-secondary.cfg");
            log.info("Loading [quickfixj-server-secondary.cfg] — SECONDARY (Backup) simulator, same DB as Primary");
        } else {
            inputStream = new FileInputStream(args[0]);
            log.info("Loading file [" + args[0] + "] as configuration file");
        }
        if (inputStream == null) {
            log.error("Configuration file not found. Usage: java -jar fix-simulator.jar [primary|secondary|<path-to.cfg>]");
            System.err.println("Configuration file not found. Usage: java -jar fix-simulator.jar [primary|secondary|<path-to.cfg>]");
            System.exit(1);
        }
        return inputStream;
    }
}
