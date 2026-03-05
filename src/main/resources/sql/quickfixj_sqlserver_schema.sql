-- QuickFIX/J JDBC schema for SQL Server (table names match quickfixj-server.cfg: TRACE_FIX_*)
-- This project uses database trace_fix only. Do NOT use or create database "quickfix".
-- Run in database trace_fix when UseJdbcStore=Y and LogToDB=Y.
-- New DB: run this script. Existing tables missing senderlocid/targetlocid? Run quickfixj_sqlserver_add_locid_columns.sql instead.
-- Example: sqlcmd -S 192.168.1.14,1433 -d trace_fix -U wizcom -P Wizcom@12345 -i quickfixj_sqlserver_schema.sql
-- In SSMS: select database trace_fix, then execute this script.

USE trace_fix;
GO

-- Session state (JdbcStoreSessionsTableName=TRACE_FIX_SESSIONS)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_SESSIONS')
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

-- Stored messages for resend (JdbcStoreMessagesTableName=TRACE_FIX_MESSAGES)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES')
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

-- Message log (JdbcLogIncomingTable/OutgoingTable=TRACE_FIX_MESSAGES_LOG)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES_LOG')
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
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE object_id = OBJECT_ID('TRACE_FIX_MESSAGES_LOG') AND name = 'idx_trace_fix_messages_log_time')
  CREATE INDEX idx_trace_fix_messages_log_time ON TRACE_FIX_MESSAGES_LOG(time);

-- Event log (JdbcLogEventTable=TRACE_FIX_EVENT_LOG)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_EVENT_LOG')
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
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE object_id = OBJECT_ID('TRACE_FIX_EVENT_LOG') AND name = 'idx_trace_fix_event_log_time')
  CREATE INDEX idx_trace_fix_event_log_time ON TRACE_FIX_EVENT_LOG(time);

-- Simulator instance triggers (record Primary vs Secondary per row). Run quickfixj_sqlserver_add_simulator_instance.sql for existing DBs.
-- TRACE_FIX_MESSAGES: use INSTEAD OF INSERT (upsert) so both Primary and Secondary can write; otherwise Secondary INSERT fails with duplicate key.
IF NOT EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_SESSIONS_set_simulator')
  EXEC('CREATE TRIGGER dbo.tr_TRACE_FIX_SESSIONS_set_simulator ON dbo.TRACE_FIX_SESSIONS AFTER INSERT AS BEGIN SET NOCOUNT ON; UPDATE s SET s.simulator_instance = x.program_name FROM dbo.TRACE_FIX_SESSIONS s INNER JOIN inserted i ON s.beginstring = i.beginstring AND s.sendercompid = i.sendercompid AND s.sendersubid = i.sendersubid AND s.senderlocid = i.senderlocid AND s.targetcompid = i.targetcompid AND s.targetsubid = i.targetsubid AND s.targetlocid = i.targetlocid AND s.session_qualifier = i.session_qualifier CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x; END');
IF NOT EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_instead_of_insert')
  EXEC('CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_instead_of_insert ON dbo.TRACE_FIX_MESSAGES INSTEAD OF INSERT AS SET NOCOUNT ON; DECLARE @program_name VARCHAR(128) = (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID); MERGE dbo.TRACE_FIX_MESSAGES AS t USING (SELECT beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier, msgseqnum, message FROM inserted) AS i ON t.beginstring = i.beginstring AND t.sendercompid = i.sendercompid AND t.sendersubid = i.sendersubid AND t.senderlocid = i.senderlocid AND t.targetcompid = i.targetcompid AND t.targetsubid = i.targetsubid AND t.targetlocid = i.targetlocid AND t.session_qualifier = i.session_qualifier AND t.msgseqnum = i.msgseqnum WHEN MATCHED THEN UPDATE SET t.message = i.message, t.simulator_instance = @program_name WHEN NOT MATCHED BY TARGET THEN INSERT (beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier, msgseqnum, message, simulator_instance) VALUES (i.beginstring, i.sendercompid, i.sendersubid, i.senderlocid, i.targetcompid, i.targetsubid, i.targetlocid, i.session_qualifier, i.msgseqnum, i.message, @program_name);');
IF NOT EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_LOG_set_simulator')
  EXEC('CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_set_simulator ON dbo.TRACE_FIX_MESSAGES_LOG AFTER INSERT AS BEGIN SET NOCOUNT ON; UPDATE m SET m.simulator_instance = x.program_name FROM dbo.TRACE_FIX_MESSAGES_LOG m INNER JOIN inserted i ON m.id = i.id CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x; END');
-- Parse FIX tags 34, 35, 571 from text into msgseqnum, MessageTypeTag, MessageType, TraceTradeReportID (run quickfixj_sqlserver_trace_fix_messages_log_enhance.sql for existing DBs)
IF NOT EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_LOG_parse_fix')
  EXEC('CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_parse_fix ON dbo.TRACE_FIX_MESSAGES_LOG AFTER INSERT AS SET NOCOUNT ON; UPDATE m SET m.MessageTypeTag = ISNULL(LEFT(parsed.MsgTypeTag, 3), ''''''), m.MessageType = parsed.MsgTypeName, m.TraceTradeReportID = parsed.TradeRptID, m.msgseqnum = parsed.MsgSeqNum FROM dbo.TRACE_FIX_MESSAGES_LOG m INNER JOIN inserted i ON m.id = i.id CROSS APPLY (SELECT t.tag35 AS MsgTypeTag, CASE t.tag35 WHEN ''AE'' THEN ''Trade Report'' WHEN ''0'' THEN ''Heartbeat'' WHEN ''A'' THEN ''Logon'' WHEN ''5'' THEN ''Logout'' WHEN ''1'' THEN ''Test Request'' WHEN ''2'' THEN ''Resend Request'' WHEN ''3'' THEN ''Reject'' WHEN ''4'' THEN ''Sequence Reset'' ELSE NULL END AS MsgTypeName, CASE WHEN CHARINDEX(''571='', i.text) > 0 THEN LTRIM(RTRIM(REPLACE(REPLACE(SUBSTRING(i.text, CHARINDEX(''571='', i.text) + 4, ISNULL(NULLIF(CHARINDEX(CHAR(1), i.text, CHARINDEX(''571='', i.text) + 4), 0) - (CHARINDEX(''571='', i.text) + 4), 20)), CHAR(1), ''''''), CHAR(2), '''''')) ELSE NULL END AS TradeRptID, CASE WHEN CHARINDEX(''34='', i.text) > 0 THEN ISNULL(TRY_CAST(LTRIM(RTRIM(REPLACE(REPLACE(SUBSTRING(i.text, CHARINDEX(''34='', i.text) + 3, CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX(''34='', i.text) + 3) > 0 THEN CHARINDEX(CHAR(1), i.text, CHARINDEX(''34='', i.text) + 3) - (CHARINDEX(''34='', i.text) + 3) ELSE 20 END), CHAR(1), ''''''), CHAR(2), '''''')) AS INT), 0) ELSE 0 END AS MsgSeqNum FROM (SELECT CASE WHEN CHARINDEX(''35='', i.text) > 0 THEN LTRIM(RTRIM(REPLACE(REPLACE(SUBSTRING(i.text, CHARINDEX(''35='', i.text) + 3, CASE WHEN CHARINDEX(CHAR(1), i.text, CHARINDEX(''35='', i.text) + 3) > 0 THEN CHARINDEX(CHAR(1), i.text, CHARINDEX(''35='', i.text) + 3) - (CHARINDEX(''35='', i.text) + 3) ELSE 20 END), CHAR(1), ''''''), CHAR(2), '''''')) ELSE '''''' END AS tag35) t) parsed;');
IF NOT EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_EVENT_LOG_set_simulator')
  EXEC('CREATE TRIGGER dbo.tr_TRACE_FIX_EVENT_LOG_set_simulator ON dbo.TRACE_FIX_EVENT_LOG AFTER INSERT AS BEGIN SET NOCOUNT ON; UPDATE e SET e.simulator_instance = x.program_name FROM dbo.TRACE_FIX_EVENT_LOG e INNER JOIN inserted i ON e.id = i.id CROSS APPLY (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x; END');
