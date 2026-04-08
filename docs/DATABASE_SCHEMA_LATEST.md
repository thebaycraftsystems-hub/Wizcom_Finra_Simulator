# Latest Database Schema (SQL Server)

**Database:** `trace_fix`  
**Scripts location:** `src/main/resources/sql/`

---

## Tables overview

| Table | Purpose | Written by |
|-------|---------|------------|
| **TRACE_FIX_SESSIONS** | Session state and sequence numbers | QuickFIX/J JdbcStore |
| **TRACE_FIX_MESSAGES** | Stored messages for resend | QuickFIX/J JdbcStore |
| **TRACE_FIX_MESSAGES_LOG** | Log of every FIX message (in/out) | QuickFIX/J JdbcLog |
| **TRACE_FIX_EVENT_LOG** | Session events (logon, logout, etc.) | QuickFIX/J JdbcLog |
| **TRACE_LIFECYCLE_STATE** | Trade lifecycle (NEW_SUBMITTED, ACCEPTED, etc.) | Simulator (JdbcLifecycleStore) |

---

## 1. TRACE_FIX_SESSIONS

Session state and next sequence numbers. Key = session identity (acceptor view).

| Column | Type | Description |
|--------|------|-------------|
| beginstring | CHAR(8) NOT NULL | FIX version (e.g. FIX.4.4) |
| sendercompid | VARCHAR(64) NOT NULL | Sender CompID (acceptor = FNRA) |
| sendersubid | VARCHAR(64) NOT NULL | Sender SubID (e.g. SP) |
| senderlocid | VARCHAR(64) NOT NULL | Sender LocationID |
| targetcompid | VARCHAR(64) NOT NULL | Target CompID (initiator = JPMS) |
| targetsubid | VARCHAR(64) NOT NULL | Target SubID (e.g. 44B1) |
| targetlocid | VARCHAR(64) NOT NULL | Target LocationID |
| session_qualifier | VARCHAR(64) NOT NULL | Session qualifier |
| creation_time | DATETIME NOT NULL | When session row was created |
| incoming_seqnum | INT NOT NULL | Next expected sequence from initiator |
| outgoing_seqnum | INT NOT NULL | Next sequence we will send |
| simulator_instance | VARCHAR(128) NULL | FixSimulator-Primary / FixSimulator-Secondary (from trigger) |

**Primary key:** (beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier)

**Trigger:** `tr_TRACE_FIX_SESSIONS_set_simulator` (AFTER INSERT) — sets `simulator_instance` from connection `program_name`.

---

## 2. TRACE_FIX_MESSAGES

Stored FIX messages for resend (ResendRequest). One row per (session + msgseqnum).

| Column | Type | Description |
|--------|------|-------------|
| beginstring | CHAR(8) NOT NULL | |
| sendercompid | VARCHAR(64) NOT NULL | |
| sendersubid | VARCHAR(64) NOT NULL | |
| senderlocid | VARCHAR(64) NOT NULL | |
| targetcompid | VARCHAR(64) NOT NULL | |
| targetsubid | VARCHAR(64) NOT NULL | |
| targetlocid | VARCHAR(64) NOT NULL | |
| session_qualifier | VARCHAR(64) NOT NULL | |
| msgseqnum | INT NOT NULL | Message sequence number (34) |
| message | NVARCHAR(MAX) NOT NULL | Full FIX message |
| simulator_instance | VARCHAR(128) NULL | FixSimulator-Primary / FixSimulator-Secondary |

**Primary key:** (beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier, msgseqnum)

**Trigger:** `tr_TRACE_FIX_MESSAGES_instead_of_insert` (INSTEAD OF INSERT) — MERGE so both Primary and Secondary can write same session+msgseqnum (upsert).

---

## 3. TRACE_FIX_MESSAGES_LOG

Log of every incoming and outgoing FIX message (when LogToDB=Y).

| Column | Type | Description |
|--------|------|-------------|
| id | INT IDENTITY(1,1) NOT NULL | Auto-increment |
| time | DATETIME NOT NULL | When message was logged |
| beginstring | CHAR(8) NOT NULL | |
| sendercompid | VARCHAR(64) NOT NULL | From FIX tag 49 (set/overridden by trigger) |
| sendersubid | VARCHAR(64) NOT NULL | From tag 50 |
| senderlocid | VARCHAR(64) NOT NULL | |
| targetcompid | VARCHAR(64) NOT NULL | From tag 56 |
| targetsubid | VARCHAR(64) NOT NULL | From tag 57 |
| targetlocid | VARCHAR(64) NOT NULL | |
| session_qualifier | VARCHAR(64) NULL | |
| text | NVARCHAR(MAX) NOT NULL | Raw FIX message |
| simulator_instance | VARCHAR(128) NULL | Set by trigger |
| MessageTypeTag | VARCHAR(3) NOT NULL DEFAULT '' | Tag 35 (e.g. AE, 0, A) — from trigger |
| MessageType | VARCHAR(100) NULL | Human name (Trade Report, Heartbeat, Logon, etc.) — from trigger |
| TraceTradeReportID | VARCHAR(20) NULL | Tag 571 — from trigger |
| msgseqnum | INT NOT NULL DEFAULT 0 | Tag 34 — from trigger |

**Primary key:** id  
**Index:** idx_trace_fix_messages_log_time (time)

**Triggers:**
- `tr_TRACE_FIX_MESSAGES_LOG_set_simulator` (AFTER INSERT) — sets simulator_instance.
- `tr_TRACE_FIX_MESSAGES_LOG_parse_fix` (AFTER INSERT) — parses `text` and sets MessageTypeTag, MessageType, TraceTradeReportID, msgseqnum, sendercompid, targetcompid, sendersubid, targetsubid.

---

## 4. TRACE_FIX_EVENT_LOG

Session/connection events (logon, logout, errors, etc.).

| Column | Type | Description |
|--------|------|-------------|
| id | INT IDENTITY(1,1) NOT NULL | |
| time | DATETIME NOT NULL | |
| beginstring | CHAR(8) NOT NULL | |
| sendercompid | VARCHAR(64) NOT NULL | |
| sendersubid | VARCHAR(64) NOT NULL | |
| senderlocid | VARCHAR(64) NOT NULL | |
| targetcompid | VARCHAR(64) NOT NULL | |
| targetsubid | VARCHAR(64) NOT NULL | |
| targetlocid | VARCHAR(64) NOT NULL | |
| session_qualifier | VARCHAR(64) NULL | |
| text | NVARCHAR(MAX) NOT NULL | Event description |
| simulator_instance | VARCHAR(128) NULL | Set by trigger |

**Primary key:** id  
**Index:** idx_trace_fix_event_log_time (time)

**Trigger:** `tr_TRACE_FIX_EVENT_LOG_set_simulator` (AFTER INSERT) — sets simulator_instance.

---

## 5. TRACE_LIFECYCLE_STATE

Trade lifecycle state per (session_key, trade_report_id). Used by compliance engine (JdbcLifecycleStore).

| Column | Type | Description |
|--------|------|-------------|
| session_key | VARCHAR(256) NOT NULL | Session identifier |
| trade_report_id | VARCHAR(64) NOT NULL | Trade report ID (e.g. from tag 571) |
| state | VARCHAR(32) NOT NULL | NEW_SUBMITTED, ACCEPTED, CORRECTED, CANCELLED, etc. |
| updated_at | DATETIME NOT NULL DEFAULT GETUTCDATE() | Last update |

**Primary key:** (session_key, trade_report_id)

---

## Scripts and run order

### New database (clean install)

1. **quickfixj_sqlserver_schema.sql** — Creates TRACE_FIX_SESSIONS, TRACE_FIX_MESSAGES, TRACE_FIX_MESSAGES_LOG, TRACE_FIX_EVENT_LOG and all triggers (including simulator_instance and parse_fix).
2. **trace_lifecycle_state_table.sql** — Creates TRACE_LIFECYCLE_STATE.

Use database `trace_fix` (create it if needed: `CREATE DATABASE trace_fix;`).

### Existing database (additions only)

- **quickfixj_sqlserver_add_locid_columns.sql** — Add senderlocid/targetlocid if missing.
- **quickfixj_sqlserver_add_simulator_instance.sql** — Add simulator_instance column and triggers to all four QuickFIX tables.
- **quickfixj_sqlserver_trace_fix_messages_log_enhance.sql** — Add MessageTypeTag, MessageType, TraceTradeReportID, msgseqnum and parse trigger to TRACE_FIX_MESSAGES_LOG (and override sender/target from tags 49/56/50/57).
- **quickfixj_sqlserver_trace_fix_messages_upsert_trigger.sql** — Replace TRACE_FIX_MESSAGES trigger with INSTEAD OF INSERT MERGE (so Secondary can write same session+msgseqnum).

---

## Config keys (quickfixj-server.cfg)

| Key | Value | Table |
|-----|--------|-------|
| JdbcStoreSessionsTableName | TRACE_FIX_SESSIONS | Session state |
| JdbcStoreMessagesTableName | TRACE_FIX_MESSAGES | Message store |
| JdbcLogIncomingTable | TRACE_FIX_MESSAGES_LOG | Message log |
| JdbcLogOutgoingTable | TRACE_FIX_MESSAGES_LOG | Message log |
| JdbcLogEventTable | TRACE_FIX_EVENT_LOG | Event log |

TRACE_LIFECYCLE_STATE is used by the simulator when a JDBC DataSource is configured (no separate config key).
