package com.wizcom.fix.simulator.compliance;

import quickfix.SessionID;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory lifecycle state store. Lifecycle only; does not touch message dump tables.
 */
public final class InMemoryLifecycleStore implements LifecycleStateStore {
    private final Map<String, LifecycleState> stateByKey = new ConcurrentHashMap<>();

    private static String key(String tradeReportId, SessionID sessionID) {
        return sessionID + "|" + tradeReportId;
    }

    @Override
    public LifecycleState get(String tradeReportId, SessionID sessionID) {
        return stateByKey.get(key(tradeReportId, sessionID));
    }

    @Override
    public void put(String tradeReportId, SessionID sessionID, LifecycleState state) {
        stateByKey.put(key(tradeReportId, sessionID), state);
    }

    @Override
    public boolean contains(String tradeReportId, SessionID sessionID) {
        return stateByKey.containsKey(key(tradeReportId, sessionID));
    }
}
