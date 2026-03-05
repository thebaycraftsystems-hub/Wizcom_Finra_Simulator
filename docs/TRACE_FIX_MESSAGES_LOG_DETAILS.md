# TRACE_FIX_MESSAGES_LOG – Table details

**Database:** `trace_fix` (SQL Server)  
**Config:** `JdbcLogIncomingTable` and `JdbcLogOutgoingTable` (both point to this table)  
**Used when:** `LogToDB=Y`  
**Who inserts:** QuickFIX/J `JdbcLog` (one row per incoming/outgoing FIX message)

---

## Columns

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| **id** | INT IDENTITY(1,1) | NOT NULL | Primary key, auto-generated. |
| **time** | DATETIME | NOT NULL | When the message was logged. |
| **beginstring** | CHAR(8) | NOT NULL | FIX version (e.g. FIX.4.4). |
| **sendercompid** | VARCHAR(64) | NOT NULL | Sender CompID (49). |
| **sendersubid** | VARCHAR(64) | NOT NULL | Sender SubID (50). |
| **senderlocid** | VARCHAR(64) | NOT NULL | Sender LocID (142). |
| **targetcompid** | VARCHAR(64) | NOT NULL | Target CompID (56). |
| **targetsubid** | VARCHAR(64) | NOT NULL | Target SubID (57). |
| **targetlocid** | VARCHAR(64) | NOT NULL | Target LocID (143). |
| **session_qualifier** | VARCHAR(64) | NULL | Session qualifier. |
| **text** | NVARCHAR(MAX) | NOT NULL | Raw FIX message (SOH-delimited). |
| **simulator_instance** | VARCHAR(128) | NULL | Set by trigger: `FixSimulator-Primary` or `FixSimulator-Secondary`. |
| **MessageTypeTag** | VARCHAR(3) | NOT NULL, default '' | FIX tag 35 (e.g. AE, 0, A). Filled by trigger from `text`. |
| **MessageType** | VARCHAR(100) | NULL | Human name for 35 (e.g. Trade Report, Heartbeat). Filled by trigger. |
| **TraceTradeReportID** | VARCHAR(20) | NULL | FIX tag 571 (e.g. SP20260304000113:18). Filled by trigger. |
| **msgseqnum** | INT | NOT NULL, default 0 | FIX tag 34 (MsgSeqNum). Filled by trigger. |

---

## Indexes

| Index | Column(s) |
|-------|-----------|
| **PK** | `id` |
| **idx_trace_fix_messages_log_time** | `time` |

---

## Triggers (AFTER INSERT)

1. **tr_TRACE_FIX_MESSAGES_LOG_set_simulator**  
   Sets `simulator_instance` from the connection’s `program_name` (application name from JDBC).

2. **tr_TRACE_FIX_MESSAGES_LOG_parse_fix**  
   Parses raw `text` and sets:
   - **MessageTypeTag** / **MessageType** from tag 35
   - **TraceTradeReportID** from tag 571
   - **msgseqnum** from tag 34 (0 if missing/invalid)
   - **sendercompid** from tag 49, **targetcompid** from tag 56, **sendersubid** from tag 50, **targetsubid** from tag 57 (same mapping whether 49=FNRA or not; when a tag is missing, the value from the QuickFIX/J insert is kept)

QuickFIX/J only inserts: `time`, `beginstring`, sender/target IDs, `session_qualifier`, `text`. The triggers fill `simulator_instance`, `MessageTypeTag`, `MessageType`, `TraceTradeReportID`, `msgseqnum`, and ensure sender/target CompID/SubID reflect the message body (49, 56, 50, 57).

---

## Recreate table

To drop and recreate the table (and its triggers), run:

`src/main/resources/sql/quickfixj_sqlserver_trace_fix_messages_log_recreate.sql`

**Warning:** This deletes all data in `TRACE_FIX_MESSAGES_LOG`.
