package com.wizcom.fix.simulator.compliance;

import quickfix.SessionID;

/**
 * Stores lifecycle state per trade (TradeReportID/session). Separate from message dump tables.
 */
public interface LifecycleStateStore {
    LifecycleState get(String tradeReportId, SessionID sessionID);
    void put(String tradeReportId, SessionID sessionID, LifecycleState state);
    boolean contains(String tradeReportId, SessionID sessionID);
}
