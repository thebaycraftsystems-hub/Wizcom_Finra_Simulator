package com.wizcom.fix.simulator.compliance;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;
import quickfix.fix44.TradeCaptureReport;
import quickfix.fix44.TradeCaptureReportAck;
import quickfix.IntField;

/**
 * Builds and sends SPRE (35=AR TradeCaptureReportAck) to Trace gateway (initiator) with reject reason.
 * Only outgoing responses to initiator; does not modify incoming messages or DB persistence.
 */
public final class ResponseBuilder {

    private final RejectCodes rejectCodes;

    public ResponseBuilder(RejectCodes rejectCodes) {
        this.rejectCodes = rejectCodes;
    }

    /**
     * Send TradeCaptureReportAck (AR) with ExecType=REJECTED, TradeRptStatus=REJECTED.
     */
    public void sendSPRE(Message request, SessionID sessionID, int rejectCode, String rejectText) throws FieldNotFound, SessionNotFound {
        if (!(request instanceof TradeCaptureReport)) {
            return;
        }
        TradeCaptureReport req = (TradeCaptureReport) request;
        TradeCaptureReportAck ack = new TradeCaptureReportAck();

        ack.setField(new TradeReportID(req.getTradeReportID().getValue()));
        if (req.isSetField(new TradeReportRefID())) {
            ack.setField(new TradeReportRefID(req.getTradeReportID().getValue()));
        }
        ack.setField(new TradeReportTransType(req.getTradeReportTransType().getValue()));
        ack.setField(new TradeReportType(req.getTradeReportType().getValue()));
        ack.setField(new ExecType(ExecType.REJECTED));
        ack.setField(new IntField(750, 2)); // TradeRequestStatus REJECTED (required for AR)
        ack.setField(new IntField(939, 1)); // TrdRptStatus REJECTED
        ack.set(req.getInstrument());
        ack.setField(new TradeReportRejectReason(rejectCode));
        ack.setField(new Text(rejectText != null ? rejectText : rejectCodes.getText(rejectCode)));
        ack.reverseRoute(req.getHeader());
        ack.getHeader().setField(new MsgType("AR"));

        Session.sendToTarget(ack, sessionID);
    }

    public void sendSPRE(Message request, SessionID sessionID, ValidationResult result) throws FieldNotFound, SessionNotFound {
        if (result.isValid()) return;
        sendSPRE(request, sessionID, result.getRejectCode(), result.getRejectText());
    }
}
