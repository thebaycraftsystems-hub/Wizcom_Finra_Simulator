/**
 * 
 */
package com.wizcom.fix.simulator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysql.jdbc.Field;

import quickfix.ConfigError;
import quickfix.DoNotSend;
import quickfix.FieldConvertError;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.IntField;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.StringField;
import quickfix.UnsupportedMessageType;
import quickfix.field.*;
import quickfix.fix44.Heartbeat;
import quickfix.fix44.TestRequest;
import quickfix.fix44.TradeCaptureReport;
import quickfix.fix44.TradeCaptureReportAck;
import quickfix.fix44.component.Instrument;
import quickfix.fix44.component.Parties;

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
	
		
//    private SessionID currentSession;
//    private DataDictionary dictionary;
    
    public SessionSettings getSettings() {
        return settings;
    }
    
	public WizFixApplication() {	}

	public WizFixApplication(SessionSettings settings) throws ConfigError, FieldConvertError {
		this.settings = settings;
		// initializeValidOrderTypes(settings);
		// initializeMarketDataProvider(settings);
		// alwaysFillLimitOrders = settings.isSetting(ALWAYS_FILL_LIMIT_KEY) &&
		// settings.getBool(ALWAYS_FILL_LIMIT_KEY);
		//System.out.println("WizFixSimlatorVersion 5");
		//log.info("WizFixSimlatorVersion ["+5+"]");	
		
		if(settings.getBool("TraceNotAvailable")){
			log.info("TraceNotAvailable Task started on " + new Date());
			TimerTask repeatedTask = new TimerTask() {
				public void run() {
					if(traceNotAvailable) {
						traceNotAvailable=false;
						log.info("TraceNotAvailable flag set to [false] ");
					}else {
						traceNotAvailable=true;
						log.info("TraceNotAvailable flag set to [true] ");
					}					
		        }
		    };
		    Timer timer = new Timer("Timer");
		     
		    long delay  = 1000L;
		    long period = 1000L * settings.getInt("TraceNotAvailableIntervel");
		    timer.scheduleAtFixedRate(repeatedTask, delay, period);	
		}
	}

	/**
	 * @param messageHandler
	 */
	public WizFixApplication(Object messageHandler) {
		super(messageHandler);
	}
	
	public void onCreate(SessionID arg0) {	}

	public void onLogon(SessionID arg0) {	}

	public void onLogout(SessionID arg0) {	}
		
	public void fromAdmin(Message arg0, SessionID arg1) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
		log.info("Recieved Admin message from Gateway :: "+arg0.toString());		
	}
	
	public void toAdmin(Message arg0, SessionID arg1) {
	//	log.info("Sending message to Gateway ::");
	//	log.info(arg0.toString());		
		try {			
			crack(arg0, arg1);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			e.printStackTrace();
		}
	}

	public void fromApp(Message arg0, SessionID arg1) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
		log.info("Request message received from Gateway :: [ "+ arg0.toString() +" ]");
		crack(arg0, arg1);
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
		
		responseMsgDelay = settings.getBool("ResponseMsgDelay"); 
		
		if (responseMsgDelay) {
			System.out.println();
			try {
				int myOption = settings.getInt("ResponseMsgDelayTime");
				if(myOption > 0 ) {
					System.out.println("Response processing delay time is :: "+myOption);
					Thread.sleep(myOption * 1000);
					onMessageHold(msg, sessionID);
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("Invalid enter, response continue....");
				onMessageHold(msg, sessionID);
			}
			
		}else {
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
		
		Session.sendToTarget(resTrdCapRpt, sessionID);
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
			
			Session.sendToTarget(resTrdCapRpt, sessionID);
			
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
			// resTrdCapRpt.getHeader().setField(new
			// SendingTime(LocalDateTime.now(ZoneOffset.UTC)));
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
			// resTrdCapRpt.getHeader().setField(new
			// SendingTime(LocalDateTime.now(ZoneOffset.UTC)));
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
			
			Session.sendToTarget(resTrdCapRpt, sessionID);
			
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
			log.error("ERROR :: ",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
		
	}
	
	
	private quickfix.fix44.TradeCaptureReport.NoSides buildSidesGroup(Character bsindicator, String partyID, int partyRole)
			{
		quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
		
		
		try {
		

		sidesGroup.set(new Side(bsindicator));
		sidesGroup.set(new OrderID("none"));

		// NoPartyIds subgroup
		quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs partyIdsGroup = new quickfix.fix44.TradeCaptureReport.NoSides.NoPartyIDs();

		partyIdsGroup.set(new PartyID(partyID));
		partyIdsGroup.set(new PartyRole(partyRole));
		sidesGroup.addGroup(partyIdsGroup);
		
		}catch (Exception ex) {
			log.error("ERROR :: ",ex);
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
			
		}
		return sidesGroup;

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
				try {
					heartBtDelay = settings.getBool("HeartBtDelay");
					heartBtDelayCount = settings.getInt("HeartBtDelayCount");
					
					//System.out.println("HeartBtDelay :: "+heartBtDelay + " \t HeartBtDelayCount :: "+heartBtDelayCount + "Current HeartBtCount :: "+heartbeatcount);
					log.info("HeartBtDelay :: "+heartBtDelay + " \t HeartBtDelayCount :: "+heartBtDelayCount + "Current HeartBtCount :: "+heartbeatcount);
					
					if (heartBtDelay && heartbeatcount == heartBtDelayCount) {
						if(settings.getInt("HeartBtDelayTime") > 0 ) {
							//System.out.println("Heart Beat Delay Time is ["+settings.getInt("HeartBtDelayTime")+"]");
							log.info("Heart Beat Delay Time is ["+ settings.getInt("HeartBtDelayTime") +"]");
							Thread.sleep(settings.getInt("HeartBtDelayTime") * 1000);
						}else {
							//System.out.println("Defalut Heart Beat Delay Time ["+ 15 +"] setting ...");
							log.info("Defalut Heart Beat Delay Time is ["+ 15 +"] setting ...");
							Thread.sleep(15 * 1000);
						}		
						
						log.info("Heartbeat message from FINRA to Gateway is ::" + msg.toString());
						heartbeatcount = 0;
						
					} 
				}catch (Exception exe) {
					//System.out.println("Invalid or no option selected. Processing Heartbeat message \n\n");
					log.info("Invalid or no option selected. Processing Heartbeat message");				
				}
				
				log.info("Sending Heartbeat message from FINRA to Gateway :: SeqNumber [" + msg.getHeader().getField(new MsgSeqNum()) + "] " + msg.toString());

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