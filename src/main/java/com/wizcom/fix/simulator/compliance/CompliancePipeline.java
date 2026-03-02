package com.wizcom.fix.simulator.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.fix44.TradeCaptureReport;

/**
 * FINRA compliance pipeline: Spec Validation -> Lifecycle Engine.
 * Does not modify existing DB message persistence (handled by QuickFIX/J before/after this).
 * If validation or lifecycle fails, sends SPRE and returns true (caller must not crack).
 */
public final class CompliancePipeline {
    private static final Logger log = LoggerFactory.getLogger(CompliancePipeline.class);

    private final SpecValidationEngine specValidation;
    private final LifecycleEngine lifecycleEngine;
    private final ResponseBuilder responseBuilder;

    public CompliancePipeline() {
        RejectCodes rejectCodes = new RejectCodes();
        this.specValidation = new SpecValidationEngine();
        this.lifecycleEngine = new LifecycleEngine(new InMemoryLifecycleStore());
        this.responseBuilder = new ResponseBuilder(rejectCodes);
    }

    /**
     * Run validation and lifecycle. If either fails, send SPRE and return true (handled).
     * Otherwise return false so caller proceeds to crack() and existing business logic.
     */
    public boolean processIncoming(Message message, SessionID sessionID) {
        try {
            ValidationResult specResult = specValidation.validate(message, sessionID);
            if (!specResult.isValid()) {
                log.warn("Spec validation failed: {} - {}", specResult.getRejectCode(), specResult.getRejectText());
                responseBuilder.sendSPRE(message, sessionID, specResult);
                return true;
            }
            if (message instanceof TradeCaptureReport) {
                ValidationResult lifeResult = lifecycleEngine.evaluateAndTransition((TradeCaptureReport) message, sessionID);
                if (!lifeResult.isValid()) {
                    log.warn("Lifecycle transition failed: {} - {}", lifeResult.getRejectCode(), lifeResult.getRejectText());
                    responseBuilder.sendSPRE(message, sessionID, lifeResult);
                    return true;
                }
            }
            return false;
        } catch (FieldNotFound e) {
            log.debug("Pipeline field error: {}", e.getMessage());
            try {
                responseBuilder.sendSPRE(message, sessionID, 4062, "DATA TYPE VIOLATION");
            } catch (Exception e2) {
                log.warn("Could not send SPRE: {}", e2.getMessage());
            }
            return true;
        } catch (SessionNotFound e) {
            log.warn("Session not found when sending SPRE: {}", e.getMessage());
            return true;
        } catch (Exception e) {
            log.error("Compliance pipeline error", e);
            return false;
        }
    }

    public LifecycleEngine getLifecycleEngine() {
        return lifecycleEngine;
    }
}
