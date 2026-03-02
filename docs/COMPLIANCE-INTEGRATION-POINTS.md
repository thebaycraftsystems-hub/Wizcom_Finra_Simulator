# FINRA Compliance – Integration Points

Existing **DB message persistence is unchanged**. QuickFIX/J logs all incoming/outgoing messages via the configured LogFactory (JdbcLogFactory / FileLogFactory). No code in this project writes directly to TRACE_FIX_MESSAGES_LOG or TRACE_FIX_EVENT_LOG; that is done by the engine.

## Flow (unchanged persistence order)

```
Incoming FIX message
       ↓
[ QuickFIX/J receives and persists incoming – UNCHANGED ]
       ↓
fromApp(message, sessionID)
       ↓
CompliancePipeline.processIncoming()
  ├─ SpecValidationEngine.validate()  → if fail: send SPRE, return
  ├─ LifecycleEngine.evaluateAndTransition() → if fail: send SPRE, return
  └─ else: return false
       ↓
crack(message, sessionID)  → onMessage(TradeCaptureReport) etc.
       ↓
Existing business logic (processingTradeEntry, processingREJ, …)
       ↓
Session.sendToTarget(response, sessionID)
       ↓
[ QuickFIX/J persists outgoing – UNCHANGED ]
```

## What was added (no removal or reorder)

| Location | Change |
|----------|--------|
| **WizFixApplication.fromApp** | After existing `log.info`, call `compliancePipeline.processIncoming(arg0, arg1)`; if true, return; else `crack(arg0, arg1)` as before. |
| **WizFixApplication.processingTradeEntry** | After `Session.sendToTarget(resTrdCapRpt, sessionID)`, call `compliancePipeline.getLifecycleEngine().markAccepted(...)` so TS lifecycle state becomes ACCEPTED. |
| **New package** | `com.wizcom.fix.simulator.compliance`: SpecValidationEngine, LifecycleEngine, ResponseBuilder, CompliancePipeline, LifecycleStateStore (InMemoryLifecycleStore), ValidationResult, RejectCodes, LifecycleState. |
| **New config** | `src/main/resources/config/finra/*.yaml`: message_types, reject_codes, sp_validation_rules, ts_lifecycle_rules, ca_admin_rules. |
| **New table** | `TRACE_LIFECYCLE_STATE` (optional; lifecycle can use in-memory store). Script: `sql/trace_lifecycle_state_table.sql`. |

## What was not touched

- Simulator.java (LogFactory, JdbcStoreFactory, createJdbcDataSource, isJdbcStoreSchemaValid, etc.).
- Any JdbcLogFactory or session/message store usage.
- toApp / toAdmin (only application logging; DB dump remains in engine).
- Any existing Session.sendToTarget call order or arguments other than the one new call to send SPRE from ResponseBuilder when validation/lifecycle fails.
