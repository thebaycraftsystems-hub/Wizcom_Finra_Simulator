-- Add senderlocid and targetlocid to existing QuickFIX/J tables (required by QuickFIX/J 2.x).
-- Run in database trace_fix when you see "JDBC store schema invalid... Falling back to file store".
-- In SSMS: select database trace_fix, then execute this script.

USE trace_fix;
GO

-- TRACE_FIX_SESSIONS: add columns and fix primary key
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_SESSIONS')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_SESSIONS') AND name = 'senderlocid')
    ALTER TABLE dbo.TRACE_FIX_SESSIONS ADD senderlocid VARCHAR(64) NOT NULL DEFAULT '';
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_SESSIONS') AND name = 'targetlocid')
    ALTER TABLE dbo.TRACE_FIX_SESSIONS ADD targetlocid VARCHAR(64) NOT NULL DEFAULT '';
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_SESSIONS') AND name = 'session_qualifier')
    ALTER TABLE dbo.TRACE_FIX_SESSIONS ADD session_qualifier VARCHAR(64) NOT NULL DEFAULT '';

  -- Recreate PK to include senderlocid, targetlocid (drop existing PK first)
  IF EXISTS (SELECT * FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID('dbo.TRACE_FIX_SESSIONS') AND type = 'PK')
  BEGIN
    DECLARE @pk_sessions NVARCHAR(256) = (SELECT name FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID('dbo.TRACE_FIX_SESSIONS') AND type = 'PK');
    EXEC('ALTER TABLE dbo.TRACE_FIX_SESSIONS DROP CONSTRAINT ' + @pk_sessions);
  END
  IF NOT EXISTS (SELECT * FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID('dbo.TRACE_FIX_SESSIONS') AND type = 'PK')
    ALTER TABLE dbo.TRACE_FIX_SESSIONS ADD CONSTRAINT PK_TRACE_FIX_SESSIONS PRIMARY KEY (
      beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier
    );
END
GO

-- TRACE_FIX_MESSAGES: add columns and fix primary key
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES') AND name = 'senderlocid')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES ADD senderlocid VARCHAR(64) NOT NULL DEFAULT '';
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES') AND name = 'targetlocid')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES ADD targetlocid VARCHAR(64) NOT NULL DEFAULT '';
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES') AND name = 'session_qualifier')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES ADD session_qualifier VARCHAR(64) NOT NULL DEFAULT '';

  IF EXISTS (SELECT * FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES') AND type = 'PK')
  BEGIN
    DECLARE @pk_msgs NVARCHAR(256) = (SELECT name FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES') AND type = 'PK');
    EXEC('ALTER TABLE dbo.TRACE_FIX_MESSAGES DROP CONSTRAINT ' + @pk_msgs);
  END
  IF NOT EXISTS (SELECT * FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES') AND type = 'PK')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES ADD CONSTRAINT PK_TRACE_FIX_MESSAGES PRIMARY KEY (
      beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier, msgseqnum
    );
END
GO

-- TRACE_FIX_MESSAGES_LOG: add columns (PK is id, no change)
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES_LOG')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES_LOG') AND name = 'senderlocid')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES_LOG ADD senderlocid VARCHAR(64) NOT NULL DEFAULT '';
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES_LOG') AND name = 'targetlocid')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES_LOG ADD targetlocid VARCHAR(64) NOT NULL DEFAULT '';
END
GO

-- TRACE_FIX_EVENT_LOG: add columns if missing (PK is id, no change)
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_EVENT_LOG')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_EVENT_LOG') AND name = 'senderlocid')
    ALTER TABLE dbo.TRACE_FIX_EVENT_LOG ADD senderlocid VARCHAR(64) NOT NULL DEFAULT '';
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_EVENT_LOG') AND name = 'targetlocid')
    ALTER TABLE dbo.TRACE_FIX_EVENT_LOG ADD targetlocid VARCHAR(64) NOT NULL DEFAULT '';
END
GO

PRINT 'Migration complete. Restart the simulator to use JDBC store.';
