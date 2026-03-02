-- =============================================================================
-- QuickFIX/J for trace_fix: COMPLETE DROP + CREATE (tables and views)
-- =============================================================================
-- Run in database [trace_fix]. SSMS: select database trace_fix, then execute.
--
-- DROPS (in order):
--   1. Views: vw_FIX_FillsToday, vw_FIX_MessageTraffic24h, vw_FIX_OpenOrders, vw_FIX_SessionHealth
--   2. Tables: TRACE_FIX_MESSAGES_LOG, TRACE_FIX_EVENT_LOG, TRACE_FIX_MESSAGES, TRACE_FIX_SESSIONS
--
-- CREATES:
--   3. Tables: TRACE_FIX_SESSIONS, TRACE_FIX_MESSAGES, TRACE_FIX_MESSAGES_LOG, TRACE_FIX_EVENT_LOG
--   4. Views: vw_FIX_SessionHealth, vw_FIX_MessageTraffic24h, vw_FIX_OpenOrders, vw_FIX_FillsToday
--
-- WARNING: All data in the four tables is permanently deleted.
-- =============================================================================

USE trace_fix;
GO

-- =============================================================================
-- PART 1: DROP VIEWS (FIX-related views; drop before tables so no dependency errors)
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
-- PART 2: DROP TABLES (order: child-style first; these have no FKs, order optional)
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
-- PART 3: CREATE TABLES (full QuickFIX/J 2.x schema)
-- =============================================================================

-- Session state (JdbcStoreSessionsTableName=TRACE_FIX_SESSIONS)
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

-- Message log (JdbcLogIncomingTable / JdbcLogOutgoingTable = TRACE_FIX_MESSAGES_LOG)
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
CREATE INDEX idx_trace_fix_messages_log_time ON dbo.TRACE_FIX_MESSAGES_LOG(time);

-- Event log (JdbcLogEventTable=TRACE_FIX_EVENT_LOG)
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
CREATE INDEX idx_trace_fix_event_log_time ON dbo.TRACE_FIX_EVENT_LOG(time);
GO

-- =============================================================================
-- PART 4: CREATE VIEWS (adjust definitions if your originals differed)
-- =============================================================================

-- Session health: active sessions with seq numbers and creation time
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
  outgoing_seqnum
FROM dbo.TRACE_FIX_SESSIONS;

-- Message traffic in the last 24 hours
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

-- Open orders: message log entries that look like order-related (35=D NewOrderSingle); adjust filter as needed
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

-- Fills today: message log entries that look like executions (35=8 ExecutionReport); adjust filter as needed
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
