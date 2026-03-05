package com.wizcom.fix.simulator.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.MsgType;
import quickfix.fix44.TradeCaptureReport;

/**
 * Validates application messages (e.g. 35=AE TradeCaptureReport).
 * Specification-based required/conditional field checks are disabled: we do not read docs/Specification
 * or config required_tags; we do not reject when fields are missing or invalid.
 */
public class SpecValidationEngine {
    private static final Logger log = LoggerFactory.getLogger(SpecValidationEngine.class);

    /**
     * Validate application message. For 35=AE (TradeCaptureReport) runs SP rules.
     * Other message types pass (admin handled elsewhere).
     */
    public ValidationResult validate(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getField(new MsgType()).getValue();
            if (!"AE".equals(msgType)) {
                return ValidationResult.ok();
            }
            return validateTradeCaptureReport((TradeCaptureReport) message, sessionID);
        } catch (FieldNotFound e) {
            log.debug("Validation field missing: {} — not rejecting.", e.getMessage());
            return ValidationResult.ok();
        } catch (Exception e) {
            log.debug("Validation error: {} — not rejecting (only reject when 31 > 10000).", e.getMessage());
            return ValidationResult.ok();
        }
    }

    /** Reject only when 31 (LastPx) > 10000. Do not reject for tag not found or anything else (Primary and Secondary). */
    private static final double MAX_LAST_PX = 10000.0;

    private ValidationResult validateTradeCaptureReport(TradeCaptureReport msg, SessionID sessionID) throws FieldNotFound {
        if (msg.isSetField(31)) {
            double lastPx = msg.getLastPx().getValue();
            if (lastPx > MAX_LAST_PX) {
                log.warn("Rejecting trade: 31 (LastPx) = {} > {} from Gateway", lastPx, MAX_LAST_PX);
                return ValidationResult.fail(4066, "TRADE REJECTED: 31 (LastPx) must not exceed 10000");
            }
        }
        return ValidationResult.ok();
    }
}
