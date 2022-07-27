package com.wizcom.fix.simulator;

import javax.management.JMException;
import javax.management.ObjectName;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

    private final JmxExporter jmxExporter;
    private final ObjectName connectorObjectName;
    
    
    public Simulator(SessionSettings settings) throws ConfigError, FieldConvertError, JMException {
    	
    	log.info("*************************************************************************************");
    	log.info("*							 WELCOME TO WIZCOM FIX SIMULATOR ");  
    	//log.info("*							 Version ["+settings.getString("WizFixSimlatorVersion")+"]										   *");
    	log.info("*							 Version [6.1.0]");
    	log.info("*************************************************************************************");
    	System.out.println("WELCOME TO WIZCOM FIX SIMULATOR \nVersion 6.1.0");
    	
    	WizFixApplication wizFixApplication = new WizFixApplication(settings);
		MessageStoreFactory messageStoreFactory = new FileStoreFactory( settings );
        boolean logToFile = false;
        boolean logToDB = false;
        boolean logToScreen = false;
        LogFactory logFactory;
        try {
            logToFile = settings.getBool("LogToFile");
            logToDB = settings.getBool("LogToDB");
            logToScreen = settings.getBool("LogToScreen");
        } catch (FieldConvertError ex) {}
        
        if ( logToFile && logToDB && logToScreen) {
            logFactory = new CompositeLogFactory( new LogFactory[] {new ScreenLogFactory(settings), new FileLogFactory(settings), new JdbcLogFactory(settings)});
        } else if ( logToFile && logToDB ) {
            logFactory = new CompositeLogFactory( new LogFactory[] { new FileLogFactory(settings), new JdbcLogFactory(settings)});
        } if ( logToFile && logToScreen) {
            logFactory = new CompositeLogFactory( new LogFactory[] { new ScreenLogFactory(settings), new FileLogFactory(settings)});
        } if ( logToDB && logToScreen) {
            logFactory = new CompositeLogFactory( new LogFactory[] { new ScreenLogFactory(settings), new JdbcLogFactory(settings)});
        } else if ( logToFile ) {
            logFactory = new CompositeLogFactory( new LogFactory[] { new FileLogFactory(settings)});
        } else if ( logToDB ) {
            logFactory = new CompositeLogFactory( new LogFactory[] { new JdbcLogFactory(settings)});
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

    private void stop() {
        try {
            jmxExporter.getMBeanServer().unregisterMBean(connectorObjectName);
        } catch (Exception e) {
            log.error("Failed to unregister acceptor from JMX", e);
        }
        acceptor.stop();
    }

    public static void main(String[] args) throws Exception {
        try {
            InputStream inputStream = getSettingsInputStream(args);
            SessionSettings settings = new SessionSettings(inputStream);
            inputStream.close();

            Simulator simulator = new Simulator(settings);
            simulator.start();

         //   System.out.println("press <enter> to quit");
         //   System.in.read();
         //   log.debug("Shutdown started........................................");
         //   System.out.println("Terminated.....................................");
         //   simulator.stop();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
    
    private static InputStream getSettingsInputStream(String[] args) throws FileNotFoundException {
        InputStream inputStream = null;
        if (args.length == 0) {            
        	inputStream = Simulator.class.getResourceAsStream("quickfixj-server.cfg");
        	log.info("Loading default file [quickfixj-server.cfg] as configuration file");
        } else if (args.length == 1) {
            inputStream = new FileInputStream(args[0]);
            log.info("Loading file ["+args[0]+"] as configuration file");
        }
        if (inputStream == null) {
        	log.info("Configuration file not found :: " + Simulator.class.getName() + " [configFile].");
            System.out.println("Configuration file not found :: " + Simulator.class.getName() + " [configFile].");
            System.exit(1);
        }
        return inputStream;
    }
}
