-- QuickFIX/J JDBC schema for SQL Server (table names match quickfixj-server.cfg: TRACE_FIX_*)
-- This project uses database trace_fix only. Do NOT use or create database "quickfix".
-- Run in database trace_fix when UseJdbcStore=Y and LogToDB=Y.
-- Example: sqlcmd -S 192.168.1.14,1433 -d trace_fix -U wizcom -P Wizcom@12345 -i quickfixj_sqlserver_schema.sql
-- In SSMS: select database trace_fix, then execute this script.

USE trace_fix;
GO

-- Session state (JdbcStoreSessionsTableName=TRACE_FIX_SESSIONS)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_SESSIONS')
CREATE TABLE dbo.TRACE_FIX_SESSIONS (
  beginstring       CHAR(8) NOT NULL,
  sendercompid      VARCHAR(64) NOT NULL,
  sendersubid       VARCHAR(64) NOT NULL,
  senderlocid       VARCHAR(64) NOT NULL,
  targetcompid      VARCHAR(64) NOT NULL,
  targetsubid       VARCHAR(64) NOT NULL,
  targetlocid       VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) NOT NULL,
  creation_time     DATETIME NOT NULL,
  incoming_seqnum   INT NOT NULL,
  outgoing_seqnum   INT NOT NULL,
  PRIMARY KEY (beginstring, sendercompid, sendersubid, senderlocid,
               targetcompid, targetsubid, targetlocid, session_qualifier)
);

-- Stored messages for resend (JdbcStoreMessagesTableName=TRACE_FIX_MESSAGES)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES')
CREATE TABLE dbo.TRACE_FIX_MESSAGES (
  beginstring       CHAR(8) NOT NULL,
  sendercompid      VARCHAR(64) NOT NULL,
  sendersubid       VARCHAR(64) NOT NULL,
  senderlocid       VARCHAR(64) NOT NULL,
  targetcompid      VARCHAR(64) NOT NULL,
  targetsubid       VARCHAR(64) NOT NULL,
  targetlocid       VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) NOT NULL,
  msgseqnum         INT NOT NULL,
  message           NVARCHAR(MAX) NOT NULL,
  PRIMARY KEY (beginstring, sendercompid, sendersubid, senderlocid,
               targetcompid, targetsubid, targetlocid, session_qualifier,
               msgseqnum)
);

-- Message log (JdbcLogIncomingTable/OutgoingTable=TRACE_FIX_MESSAGES_LOG)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES_LOG')
CREATE TABLE dbo.TRACE_FIX_MESSAGES_LOG (
  id                INT IDENTITY(1,1) NOT NULL,
  time              DATETIME NOT NULL,
  beginstring       CHAR(8) NOT NULL,
  sendercompid      VARCHAR(64) NOT NULL,
  sendersubid       VARCHAR(64) NOT NULL,
  senderlocid       VARCHAR(64) NOT NULL,
  targetcompid      VARCHAR(64) NOT NULL,
  targetsubid       VARCHAR(64) NOT NULL,
  targetlocid       VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) NULL,
  text              NVARCHAR(MAX) NOT NULL,
  PRIMARY KEY (id)
);
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE object_id = OBJECT_ID('TRACE_FIX_MESSAGES_LOG') AND name = 'idx_trace_fix_messages_log_time')
  CREATE INDEX idx_trace_fix_messages_log_time ON TRACE_FIX_MESSAGES_LOG(time);

-- Event log (JdbcLogEventTable=TRACE_FIX_EVENT_LOG)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_EVENT_LOG')
CREATE TABLE dbo.TRACE_FIX_EVENT_LOG (
  id                INT IDENTITY(1,1) NOT NULL,
  time              DATETIME NOT NULL,
  beginstring       CHAR(8) NOT NULL,
  sendercompid      VARCHAR(64) NOT NULL,
  sendersubid       VARCHAR(64) NOT NULL,
  senderlocid       VARCHAR(64) NOT NULL,
  targetcompid      VARCHAR(64) NOT NULL,
  targetsubid       VARCHAR(64) NOT NULL,
  targetlocid       VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) NULL,
  text              NVARCHAR(MAX) NOT NULL,
  PRIMARY KEY (id)
);
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE object_id = OBJECT_ID('TRACE_FIX_EVENT_LOG') AND name = 'idx_trace_fix_event_log_time')
  CREATE INDEX idx_trace_fix_event_log_time ON TRACE_FIX_EVENT_LOG(time);
