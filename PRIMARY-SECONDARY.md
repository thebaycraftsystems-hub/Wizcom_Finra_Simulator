# Primary and Secondary (Backup) Simulator — FINRA-style

This document describes how the **Primary** and **Secondary (Backup)** simulators work, share the same database, and handle sequence numbers like FINRA primary/secondary.

---

## 1. Overview

- **Primary Simulator**: Uses `quickfixj-server.cfg`; listens on ports **64034**, **64093**, **64094**.
- **Secondary Simulator**: Uses `quickfixj-server-secondary.cfg`; listens on ports **64134**, **64193**, **64194**.
- **Same FIX session identity**: Both configs use the same `SenderCompID=FNRA`, `TargetCompID=JPMS`, and the same session qualifiers (SP/44B1, CA/44B2, TS/44B3). So the **session ID** (e.g. FIX.4.4:FNRA/SP->JPMS/44B1) is identical for Primary and Secondary.
- **Same database**: Both use the same JDBC URL and the same tables (`TRACE_FIX_SESSIONS`, `TRACE_FIX_MESSAGES`, and log tables). Session state and sequence numbers are stored there.

**Deployment rule:** `JdbcURL`, `JdbcUser`, `JdbcPassword`, `JdbcStoreSessionsTableName`, and `JdbcStoreMessagesTableName` in `quickfixj-server-secondary.cfg` must match Primary (only `FileStorePath`, `FileLogPath`, ports, and `SimulatorRole` differ). If Secondary pointed at a different database, MsgSeqNum would diverge on failover. On startup with `UseJdbcStore=Y`, the JVM logs a **SHARED SEQ DB** line with the effective JDBC URL and sessions table.

---

## 2. How sequence numbers are handled

- QuickFIX/J identifies a session in the store by: BeginString, SenderCompID, SenderSubID, SenderLocID, TargetCompID, TargetSubID, TargetLocID, SessionQualifier. **Socket port is not part of the session key.**
- Primary and Secondary use the **same** SessionID in config, so they both read/write the **same** row in `TRACE_FIX_SESSIONS` and the same message rows in `TRACE_FIX_MESSAGES`.
- When the **initiator (gateway)** connects to **Primary**, Primary’s acceptor owns that TCP connection and uses the shared DB for incoming/outgoing seq nums. When Primary goes down and the initiator reconnects to **Secondary**, Secondary’s acceptor gets the connection and loads the **same** session state (incoming_seqnum, outgoing_seqnum) from the DB. So **sequence numbers continue** across failover—no reset, FINRA-style.

**On Logon (whichever is active — Primary or Secondary):** The simulator must (1) check the database and load session state from `TRACE_FIX_SESSIONS`, (2) use the expected seq from DB (`incoming_seqnum`) so we do not assume 1 after restart, (3) if there is a gap (incoming MsgSeqNum > expected), send ResendRequest for the missing range and wait for the initiator to resend, (4) otherwise accept the Logon and continue. This is achieved with **UseJdbcStore=Y** and **RefreshOnLogon=Y**; QuickFIX/J then sends ResendRequest automatically when it detects a gap.

---

## 3. What to run

- **Primary only** (single instance):  
  From the **project root**:  
  `java -jar target\fix-simulator.jar`  
  or  
  `java -jar target\fix-simulator.jar primary`  
  Or double‑click **run-primary.bat** (builds the jar if needed, then starts Primary).

- **Secondary only** (e.g. backup on another host):  
  From the **project root** (where `pom.xml` is):  
  `java -jar target\fix-simulator.jar secondary`  
  Or double‑click **run-secondary.bat** (builds the jar if needed, then starts Secondary).

- **Both** (e.g. Primary and Secondary on same or different hosts):  
  Run two JVMs: one with `java -jar target\fix-simulator.jar primary` (or **run-primary.bat**), one with `secondary` (or **run-secondary.bat**). Only the one that receives the gateway connection will have an active session; the other will just be listening. When Primary dies, the gateway fails over to Secondary, which then uses the shared DB and continues the session.

---

## 4. Config differences (Primary vs Secondary)

| Setting            | Primary                      | Secondary                          |
|--------------------|-----------------------------|------------------------------------|
| Config file        | quickfixj-server.cfg        | quickfixj-server-secondary.cfg     |
| SimulatorRole      | (not set)                   | Secondary                          |
| FileStorePath      | data                        | data-secondary                     |
| FileLogPath        | logs                        | logs-secondary                     |
| SocketAcceptPort (SP) | 64034                    | 64134                              |
| SocketAcceptPort (CA) | 64093                    | 64193                              |
| SocketAcceptPort (TS) | 64094                    | 64194                              |
| JdbcURL, tables    | Same                        | Same                               |
| Session IDs        | Same (FNRA/SP->JPMS/44B1 etc.) | Same                              |

All other behavior (LogonRequired, HeartBeat_Required, DB persistence, etc.) is the same unless you edit either config.

---

## 5. Initiator (gateway) configuration for failover

Configure the initiator to try **Primary** first and **Secondary** on failure. How you do this depends on your FIX engine (e.g. SocketConnectHost/SocketConnectPort for primary, and a second pair or a list of backup addresses for secondary). The simulator does not require any special FIX fields; it just listens on different ports for Primary vs Secondary.

---

## 6. Summary

- **Primary** and **Secondary** are the same codebase; only the **config file** (and thus ports and file paths) differ.
- They share the **same database** and **same session identity**, so **sequence numbers are preserved** on failover.
- Run Primary and/or Secondary as needed; the gateway connects to one at a time, and the shared DB keeps session state consistent.
