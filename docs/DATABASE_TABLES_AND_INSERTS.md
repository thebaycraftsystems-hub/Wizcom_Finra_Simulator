# Database Tables and Inserts

Database: **trace_fix** (SQL Server). Schema scripts: `src/main/resources/sql/`.

---

## Tables in use (when LogToDB=Y and/or UseJdbcStore=Y)

All inserts into these four tables are performed by **QuickFIX/J** (JdbcStore / JdbcLog), not by application code. The simulator only provides the DataSource and config (table names, JDBC URL, etc.).

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

**When:** On every application and admin message send/receive (when using JDBC store).

**Where:** QuickFIX/J message store implementation.

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

**When:** On session lifecycle and connection events.

**Where:** QuickFIX/J `JdbcLog` (event log).

---

## 5. TRACE_LIFECYCLE_STATE (defined but not used for inserts by current code)

**Defined in:** `sql/trace_lifecycle_state_table.sql`

**Purpose (per schema comment):** Lifecycle state for FINRA TS compliance (per TradeReportID/session).

**Current app behavior:** The simulator uses `InMemoryLifecycleStore` only (`CompliancePipeline` → `LifecycleEngine` → `InMemoryLifecycleStore`). There is **no JDBC implementation** of `LifecycleStateStore`, so **no inserts** into `TRACE_LIFECYCLE_STATE` happen in the current codebase. The table exists for potential future use or external tooling.

---

## Config that enables DB usage

In `quickfixj-server.cfg` (and secondary):

- **LogToDB=Y** → Enables JDBC logging → inserts into **TRACE_FIX_MESSAGES_LOG** and **TRACE_FIX_EVENT_LOG**.
- **UseJdbcStore=Y** → Enables JDBC store → inserts/updates to **TRACE_FIX_SESSIONS** and **TRACE_FIX_MESSAGES**.

JDBC connection is created in **Simulator** (`createJdbcDataSource`) and passed to:
- **JdbcLogFactory** (for log tables) when `LogToDB=Y`
- **JdbcStoreFactory** (for session/message store) when `UseJdbcStore=Y`

Table names are set in config (e.g. `JdbcStoreMessagesTableName=TRACE_FIX_MESSAGES`). Schema must exist in DB `trace_fix` (create via `quickfixj_sqlserver_schema.sql`).
