-- =============================================================================
-- DROP and RECREATE TRACE_FIX_MESSAGES_LOG (table + index + triggers)
-- =============================================================================
-- Run in database [trace_fix]. All data in TRACE_FIX_MESSAGES_LOG will be lost.
-- Example: sqlcmd -S SERVER -d trace_fix -U USER -P PASSWORD -i quickfixj_sqlserver_trace_fix_messages_log_recreate.sql
-- =============================================================================

USE trace_fix;
GO

-- Drop triggers first (they reference the table)
IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_LOG_parse_fix')
  DROP TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_parse_fix;
GO
IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_LOG_set_simulator')
  DROP TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_set_simulator;
GO

-- Drop table
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES_LOG')
  DROP TABLE dbo.TRACE_FIX_MESSAGES_LOG;
GO

-- Create table
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
GO

CREATE INDEX idx_trace_fix_messages_log_time ON dbo.TRACE_FIX_MESSAGES_LOG(time);
GO

-- Trigger: set simulator_instance from connection application name
CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_set_simulator ON dbo.TRACE_FIX_MESSAGES_LOG
AFTER INSERT
AS
BEGIN
  SET NOCOUNT ON;
  UPDATE m SET m.simulator_instance = x.program_name
  FROM dbo.TRACE_FIX_MESSAGES_LOG m
  INNER JOIN inserted i ON m.id = i.id
  CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
END;
GO

-- Trigger: parse FIX tags 34, 35, 571 from text into msgseqnum, MessageTypeTag, MessageType, TraceTradeReportID
CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_parse_fix ON dbo.TRACE_FIX_MESSAGES_LOG
AFTER INSERT
AS
SET NOCOUNT ON;
UPDATE m SET
  m.MessageTypeTag = ISNULL(LEFT(parsed.MsgTypeTag, 3), ''),
  m.MessageType    = parsed.MsgTypeName,
  m.TraceTradeReportID = parsed.TradeRptID,
  m.msgseqnum      = parsed.MsgSeqNum
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
    ELSE 0 END AS MsgSeqNum
  FROM (
    SELECT
      CASE WHEN CHARINDEX('35=', i.text) > 0 THEN
        LTRIM(RTRIM(REPLACE(REPLACE(
          SUBSTRING(i.text, CHARINDEX('35=', i.text) + 3,
            CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX('35=', i.text) + 3) > 0
              THEN CHARINDEX(CHAR(1), i.text, CHARINDEX('35=', i.text) + 3) - (CHARINDEX('35=', i.text) + 3)
              ELSE 20 END),
          CHAR(1), ''), CHAR(2), '')))
      ELSE '' END AS tag35
  ) t
) parsed;
GO

PRINT 'TRACE_FIX_MESSAGES_LOG recreated (table + index + triggers).';
