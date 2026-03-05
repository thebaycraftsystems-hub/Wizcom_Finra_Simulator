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

    /** Uses in-memory lifecycle store (state not persisted to TRACE_LIFECYCLE_STATE). */
    public CompliancePipeline() {
        this(null);
    }

    /**
     * Uses the given store if non-null (e.g. JdbcLifecycleStore for TRACE_LIFECYCLE_STATE);
     * otherwise uses InMemoryLifecycleStore.
     */
    public CompliancePipeline(LifecycleStateStore store) {
        RejectCodes rejectCodes = new RejectCodes();
        this.specValidation = new SpecValidationEngine();
        this.lifecycleEngine = new LifecycleEngine(store != null ? store : new InMemoryLifecycleStore());
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
            log.debug("Pipeline field missing: {} — not rejecting (missing fields accepted).", e.getMessage());
            return false;
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
