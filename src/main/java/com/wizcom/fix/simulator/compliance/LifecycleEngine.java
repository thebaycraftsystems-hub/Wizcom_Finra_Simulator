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
     * Evaluate transition for incoming TradeCaptureReport. Never reject (only reject when 31 > 10000 in SpecValidationEngine).
     */
    public ValidationResult evaluateAndTransition(TradeCaptureReport msg, SessionID sessionID) {
        try {
            String tradeReportId = msg.getTradeReportID().getValue();
            int transType = msg.getTradeReportTransType().getValue();
            LifecycleState current = store.get(tradeReportId, sessionID);

            if (transType == 0) {
                store.put(tradeReportId, sessionID, LifecycleState.NEW_SUBMITTED);
                return ValidationResult.ok();
            }
            if (transType == 2) {
                store.put(tradeReportId, sessionID, LifecycleState.CORRECTED);
                return ValidationResult.ok();
            }
            if (transType == 1) {
                store.put(tradeReportId, sessionID, LifecycleState.CANCELLED);
                return ValidationResult.ok();
            }
            if (transType == 4) {
                store.put(tradeReportId, sessionID, LifecycleState.CANCELLED);
                return ValidationResult.ok();
            }
            return ValidationResult.ok();
        } catch (FieldNotFound e) {
            log.debug("Lifecycle field missing: {} — not rejecting.", e.getMessage());
            return ValidationResult.ok();
        }
    }

    /**
     * Call when simulator accepts a NEW (sends SPEN). Updates state to ACCEPTED.
     */
    public void markAccepted(String tradeReportId, SessionID sessionID) {
        store.put(tradeReportId, sessionID, LifecycleState.ACCEPTED);
    }
}
