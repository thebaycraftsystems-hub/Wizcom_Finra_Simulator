-- =============================================================================
-- RECREATE ALL TABLES (trace_fix database)
-- =============================================================================
-- Use this script to drop and recreate all FIX-related tables and views,
-- then ensure TRACE_LIFECYCLE_STATE exists.
--
-- Prerequisites:
--   - Database [trace_fix] must exist.
--   - You have permission to DROP/CREATE objects.
--
-- How to run:
--   SSMS: Select database [trace_fix], open this file, execute (F5).
--   sqlcmd: sqlcmd -S YOUR_SERVER -d trace_fix -U USER -P PASSWORD -i recreate_all_tables.sql
--
-- WARNING: All data in TRACE_FIX_SESSIONS, TRACE_FIX_MESSAGES, TRACE_FIX_MESSAGES_LOG,
--          and TRACE_FIX_EVENT_LOG is permanently deleted. TRACE_LIFECYCLE_STATE is
--          only created if missing (not dropped).
-- =============================================================================

USE trace_fix;
GO

-- =============================================================================
-- PART 1: DROP VIEWS (FIX-related views)
-- =============================================================================
IF EXISTS (SELECT 1 FROM sys.views WHERE name = 'vw_FIX_FillsToday')
  DROP VIEW dbo.vw_FIX_FillsToday;

IF EXISTS (SELECT 1 FROM sys.views WHERE name = 'vw_FIX_MessageTraffic24h')
  DROP VIEW dbo.vw_FIX_MessageTraffic24h;

IF EXISTS (SELECT 1 FROM sys.views WHERE name = 'vw_FIX_OpenOrders')
  DROP VIEW dbo.vw_FIX_OpenOrders;

IF EXISTS (SELECT 1 FROM sys.views WHERE name = 'vw_FIX_SessionHealth')
  DROP VIEW dbo.vw_FIX_SessionHealth;
GO

-- =============================================================================
-- PART 2: DROP TABLES (QuickFIX/J tables)
-- =============================================================================
IF EXISTS (SELECT 1 FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES_LOG')
  DROP TABLE dbo.TRACE_FIX_MESSAGES_LOG;

IF EXISTS (SELECT 1 FROM sys.tables WHERE name = 'TRACE_FIX_EVENT_LOG')
  DROP TABLE dbo.TRACE_FIX_EVENT_LOG;

IF EXISTS (SELECT 1 FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES')
  DROP TABLE dbo.TRACE_FIX_MESSAGES;

IF EXISTS (SELECT 1 FROM sys.tables WHERE name = 'TRACE_FIX_SESSIONS')
  DROP TABLE dbo.TRACE_FIX_SESSIONS;
GO

-- =============================================================================
-- PART 3: CREATE TABLES (QuickFIX/J 2.x schema)
-- =============================================================================

CREATE TABLE dbo.TRACE_FIX_SESSIONS (
  beginstring         CHAR(8) NOT NULL,
  sendercompid        VARCHAR(64) NOT NULL,
  sendersubid         VARCHAR(64) NOT NULL,
  senderlocid         VARCHAR(64) NOT NULL,
  targetcompid        VARCHAR(64) NOT NULL,
  targetsubid         VARCHAR(64) NOT NULL,
  targetlocid         VARCHAR(64) NOT NULL,
  session_qualifier   VARCHAR(64) NOT NULL,
  creation_time       DATETIME NOT NULL,
  incoming_seqnum     INT NOT NULL,
  outgoing_seqnum     INT NOT NULL,
  simulator_instance  VARCHAR(128) NULL,
  PRIMARY KEY (beginstring, sendercompid, sendersubid, senderlocid,
               targetcompid, targetsubid, targetlocid, session_qualifier)
);

CREATE TABLE dbo.TRACE_FIX_MESSAGES (
  beginstring         CHAR(8) NOT NULL,
  sendercompid        VARCHAR(64) NOT NULL,
  sendersubid         VARCHAR(64) NOT NULL,
  senderlocid         VARCHAR(64) NOT NULL,
  targetcompid        VARCHAR(64) NOT NULL,
  targetsubid         VARCHAR(64) NOT NULL,
  targetlocid         VARCHAR(64) NOT NULL,
  session_qualifier   VARCHAR(64) NOT NULL,
  msgseqnum           INT NOT NULL,
  message             NVARCHAR(MAX) NOT NULL,
  simulator_instance  VARCHAR(128) NULL,
  PRIMARY KEY (beginstring, sendercompid, sendersubid, senderlocid,
               targetcompid, targetsubid, targetlocid, session_qualifier,
               msgseqnum)
);

CREATE TABLE dbo.TRACE_FIX_MESSAGES_LOG (
  id                 INT IDENTITY(1,1) NOT NULL,
  time               DATETIME NOT NULL,
  beginstring        CHAR(8) NOT NULL,
  sendercompid       VARCHAR(64) NOT NULL,
  sendersubid        VARCHAR(64) NOT NULL,
  senderlocid        VARCHAR(64) NOT NULL,
  targetcompid       VARCHAR(64) NOT NULL,
  targetsubid        VARCHAR(64) NOT NULL,
  targetlocid        VARCHAR(64) NOT NULL,
  session_qualifier  VARCHAR(64) NULL,
  text               NVARCHAR(MAX) NOT NULL,
  simulator_instance VARCHAR(128) NULL,
  MessageTypeTag     VARCHAR(3) NOT NULL DEFAULT '',
  MessageType        VARCHAR(100) NULL,
  TraceTradeReportID VARCHAR(20) NULL,
  msgseqnum          INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX idx_trace_fix_messages_log_time ON dbo.TRACE_FIX_MESSAGES_LOG(time);

CREATE TABLE dbo.TRACE_FIX_EVENT_LOG (
  id                 INT IDENTITY(1,1) NOT NULL,
  time               DATETIME NOT NULL,
  beginstring        CHAR(8) NOT NULL,
  sendercompid       VARCHAR(64) NOT NULL,
  sendersubid        VARCHAR(64) NOT NULL,
  senderlocid        VARCHAR(64) NOT NULL,
  targetcompid       VARCHAR(64) NOT NULL,
  targetsubid        VARCHAR(64) NOT NULL,
  targetlocid        VARCHAR(64) NOT NULL,
  session_qualifier  VARCHAR(64) NULL,
  text               NVARCHAR(MAX) NOT NULL,
  simulator_instance VARCHAR(128) NULL,
  PRIMARY KEY (id)
);
CREATE INDEX idx_trace_fix_event_log_time ON dbo.TRACE_FIX_EVENT_LOG(time);

-- Triggers: set simulator_instance from connection's application name (FixSimulator-Primary / FixSimulator-Secondary)
CREATE TRIGGER dbo.tr_TRACE_FIX_SESSIONS_set_simulator ON dbo.TRACE_FIX_SESSIONS AFTER INSERT AS
BEGIN SET NOCOUNT ON;
  UPDATE s SET s.simulator_instance = x.program_name
  FROM dbo.TRACE_FIX_SESSIONS s
  INNER JOIN inserted i ON s.beginstring = i.beginstring AND s.sendercompid = i.sendercompid AND s.sendersubid = i.sendersubid AND s.senderlocid = i.senderlocid
    AND s.targetcompid = i.targetcompid AND s.targetsubid = i.targetsubid AND s.targetlocid = i.targetlocid AND s.session_qualifier = i.session_qualifier
  CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
END;
GO
CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_set_simulator ON dbo.TRACE_FIX_MESSAGES AFTER INSERT AS
BEGIN SET NOCOUNT ON;
  UPDATE m SET m.simulator_instance = x.program_name
  FROM dbo.TRACE_FIX_MESSAGES m
  INNER JOIN inserted i ON m.beginstring = i.beginstring AND m.sendercompid = i.sendercompid AND m.sendersubid = i.sendersubid AND m.senderlocid = i.senderlocid
    AND m.targetcompid = i.targetcompid AND m.targetsubid = i.targetsubid AND m.targetlocid = i.targetlocid AND m.session_qualifier = i.session_qualifier AND m.msgseqnum = i.msgseqnum
  CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
END;
GO
CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_set_simulator ON dbo.TRACE_FIX_MESSAGES_LOG AFTER INSERT AS
BEGIN SET NOCOUNT ON;
  UPDATE m SET m.simulator_instance = x.program_name
  FROM dbo.TRACE_FIX_MESSAGES_LOG m INNER JOIN inserted i ON m.id = i.id
  CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
END;
GO
CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_parse_fix ON dbo.TRACE_FIX_MESSAGES_LOG AFTER INSERT AS
SET NOCOUNT ON;
UPDATE m SET
  m.MessageTypeTag = ISNULL(LEFT(parsed.MsgTypeTag, 3), ''),
  m.MessageType    = parsed.MsgTypeName,
  m.TraceTradeReportID = parsed.TradeRptID,
  m.msgseqnum      = parsed.MsgSeqNum,
  m.sendercompid   = COALESCE(NULLIF(LTRIM(RTRIM(parsed.tag49)), ''), i.sendercompid),
  m.targetcompid   = COALESCE(NULLIF(LTRIM(RTRIM(parsed.tag56)), ''), i.targetcompid),
  m.sendersubid    = COALESCE(NULLIF(LTRIM(RTRIM(parsed.tag50)), ''), i.sendersubid),
  m.targetsubid    = COALESCE(NULLIF(LTRIM(RTRIM(parsed.tag57)), ''), i.targetsubid)
FROM dbo.TRACE_FIX_MESSAGES_LOG m
INNER JOIN inserted i ON m.id = i.id
CROSS APPLY (
  SELECT
    t.tag35 AS MsgTypeTag,
    CASE t.tag35
      WHEN 'AE' THEN 'Trade Report'
      WHEN '0'  THEN 'Heartbeat'
      WHEN 'A'  THEN 'Logon'
      WHEN '5'  THEN 'Logout'
      WHEN '1'  THEN 'Test Request'
      WHEN '2'  THEN 'Resend Request'
      WHEN '3'  THEN 'Reject'
      WHEN '4'  THEN 'Sequence Reset'
      ELSE NULL END AS MsgTypeName,
    CASE WHEN CHARINDEX('571=', i.text) > 0 THEN
      LTRIM(RTRIM(REPLACE(REPLACE(
        SUBSTRING(i.text, CHARINDEX('571=', i.text) + 4,
          ISNULL(NULLIF(CHARINDEX(CHAR(1), i.text, CHARINDEX('571=', i.text) + 4), 0) - (CHARINDEX('571=', i.text) + 4), 20)),
        CHAR(1), ''), CHAR(2), '')))
    ELSE NULL END AS TradeRptID,
    CASE WHEN CHARINDEX('34=', i.text) > 0 THEN
      ISNULL(TRY_CAST(LTRIM(RTRIM(REPLACE(REPLACE(
        SUBSTRING(i.text, CHARINDEX('34=', i.text) + 3,
          CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX('34=', i.text) + 3) > 0
            THEN CHARINDEX(CHAR(1), i.text, CHARINDEX('34=', i.text) + 3) - (CHARINDEX('34=', i.text) + 3)
            ELSE 20 END),
        CHAR(1), ''), CHAR(2), ''))) AS INT), 0)
    ELSE 0 END AS MsgSeqNum,
    t.tag49,
    t.tag50,
    t.tag56,
    t.tag57
  FROM (
    SELECT
      CASE WHEN CHARINDEX('35=', i.text) > 0 THEN
        LTRIM(RTRIM(REPLACE(REPLACE(
          SUBSTRING(i.text, CHARINDEX('35=', i.text) + 3,
            CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX('35=', i.text) + 3) > 0
              THEN CHARINDEX(CHAR(1), i.text, CHARINDEX('35=', i.text) + 3) - (CHARINDEX('35=', i.text) + 3)
              ELSE 20 END),
          CHAR(1), ''), CHAR(2), '')))
      ELSE '' END AS tag35,
      CASE WHEN CHARINDEX('49=', i.text) > 0 THEN LTRIM(RTRIM(REPLACE(REPLACE(SUBSTRING(i.text, CHARINDEX('49=', i.text) + 3, CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX('49=', i.text) + 3) > 0 THEN CHARINDEX(CHAR(1), i.text, CHARINDEX('49=', i.text) + 3) - (CHARINDEX('49=', i.text) + 3) ELSE 64 END), CHAR(1), ''), CHAR(2), ''))) ELSE '' END AS tag49,
      CASE WHEN CHARINDEX('50=', i.text) > 0 THEN LTRIM(RTRIM(REPLACE(REPLACE(SUBSTRING(i.text, CHARINDEX('50=', i.text) + 3, CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX('50=', i.text) + 3) > 0 THEN CHARINDEX(CHAR(1), i.text, CHARINDEX('50=', i.text) + 3) - (CHARINDEX('50=', i.text) + 3) ELSE 64 END), CHAR(1), ''), CHAR(2), ''))) ELSE '' END AS tag50,
      CASE WHEN CHARINDEX('56=', i.text) > 0 THEN LTRIM(RTRIM(REPLACE(REPLACE(SUBSTRING(i.text, CHARINDEX('56=', i.text) + 3, CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX('56=', i.text) + 3) > 0 THEN CHARINDEX(CHAR(1), i.text, CHARINDEX('56=', i.text) + 3) - (CHARINDEX('56=', i.text) + 3) ELSE 64 END), CHAR(1), ''), CHAR(2), ''))) ELSE '' END AS tag56,
      CASE WHEN CHARINDEX('57=', i.text) > 0 THEN LTRIM(RTRIM(REPLACE(REPLACE(SUBSTRING(i.text, CHARINDEX('57=', i.text) + 3, CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX('57=', i.text) + 3) > 0 THEN CHARINDEX(CHAR(1), i.text, CHARINDEX('57=', i.text) + 3) - (CHARINDEX('57=', i.text) + 3) ELSE 64 END), CHAR(1), ''), CHAR(2), ''))) ELSE '' END AS tag57
  ) t
) parsed;
GO
CREATE TRIGGER dbo.tr_TRACE_FIX_EVENT_LOG_set_simulator ON dbo.TRACE_FIX_EVENT_LOG AFTER INSERT AS
BEGIN SET NOCOUNT ON;
  UPDATE e SET e.simulator_instance = x.program_name
  FROM dbo.TRACE_FIX_EVENT_LOG e INNER JOIN inserted i ON e.id = i.id
  CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
END;
GO

-- =============================================================================
-- PART 4: CREATE VIEWS
-- =============================================================================

CREATE VIEW dbo.vw_FIX_SessionHealth AS
SELECT
  beginstring,
  sendercompid,
  sendersubid,
  senderlocid,
  targetcompid,
  targetsubid,
  targetlocid,
  session_qualifier,
  creation_time,
  incoming_seqnum,
  outgoing_seqnum,
  simulator_instance
FROM dbo.TRACE_FIX_SESSIONS;

CREATE VIEW dbo.vw_FIX_MessageTraffic24h AS
SELECT
  id,
  time,
  beginstring,
  sendercompid,
  sendersubid,
  targetcompid,
  targetsubid,
  LEFT(text, 500) AS text_preview
FROM dbo.TRACE_FIX_MESSAGES_LOG
WHERE time >= DATEADD(HOUR, -24, GETUTCDATE());

CREATE VIEW dbo.vw_FIX_OpenOrders AS
SELECT
  id,
  time,
  beginstring,
  sendercompid,
  targetcompid,
  text
FROM dbo.TRACE_FIX_MESSAGES_LOG
WHERE text LIKE '%35=D%'
  AND time >= CAST(GETUTCDATE() AS DATE);

CREATE VIEW dbo.vw_FIX_FillsToday AS
SELECT
  id,
  time,
  beginstring,
  sendercompid,
  targetcompid,
  text
FROM dbo.TRACE_FIX_MESSAGES_LOG
WHERE text LIKE '%35=8%'
  AND time >= CAST(GETUTCDATE() AS DATE);
GO

-- =============================================================================
-- PART 5: TRACE_LIFECYCLE_STATE (create only if missing; not dropped above)
-- =============================================================================
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_LIFECYCLE_STATE')
CREATE TABLE dbo.TRACE_LIFECYCLE_STATE (
  session_key    VARCHAR(256) NOT NULL,
  trade_report_id VARCHAR(64) NOT NULL,
  state          VARCHAR(32) NOT NULL,
  updated_at     DATETIME NOT NULL DEFAULT GETUTCDATE(),
  PRIMARY KEY (session_key, trade_report_id)
);
GO

PRINT 'Recreate complete: TRACE_FIX_* tables and views recreated; TRACE_LIFECYCLE_STATE ensured.';
