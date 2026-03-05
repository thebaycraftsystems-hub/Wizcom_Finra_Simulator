# Database Tables and Inserts

Database: **trace_fix** (SQL Server). Schema scripts: `src/main/resources/sql/`.

---

## Tables in use (when LogToDB=Y and/or UseJdbcStore=Y)

All inserts into these four tables are performed by **QuickFIX/J** (JdbcStore / JdbcLog), not by application code. The simulator only provides the DataSource and config (table names, JDBC URL, etc.).

**Which simulator (Primary vs Secondary)?** Each table has a **simulator_instance** column (e.g. `FixSimulator-Primary` or `FixSimulator-Secondary`). The simulator sets the JDBC connection’s application name from config `SimulatorRole`; SQL Server triggers populate `simulator_instance` from that on INSERT. Run `sql/quickfixj_sqlserver_add_simulator_instance.sql` on an existing DB to add the column and triggers.

| Table | Config key | Purpose | When rows are inserted | Where (code) |
|-------|------------|---------|-------------------------|--------------|
| **TRACE_FIX_SESSIONS** | `JdbcStoreSessionsTableName` | Session state and sequence numbers | On session creation; updated on every sent/received message (seq nums) | QuickFIX/J `JdbcStore` (session store) |
| **TRACE_FIX_MESSAGES** | `JdbcStoreMessagesTableName` | Persisted messages for resend | When a message is sent or received (per seq num) | QuickFIX/J `JdbcStore` (message store) |
| **TRACE_FIX_MESSAGES_LOG** | `JdbcLogIncomingTable` / `JdbcLogOutgoingTable` | Log of incoming and outgoing FIX messages | Every incoming and outgoing application/admin message (when LogToDB=Y) | QuickFIX/J `JdbcLog` |
| **TRACE_FIX_EVENT_LOG** | `JdbcLogEventTable` | Session events (connect, disconnect, etc.) | On session events (logon, logout, connection, errors, etc.) | QuickFIX/J `JdbcLog` |

---

## 1. TRACE_FIX_SESSIONS

**Used when:** `UseJdbcStore=Y` (persist session state in DB).

**Insert/update:** Done by QuickFIX/J `JdbcStore` (session store implementation).

- **Insert:** When a new session is created (e.g. first time a gateway connects for that SessionID).
- **Update:** Incoming/outgoing sequence numbers are updated as messages are sent/received.

**Columns (what is stored):**

| Column | Description |
|--------|-------------|
| beginstring | FIX version (e.g. FIX.4.4) |
| sendercompid, sendersubid, senderlocid | Sender identity |
| targetcompid, targetsubid, targetlocid | Target identity |
| session_qualifier | Session qualifier if any |
| creation_time | When the session row was created |
| incoming_seqnum | Next expected incoming sequence number |
| outgoing_seqnum | Next outgoing sequence number |
| simulator_instance | Set by trigger: `FixSimulator-Primary` or `FixSimulator-Secondary` (which simulator inserted the row) |

**Where in our code:** We only pass `SessionSettings` and (optionally) a DataSource to `JdbcStoreFactory`. The actual INSERT/UPDATE is inside QuickFIX/J (`quickfix.JdbcStore` or equivalent).

---

## 2. TRACE_FIX_MESSAGES

**Used when:** `UseJdbcStore=Y`.

**Insert:** For each message sent or received, QuickFIX/J stores it keyed by session + msgseqnum (for potential resend).

**Columns:**

| Column | Description |
|--------|-------------|
| beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier | Session key (same as sessions table) |
| msgseqnum | Message sequence number (34) |
| message | Full FIX message string |
| simulator_instance | Set by trigger: `FixSimulator-Primary` or `FixSimulator-Secondary` |

**When:** On every application and admin message send/receive (when using JDBC store).

**Where:** QuickFIX/J message store implementation.

**Why Secondary had no rows (and the fix):** QuickFIX/J does **INSERT only**. The table has a composite primary key (session + msgseqnum). When the initiator fails over to Secondary, Secondary uses the same session and sequence numbers; when it tries to INSERT a row that Primary already wrote (same session + msgseqnum), the INSERT fails with a **duplicate key** and no Secondary row appears. The fix is an **INSTEAD OF INSERT** trigger on `TRACE_FIX_MESSAGES` that runs a **MERGE**: if the row exists, UPDATE message and `simulator_instance`; otherwise INSERT. Then both Primary and Secondary succeed. **Existing databases:** run `sql/quickfixj_sqlserver_trace_fix_messages_upsert_trigger.sql` once to replace the old trigger with the upsert trigger. New installs (full schema) already create this trigger.

---

## 3. TRACE_FIX_MESSAGES_LOG

**Used when:** `LogToDB=Y`. Same table name is used for both incoming and outgoing (config: `JdbcLogIncomingTable` and `JdbcLogOutgoingTable` both point to `TRACE_FIX_MESSAGES_LOG`).

**Insert:** One row per incoming or outgoing FIX message (optionally excluding heartbeats if `JdbcLogHeartBeats=N`).

**Columns:**

| Column | Description |
|--------|-------------|
| id | Identity (auto) |
| time | When the message was logged |
| beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier | Session identification |
| text | Raw FIX message string |
| simulator_instance | Set by trigger: `FixSimulator-Primary` or `FixSimulator-Secondary` |
| MessageTypeTag | FIX tag 35 value (e.g. AE, 0, A). **Populated by trigger** `tr_TRACE_FIX_MESSAGES_LOG_parse_fix` from `text`. VARCHAR(3) NOT NULL, default ''. |
| MessageType | Human-readable name for tag 35 (e.g. Trade Report, Heartbeat, Logon). **Populated by trigger**. VARCHAR(100) NULL. |
| TraceTradeReportID | FIX tag 571 value (e.g. 571=SP20260304000113:18 → SP20260304000113:18). **Populated by trigger**. VARCHAR(20) NULL. |
| msgseqnum | FIX tag 34 (MsgSeqNum). **Populated by trigger**. INT NOT NULL, default 0. |

**MessageTypeTag / MessageType / TraceTradeReportID / msgseqnum:** QuickFIX/J does not write these; the **AFTER INSERT** trigger `tr_TRACE_FIX_MESSAGES_LOG_parse_fix` parses the raw `text` (FIX message, SOH-delimited), extracts tag 35 → MessageTypeTag and MessageType, tag 571 → TraceTradeReportID, tag 34 → msgseqnum, and updates the row. The same trigger also sets **sendercompid** (49), **targetcompid** (56), **sendersubid** (50), **targetsubid** (57) from the message body so the stored row reflects the FIX tags (same mapping for 49=FNRA and 49≠FNRA). **Existing DBs:** run `sql/quickfixj_sqlserver_trace_fix_messages_log_enhance.sql` to add the columns and trigger. New installs (full schema) already include them.

**When:** Every incoming and outgoing message that is logged (e.g. all app messages; heartbeats only if JdbcLogHeartBeats=Y).

**Where:** QuickFIX/J `JdbcLog` (used as the log backend when we add `createJdbcLogFactory(...)` to the composite log factory in `Simulator.java`).

---

## 4. TRACE_FIX_EVENT_LOG

**Used when:** `LogToDB=Y`.

**Insert:** One row per session/connection event (e.g. connection established, logon, logout, error).

**Columns:**

| Column | Description |
|--------|-------------|
| id | Identity (auto) |
| time | Event time |
| beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier | Session identification |
| text | Event description / message |
| simulator_instance | Set by trigger: `FixSimulator-Primary` or `FixSimulator-Secondary` |

**When:** On session lifecycle and connection events.

**Where:** QuickFIX/J `JdbcLog` (event log).

---

## 5. TRACE_LIFECYCLE_STATE

**Defined in:** `sql/trace_lifecycle_state_table.sql`

**Purpose:** Lifecycle state for FINRA TS compliance (per TradeReportID/session). Shared by Primary and Secondary so state is preserved when the initiator switches.

**Insert/update:** When the simulator uses a DB (same DataSource as for JdbcStore/JdbcLog), it uses **JdbcLifecycleStore** and writes to `TRACE_LIFECYCLE_STATE` on each lifecycle transition (e.g. NEW_SUBMITTED, ACCEPTED, CORRECTED). When no DB is configured, it uses **InMemoryLifecycleStore** and this table is not updated.

**Where:** `CompliancePipeline` → `LifecycleEngine` → `LifecycleStateStore` (JdbcLifecycleStore or InMemoryLifecycleStore). Simulator creates the store and passes it into `WizFixApplication`.

---

## Primary / Secondary switch: how the five tables are updated

When you switch from Primary to Secondary (or vice versa), the **active** simulator instance (the one the initiator is connected to) is the one that writes to the database. All five tables use the **same database** (same `JdbcURL` in both configs).

| Table | Who writes | When updated |
|-------|------------|--------------|
| **TRACE_FIX_SESSIONS** | QuickFIX/J JdbcStore | Session creation; seq nums on every message. **Active** instance (Primary or Secondary) updates. |
| **TRACE_FIX_MESSAGES** | QuickFIX/J JdbcStore | Every message sent/received. **Active** instance inserts. |
| **TRACE_FIX_MESSAGES_LOG** | QuickFIX/J JdbcLog | Every incoming/outgoing message (when LogToDB=Y). **Active** instance inserts. |
| **TRACE_FIX_EVENT_LOG** | QuickFIX/J JdbcLog | Logon, logout, connection events. **Active** instance inserts. |
| **TRACE_LIFECYCLE_STATE** | Simulator (JdbcLifecycleStore) | Lifecycle transitions (trade state). **Active** instance inserts/updates. |

**Requirements so all five tables are updated when you switch:**

1. **Same DB for both:** Primary and Secondary configs must use the same `JdbcURL`, `JdbcUser`, `JdbcPassword`, and table names.
2. **UseJdbcStore=Y** and **LogToDB=Y** in both configs (so TRACE_FIX_SESSIONS, TRACE_FIX_MESSAGES, TRACE_FIX_MESSAGES_LOG, TRACE_FIX_EVENT_LOG are written by the engine).
3. **Schema validation must pass** on startup for the instance you run. If the instance falls back to file store (log: "JDBC store schema invalid … Falling back to file store"), that instance will **not** update TRACE_FIX_SESSIONS or TRACE_FIX_MESSAGES. Ensure the DB is reachable and `sql/quickfixj_sqlserver_schema.sql` (and `sql/trace_lifecycle_state_table.sql`) have been run.
4. **TRACE_LIFECYCLE_STATE** is written whenever the simulator has a DataSource (i.e. when LogToDB=Y or UseJdbcStore=Y); no extra config. Startup log: "Using JDBC lifecycle store — TRACE_LIFECYCLE_STATE will be updated (shared by Primary/Secondary)."

**simulator_instance:** Each table has a `simulator_instance` column (e.g. `FixSimulator-Primary`, `FixSimulator-Secondary`) so you can see which instance wrote each row.

---

## Config that enables DB usage

In `quickfixj-server.cfg` (and secondary):

- **LogToDB=Y** → Enables JDBC logging → inserts into **TRACE_FIX_MESSAGES_LOG** and **TRACE_FIX_EVENT_LOG**.
- **UseJdbcStore=Y** → Enables JDBC store → inserts/updates to **TRACE_FIX_SESSIONS** and **TRACE_FIX_MESSAGES**.

JDBC connection is created in **Simulator** (`createJdbcDataSource`) and passed to:
- **JdbcLogFactory** (for log tables) when `LogToDB=Y`
- **JdbcStoreFactory** (for session/message store) when `UseJdbcStore=Y`

Table names are set in config (e.g. `JdbcStoreMessagesTableName=TRACE_FIX_MESSAGES`). Schema must exist in DB `trace_fix` (create via `quickfixj_sqlserver_schema.sql`).
