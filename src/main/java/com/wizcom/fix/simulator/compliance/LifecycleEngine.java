package com.wizcom.fix.simulator.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FieldNotFound;
import quickfix.SessionID;
import quickfix.fix44.TradeCaptureReport;

/**
 * TS lifecycle engine: deterministic state machine. Uses ts_lifecycle_rules.yaml logic.
 * State stored in LifecycleStateStore only (separate from message dump).
 */
public class LifecycleEngine {
    private static final Logger log = LoggerFactory.getLogger(LifecycleEngine.class);

    private final LifecycleStateStore store;

    public LifecycleEngine(LifecycleStateStore store) {
        this.store = store;
    }

    /**
     * Evaluate transition for incoming TradeCaptureReport. Returns fail if transition invalid.
     */
    public ValidationResult evaluateAndTransition(TradeCaptureReport msg, SessionID sessionID) {
        try {
            String tradeReportId = msg.getTradeReportID().getValue();
            int transType = msg.getTradeReportTransType().getValue();
            LifecycleState current = store.get(tradeReportId, sessionID);

            if (transType == 0) {
                if (current == LifecycleState.DNR) {
                    return ValidationResult.fail(4063, "DNR - SECOND NEW NOT ALLOWED");
                }
                if (current == LifecycleState.NEW_SUBMITTED || current == LifecycleState.ACCEPTED) {
                    return ValidationResult.fail(4053, "DUPLICATE TRADE REPORT ID");
                }
                store.put(tradeReportId, sessionID, LifecycleState.NEW_SUBMITTED);
                return ValidationResult.ok();
            }
            if (transType == 2) {
                if (current != LifecycleState.ACCEPTED && current != LifecycleState.NEW_SUBMITTED) {
                    return ValidationResult.fail(4064, "CORRECTION ONLY AFTER ACCEPTED NEW");
                }
                store.put(tradeReportId, sessionID, LifecycleState.CORRECTED);
                return ValidationResult.ok();
            }
            if (transType == 1) {
                if (current == null) {
                    return ValidationResult.fail(4055, "INVALID LIFECYCLE TRANSITION");
                }
                store.put(tradeReportId, sessionID, LifecycleState.CANCELLED);
                return ValidationResult.ok();
            }
            if (transType == 4) {
                store.put(tradeReportId, sessionID, LifecycleState.CANCELLED);
                return ValidationResult.ok();
            }
            return ValidationResult.ok();
        } catch (FieldNotFound e) {
            log.debug("Lifecycle field missing: {}", e.getMessage());
            return ValidationResult.fail(4058, "MISSING REQUIRED FIELD");
        }
    }

    /**
     * Call when simulator accepts a NEW (sends SPEN). Updates state to ACCEPTED.
     */
    public void markAccepted(String tradeReportId, SessionID sessionID) {
        store.put(tradeReportId, sessionID, LifecycleState.ACCEPTED);
    }
}
