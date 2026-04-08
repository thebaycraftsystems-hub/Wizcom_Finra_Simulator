# quickfixj-server.cfg — Field Reference

This document describes **every** setting in `quickfixj-server.cfg`: what it does and how it is used (QuickFIX/J engine vs simulator application).

---

## Config structure

- **[default]** — Global settings applied to all sessions. Required for simulator-specific and JDBC settings.
- **[session]** — One block per session (e.g. SP/44B1, CA/44B2, TS/44B3). Session blocks inherit [default] and override with session-specific values (e.g. TargetSubID, SenderSubID, SocketAcceptPort).

---

## [default] — Simulator / application

| Field | Values | How it is used |
|-------|--------|----------------|
| **SimulatorRole** | Primary \| Secondary | **Simulator:** Read at startup; sets JDBC connection `applicationName` (e.g. FixSimulator-Primary). DB triggers use it to populate `simulator_instance` on TRACE_FIX_* tables. Log messages and behavior may vary by role. |
| **PersistMessages** | Y \| N | **QuickFIX/J:** Controls whether the **message store** keeps a copy of each sent/received message for **resend**. See “[PersistMessages — how it works](#persistmessages--how-it-works)” below. |

---

### PersistMessages — how it works

**Who reads it:** QuickFIX/J engine (the simulator does **not** read this in code; it is passed through in `SessionSettings` to the engine).

**What it controls:** Whether the **message store** (file or JDBC) **persists message bodies** so they can be **resent** when the other side sends a ResendRequest (35=2).

| Value | Behavior |
|-------|----------|
| **Y** | Messages are written to the store (file under `FileStorePath` or table `JdbcStoreMessagesTableName`). When the initiator sends ResendRequest(BeginSeqNo, EndSeqNo), the engine can **resend** those messages (with PossDupFlag=Y). Session state (sequence numbers) is always persisted regardless. |
| **N** | Message **bodies** are **not** stored. Sequence numbers (session state) are still updated. When a ResendRequest is received, the engine **cannot** resend the requested range and will send a **Gap Fill** (SequenceReset 35=4, 123=Y) instead to skip the gap. |

**Relation to other settings:**

- **UseJdbcStore=Y** → Store is **JDBC** (e.g. TRACE_FIX_MESSAGES). **PersistMessages=Y** → engine writes each message into that table. **PersistMessages=N** → engine does not write messages (table stays empty for new messages); resend requests are answered with Gap Fill.
- **UseJdbcStore=N** → Store is **file** under **FileStorePath**. **PersistMessages=Y** → messages go to files. **PersistMessages=N** → no message files; resend answered with Gap Fill.

**Summary:** **PersistMessages** = “store message bodies for resend.” **UseJdbcStore** / **FileStorePath** = “where to store (DB vs file).” Session state (sequence numbers) is always persisted; only **message content** is optional via PersistMessages.

---

## [default] — Logging (where data is saved)

| Field | Values | How it is used |
|-------|--------|----------------|
| **LogToFile** | Y \| N | **Simulator:** If Y, a file log factory is added. FIX messages and events are written to files under **FileStorePath** / **FileLogPath**. |
| **LogToDB** | Y \| N | **Simulator:** If Y, a JDBC log factory is used. Messages → **JdbcLogIncomingTable** / **JdbcLogOutgoingTable**; events → **JdbcLogEventTable**. Requires JDBC settings and DB schema. |
| **LogToScreen** | Y \| N | **Simulator:** If Y, screen log factory is added. Messages/events are printed to console. |
| **FileStorePath** | path (e.g. `data`) | **QuickFIX/J:** Base directory for file store (session state and messages) when **UseJdbcStore=N**. Also used as base for file logs when **LogToFile=Y** (unless overridden by FileLogPath). |
| **UseJdbcStore** | Y \| N | **Simulator:** If Y, session state and message store use JDBC (**JdbcStoreSessionsTableName**, **JdbcStoreMessagesTableName**). If N, QuickFIX/J uses file store under FileStorePath. Required for TRACE_FIX_SESSIONS and TRACE_FIX_MESSAGES. |

---

## [default] — QuickFIX/J engine (session/connection)

| Field | Values | How it is used |
|-------|--------|----------------|
| **ConnectionType** | acceptor \| initiator | **QuickFIX/J:** This process acts as **acceptor** (simulator accepts connections from the gateway). |
| **SocketAcceptAddress** | IP (e.g. `0.0.0.0`) | **QuickFIX/J (acceptor):** Bind address. `0.0.0.0` = all interfaces so any client IP can connect. Do not set to a single client IP unless you want to restrict. |
| **ResetOnLogon** | Y \| N | **QuickFIX/J:** N = do not auto-reset sequence on logon. Simulator also **honors** initiator’s ResetSeqNumFlag (141=Y) when present. |
| **ResetOnLogout** | Y \| N | **QuickFIX/J:** N = do not reset sequence numbers when logout is received. |
| **ResetOnDisconnect** | Y \| N | **QuickFIX/J:** N = do not reset on disconnect. |
| **ResetOnError** | Y \| N | **QuickFIX/J:** N = do not reset on error. |
| **SendRedundantResendRequests** | Y \| N | **QuickFIX/J:** Y = may send another ResendRequest for the same gap if the first did not result in a fill (e.g. lost or initiator did not resend). Reduces risk of stuck “MsgSeqNum too high” / “Already sent ResendRequest… Not sending another”. |
| **RefreshOnLogon** | Y \| N | **QuickFIX/J:** Y = on Logon, reload session state and message store from DB (or file). Required so after simulator restart we use **incoming_seqnum** from DB and do not send ResendRequest(1,N-1) when DB already has the correct sequence. |
| **EnableNextExpectedMsgSeqNum** | Y \| N | **QuickFIX/J:** Y = use NextExpectedMsgSeqNum (789) in Logon for sequence sync. Simulator uses it to align next sender sequence. |
| **NonStopSession** | Y \| N | **QuickFIX/J:** Y = logon allowed 24/7; no “Logon attempt not within session time” rejection. |
| **StartTime** | time US/Eastern | **QuickFIX/J:** Session start time (used when NonStopSession=N). |
| **EndTime** | time US/Eastern | **QuickFIX/J:** Session end time (used when NonStopSession=N). |
| **CheckLatency** | Y \| N | **QuickFIX/J:** N = do not check latency. |
| **UseDataDictionary** | Y \| N | **QuickFIX/J:** N = do not validate required tags; no “Required tag missing” reject. Simulator forces N so all messages get a business response. |
| **DataDictionary** | path (e.g. FIX44.xml) | **QuickFIX/J:** Path to data dictionary file (used when UseDataDictionary=Y; simulator forces N). |
| **BeginString** | FIX.4.4 | **QuickFIX/J:** FIX version. |
| **OnBehalfOfSubID** | e.g. DESK | **QuickFIX/J:** Optional; on-behalf-of sub ID. |
| **OnBehalfOfCompID** | e.g. BROKER | **QuickFIX/J:** Optional; on-behalf-of comp ID. |

---

## [default] — Heartbeat (application logic)

| Field | Values | How it is used |
|-------|--------|----------------|
| **HeartBeat_Required** | Y \| N | **WizFixApplication:** Y = send Heartbeat to initiator when required by engine. N = do not send Heartbeat (toAdmin blocks sending). |
| **HeartBtDelay** | Y \| N | **WizFixApplication:** Y = before sending each Heartbeat, wait **HeartBtDelayTime** seconds. While waiting, **app messages (e.g. AE) are queued** and processed after the delay (SPEN sent after Heartbeat). N = send Heartbeat immediately. |
| **HeartBtDelayCount** | integer (e.g. 10) | **WizFixApplication:** When delaying, delay is applied every Nth heartbeat (e.g. every 10th). Used with HeartBtDelay=Y. |
| **HeartBtDelayTime** | integer seconds (e.g. 3) | **WizFixApplication:** Number of seconds to wait before sending Heartbeat when HeartBtDelay=Y. |

---

## [default] — Logon (application logic)

| Field | Values | How it is used |
|-------|--------|----------------|
| **LogonRequired** | Y \| N | **WizFixApplication:** Y = send Logon back to initiator when they log on. N = do not send Logon (reject logon with RejectLogon); all app/admin messages are “picked” but no response sent. |
| **LogonDelay** | Y \| N | **WizFixApplication:** Y = after receiving Logon, wait **LogonDelayinSecs** before sending our Logon. During delay **all messages are ignored**. N = send Logon immediately. |
| **LogonDelayinSecs** | integer (e.g. 15) | **WizFixApplication:** Delay in seconds when LogonDelay=Y. Initiator’s logon response timeout should be greater than this. |

---

## [default] — Response / trade behavior (application logic)

| Field | Values | How it is used |
|-------|--------|----------------|
| **ResponseMsgDelay** | Y \| N | **WizFixApplication:** Y = add an artificial delay of **ResponseMsgDelayTime** seconds before processing each incoming trade (onMessage). N = process immediately. |
| **ResponseMsgDelayTime** | integer seconds | **WizFixApplication:** Delay in seconds when ResponseMsgDelay=Y. |
| **TraceNotAvailable** | Y \| N | **WizFixApplication:** Initial value of “trace not available” flag. When true, incoming trades get REJ (reject) instead of SPEN. |
| **TraceNotAvailableIntervel** | integer seconds (e.g. 120) | **WizFixApplication:** When TraceNotAvailable is enabled, a timer toggles the trace-not-available flag every this many seconds (for testing reject behavior). |

---

## [default] — Shutdown (application logic)

| Field | Values | How it is used |
|-------|--------|----------------|
| **SendLogout_at_Shutdown** | Y \| N | **Simulator:** Y = on stop(), send Logout to all logged-on sessions, then call acceptor.stop(false). N = do not send Logout; call acceptor.stop(true) so QuickFIX/J does not send Logout either. |

---

## [default] — Session state from DB (application logic)

| Field | Values | How it is used |
|-------|--------|----------------|
| **SessionDateZone** | e.g. America/New_York | **Simulator / SessionSequenceFromDB:** Time zone for “current date” when loading session sequence from **JdbcStoreSessionsTableName**. Only rows with **creation_time** on “today” in this zone are used; otherwise treated as “fresh day” (seq 1). |

---

## [default] — JDBC (database)

| Field | Values | How it is used |
|-------|--------|----------------|
| **JdbcURL** | JDBC URL | **Simulator:** SQL Server connection URL (e.g. databaseName=trace_fix or trace_simulator). Used for JdbcStore and JdbcLog when LogToDB=Y or UseJdbcStore=Y. |
| **JdbcUser** | username | **Simulator:** DB user. |
| **JdbcPassword** | password | **Simulator:** DB password. |
| **JdbcDriver** | class name | **Simulator:** JDBC driver (e.g. com.microsoft.sqlserver.jdbc.SQLServerDriver). |
| **JdbcStoreSessionsTableName** | e.g. TRACE_FIX_SESSIONS | **Simulator / QuickFIX/J:** Table for session state (incoming_seqnum, outgoing_seqnum). Simulator also uses it in SessionSequenceFromDB for Logon alignment and shutdown seq persist. |
| **JdbcStoreMessagesTableName** | e.g. TRACE_FIX_MESSAGES | **QuickFIX/J:** Table for stored messages (resend). Simulator validates schema. |
| **JdbcLogHeartBeats** | Y \| N | **QuickFIX/J:** Y = write Heartbeats (35=0) to JDBC log tables. N = do not log heartbeats to DB. |
| **JdbcLogIncomingTable** | e.g. TRACE_FIX_MESSAGES_LOG | **QuickFIX/J:** Table for incoming message log. |
| **JdbcLogOutgoingTable** | e.g. TRACE_FIX_MESSAGES_LOG | **QuickFIX/J:** Table for outgoing message log (can be same as incoming). |
| **JdbcLogEventTable** | e.g. TRACE_FIX_EVENT_LOG | **QuickFIX/J:** Table for session/connection events. |

---

## [default] — File logging (QuickFIX/J)

| Field | Values | How it is used |
|-------|--------|----------------|
| **FileLogPath** | path (e.g. logs) | **QuickFIX/J:** Directory for file log output (messages/events when LogToFile=Y). |
| **FileLogHeartbeats** | Y \| N | **QuickFIX/J:** Y = log Heartbeats to file. |
| **FileIncludeMilliseconds** | Y \| N | **QuickFIX/J:** Y = include milliseconds in file log timestamps. |
| **FileIncludeTimeStampForMessages** | Y \| N | **QuickFIX/J:** Y = include timestamp in message log. |

---

## [default] — SLF4J / screen logging (QuickFIX/J)

| Field | Values | How it is used |
|-------|--------|----------------|
| **SLF4JLogEventCategory** | e.g. quickfixj.event | **QuickFIX/J:** Logger name for events. |
| **SLF4JLogIncomingMessageCategory** | e.g. quickfixj.msg.incoming | **QuickFIX/J:** Logger for incoming messages. |
| **SLF4JLogOutgoingMessageCategory** | e.g. quickfixj.msg.outgoing | **QuickFIX/J:** Logger for outgoing messages. |
| **SLF4JLogPrependSessionID** | Y \| N | **QuickFIX/J:** Y = prepend session ID to log messages. |
| **SLF4JLogHeartbeats** | Y \| N | **QuickFIX/J:** Y = log Heartbeats to SLF4J. |
| **ScreenLogEvents** | Y \| N | **QuickFIX/J:** Y = show events on screen. |
| **ScreenLogShowIncoming** | Y \| N | **QuickFIX/J:** Y = show incoming messages on screen. |
| **ScreenLogShowOutgoing** | Y \| N | **QuickFIX/J:** Y = show outgoing messages on screen. |
| **ScreenLogShowHeartBeats** | Y \| N | **QuickFIX/J:** Y = show Heartbeats on screen. |

---

## [default] — Session identity (acceptor)

| Field | Values | How it is used |
|-------|--------|----------------|
| **SenderCompID** | e.g. FNRA | **QuickFIX/J:** Our CompID (acceptor = simulator). Messages we send have 49=FNRA. |
| **TargetCompID** | e.g. JPMS | **QuickFIX/J:** Initiator’s CompID. Messages we send have 56=JPMS. |

---

## [session] — Per-session overrides

Each **[session]** block defines one session (e.g. SP, CA, TS). It inherits [default] and overrides:

| Field | Values | How it is used |
|-------|--------|----------------|
| **TargetSubID** | e.g. 44B1, 44B2, 44B3 | **QuickFIX/J:** Initiator’s SubID. With SenderSubID defines session (e.g. FNRA/SP ↔ JPMS/44B1). |
| **SenderSubID** | e.g. SP, CA, TS | **QuickFIX/J:** Our SubID (product). |
| **SocketAcceptPort** | port (e.g. 64034) | **Simulator / QuickFIX/J:** Acceptor listen port for this session. Each session uses a different port (64034 SP, 64093 CA, 64094 TS). |
| **NonStopSession** | Y \| N | **QuickFIX/J:** Override for this session (allow logon 24/7). |

Optional (not in current file but supported):

- **SocketAcceptAddress** — Override bind address for this session.
- **AcceptorTemplate** — When true, session is a template for dynamic sessions.

---

## Summary: where each setting is used

| Used by | Settings |
|---------|----------|
| **Simulator (startup, JDBC, shutdown)** | SimulatorRole, LogToFile, LogToDB, LogToScreen, UseJdbcStore, SendLogout_at_Shutdown, SessionDateZone, JdbcURL, JdbcUser, JdbcPassword, JdbcDriver, JdbcStoreSessionsTableName, JdbcStoreMessagesTableName, JdbcLogIncomingTable, JdbcLogOutgoingTable, JdbcLogEventTable |
| **WizFixApplication (behavior)** | LogonRequired, LogonDelay, LogonDelayinSecs, HeartBeat_Required, HeartBtDelay, HeartBtDelayCount, HeartBtDelayTime, ResponseMsgDelay, ResponseMsgDelayTime, TraceNotAvailable, TraceNotAvailableIntervel |
| **QuickFIX/J engine** | ConnectionType, SocketAcceptAddress, ResetOnLogon/Logout/Disconnect/Error, SendRedundantResendRequests, RefreshOnLogon, EnableNextExpectedMsgSeqNum, NonStopSession, StartTime, EndTime, CheckLatency, UseDataDictionary, DataDictionary, BeginString, **PersistMessages**, FileStorePath, FileLogPath, FileLogHeartbeats, FileIncludeMilliseconds, FileIncludeTimeStampForMessages, SLF4J* and ScreenLog*, SenderCompID, TargetCompID, JdbcLogHeartBeats |
| **Session block** | TargetSubID, SenderSubID, SocketAcceptPort, NonStopSession (and optional SocketAcceptAddress, AcceptorTemplate) |

---

## See also

- **docs/DATABASE_TABLES_AND_INSERTS.md** — What gets written to DB and when.
- **docs/RESENDREQUEST_AND_SEQUENCE_GAPS.md** — RefreshOnLogon, SendRedundantResendRequests, sequence behavior.
- **docs/QUERYING_TRACE_FIX_MESSAGES_LOG.md** — Querying by sendercompid, msgseqnum, MessageType.
