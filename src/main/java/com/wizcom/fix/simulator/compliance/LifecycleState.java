package com.wizcom.fix.simulator.compliance;

/**
 * TS lifecycle state. Stored in TRACE_LIFECYCLE_STATE only (not in message dump tables).
 */
public enum LifecycleState {
    NEW_SUBMITTED,
    ACCEPTED,
    MATCHED,
    DNR,
    CORRECTED,
    CANCELLED,
    REJECTED,
    IGR3_TRIGGERED
}
