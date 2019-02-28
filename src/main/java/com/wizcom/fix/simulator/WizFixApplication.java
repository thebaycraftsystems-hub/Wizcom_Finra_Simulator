/**
 * 
 */
package com.wizcom.fix.simulator;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import java.util.logging.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.ConfigError;
import quickfix.DataDictionary;
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
import quickfix.fix44.Logon;
import quickfix.fix44.Logout;
import quickfix.fix44.ResendRequest;
import quickfix.fix44.SequenceReset;
import quickfix.fix44.TradeCaptureReport;
import quickfix.fix44.TradeCaptureReportAck;
import quickfix.fix44.component.Instrument;

/**
 * @author subhash
 *
 */
public class WizFixApplication extends MessageCracker implements quickfix.Application {

	private static final Logger log = LoggerFactory.getLogger(WizFixApplication.class);
	private static int nextID = 1;
	private SessionSettings settings;
	private int heartbeatcount = 0;
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
		log.info("Recieved message from Gateway ::");
		log.info(arg0.toString());
	}
	
	public void toAdmin(Message arg0, SessionID arg1) {
		log.info("Sending message to Gateway ::");
		log.info(arg0.toString());
		
			try {
				crack(arg0, arg1);
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				e.printStackTrace();
			}
		
	}

	public void fromApp(Message arg0, SessionID arg1) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
		log.info("Request message received from Gateway ::");
		log.info(arg0.toString());
		crack(arg0, arg1);
	}

	public void toApp(Message msg, SessionID arg1) throws DoNotSend {
		log.info("Response message sending ::");
		log.info(msg.toString());
	}
			
	// TradeReportTransType : 0=new, 1=cancel, 2=Replace, ----------IN
	public void onMessage(TradeCaptureReport msg, SessionID sessionID) throws FieldNotFound, SessionNotFound {
		//log.info("message rescieved :: "+msg);	
		//REJECT processing ... send trade with price = 555 .. will send reject for that trades
		if(555 == msg.getLastPx().getValue()) {
			if("SP".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
				processingREJ(msg, sessionID, "SPEN");
			}else if("CA".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
				processingREJ(msg, sessionID, "CAEN");
			}else if("TS".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
				processingREJ(msg, sessionID, "TSEN");
			}
		}else {
			//NEW
			if (0 == msg.getTradeReportTransType().getValue()) {
				if("SP".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeEntry(msg, sessionID, "SPEN");
				}else if("CA".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeEntry(msg, sessionID, "CAEN");
				}else if("TS".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeEntry(msg, sessionID, "TSEN");
				}	
			}//CANCEL 
			else if (1 == msg.getTradeReportTransType().getValue()) {
				
				if("SP".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
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
				
			}//MODIFY
			else if (2 == msg.getTradeReportTransType().getValue()) {
				if("SP".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeCorrection(msg, sessionID, "SPCR");
				}else if("CA".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeCorrection(msg, sessionID, "CACR");
				}else if("TS".equalsIgnoreCase( msg.getHeader().getField(new TargetSubID()).getValue())) {
					processingTradeCorrection(msg, sessionID, "TSCR");
				}
				
			} else if (4 == msg.getTradeReportTransType().getValue()) {
				processingTradeReversal(msg, sessionID, "TSHX");
			}
		}
	}
	
	// REJECT
	private void processingREJ(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType) throws FieldNotFound, SessionNotFound {
		
		TradeCaptureReportAck resTrdCapRpt = new TradeCaptureReportAck();
		
		resTrdCapRpt.setField(new TradeReportID(getTrdRptID()));
		resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue()));
		
		
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
		resTrdCapRpt.setField(new TradeReportRejectReason(4005));
		
		resTrdCapRpt.setField(new Text("!REJ - INVALID PRICE"));
		

		resTrdCapRpt.reverseRoute(reqTrdCapRpt.getHeader());
		
		resTrdCapRpt.getHeader().setField(new MsgType("AR"));
		
		Session.sendToTarget(resTrdCapRpt, sessionID);
	}
	
	// NEW
	private void processingTradeEntry(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType) {

		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();

		try {
			resTrdCapRpt = reqTrdCapRpt;
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID

			resTrdCapRpt.setField(new MessageEventSource(securityType)); // MessageEventSource
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
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
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
			
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID

			resTrdCapRpt.setField(new MessageEventSource(securityType)); // MessageEventSource
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
			
		} catch (SessionNotFound sessionNotFound) {
			sessionNotFound.printStackTrace();
		} catch (FieldNotFound ex) {
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	// CANCEL
	private void processingTradeCancel(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType)
			throws FieldNotFound, SessionNotFound {
		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();

		try {
			resTrdCapRpt = reqTrdCapRpt;
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID

			resTrdCapRpt.setField(new MessageEventSource(securityType)); // MessageEventSource
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
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	//Historic
	private void processingTradeReversal(TradeCaptureReport reqTrdCapRpt, SessionID sessionID, String securityType) {
		TradeCaptureReport resTrdCapRpt = new TradeCaptureReport();

		try {
			resTrdCapRpt = reqTrdCapRpt;
			resTrdCapRpt.setField(new TradeReportRefID(reqTrdCapRpt.getTradeReportID().getValue())); // TradeReportRefID

			resTrdCapRpt.setField(new MessageEventSource(securityType)); // MessageEventSource
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
		} catch (FieldNotFound ex) {
			java.util.logging.Logger.getLogger(WizFixApplication.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public void onMessage(Message msg, SessionID sessionID) {
		
	}
	
	public void onMessage(Heartbeat msg, SessionID sessionID) throws FieldNotFound {
		
		// HearbeatDelay=True / false
		boolean heartBtDelay;
		heartbeatcount++;
		try {
			heartBtDelay = settings.getBool("HeartBtDelay");
			
			if(heartBtDelay && heartbeatcount==10) {
				heartbeatcount=0;
				System.out.println("\"Select Option to Continue :: \n"+
				  "1. Delay Heartbeat by 10 secs \n"+
				  "2. Send Logout \n"+
				  "3. Send ResetSeq Msg \n"+
				  "4. Send ResendRequest \n"+
				  "5. Continue .. ");			 

				log.info("Select Option to Continue");
				
				Scanner myOption = new Scanner(System.in);  // Create a Scanner object
	            switch (myOption.nextInt()) {
	            case 1:
	            	log.info("Selected Option 1 :: waiting 10 sec to send HEARTBEAT.....");
	            	Thread.sleep(10000);
	            	
	            	log.info(msg.toString());            	
	                Session.sendToTarget(msg, sessionID);
	            	break; 
	            
	            case 2: 
	            	log.info("Selected Option 2 :: sending logout.....");
	            	Logout logout = new Logout();
	            	//logout.reverseRoute(msg.getHeader());
	            	logout.setString(58, "User Selected Logout");
	            	
	            	log.info(logout.toString());
	            	Session.sendToTarget(logout, sessionID);
	                break;
	                
	            case 3: 
	            	log.info("Selected Option 3 :: Sending Sequence Reset.....");
	            	SequenceReset seqReset = new SequenceReset();
	            	//seqReset.reverseRoute(msg.getHeader());
	            	seqReset.setString(123, "Y");
	            	
	            	int nextSeqNo = Session.lookupSession(sessionID).getExpectedSenderNum();
	            	int resetSeqNo = nextSeqNo + 20;
	            	Session.lookupSession(sessionID).setNextSenderMsgSeqNum(resetSeqNo);
	            	seqReset.setInt(36, Session.lookupSession(sessionID).getExpectedSenderNum());
	            	
	            	log.info(seqReset.toString());
	            	Session.sendToTarget(seqReset, sessionID);
	                break; 
	            
	            case 4: 
	            	log.info("Selected Option 4 :: Sending Resend Request.....");
	            	ResendRequest resend = new ResendRequest();
	            	int endSeqNo = Session.lookupSession(sessionID).getExpectedTargetNum()-1;
	            	int beginSeqNo = endSeqNo-10;
	            	
	            	// If endSeqNo  is <=0, then set endSeqNo  = 0
	            	endSeqNo = endSeqNo <=0 ? 0 : endSeqNo;
	            	
	            	// If beginSeqNo is <=0, then set beginSeqNo = 1
	            	beginSeqNo = beginSeqNo <=0 ? 1 : beginSeqNo;
	            	
	            	//resend.reverseRoute(msg.getHeader());
	            	resend.setInt(7, beginSeqNo);
	            	resend.setInt(16, endSeqNo);	            	
	            	log.info(resend.toString());
	            	Session.sendToTarget(resend, sessionID);
	                break; 
	                
	            default: 
	            	//msg.reverseRoute(msg.getHeader());
	            	Session.sendToTarget(msg, sessionID);
	                break; 
	            }        
			}		
			
		} catch (ConfigError e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (FieldConvertError e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception e) {
			// TODO Auto-generated catch block
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
		
		
		if("TSEN".equalsIgnoreCase( secType) || "TSCX".equalsIgnoreCase( secType)) {
			return "7"+Long.valueOf((nextID++) * System.nanoTime()).toString().substring(0, 9);
		}else {
			return "1"+Long.valueOf((nextID++) * System.nanoTime()).toString().substring(0, 9);
		}
		
	}
		 
	private static class TradeRptStatus extends IntField {
		static final long serialVersionUID = 20050620;

		public static final int FIELD = 939;
		public static final int REJECTED = 1;

		public TradeRptStatus() {
			super(939);
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