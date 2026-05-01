-- =============================================================================
-- Add simulator_instance column and triggers to record Primary vs Secondary
-- =============================================================================
-- Run in database [trace_fix] so you can see which simulator (Primary or
-- Secondary) inserted each row. The app sets JDBC applicationName to
-- FixSimulator-Primary or FixSimulator-Secondary; triggers set simulator_instance
-- from the connection's program_name (same value).
--
-- Run after quickfixj_sqlserver_schema.sql (or on existing DB).
-- Example: sqlcmd -S YOUR_SERVER -d trace_fix -U USER -P PASSWORD -i quickfixj_sqlserver_add_simulator_instance.sql
-- =============================================================================

USE trace_fix;
GO

-- TRACE_FIX_SESSIONS
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_SESSIONS')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_SESSIONS') AND name = 'simulator_instance')
    ALTER TABLE dbo.TRACE_FIX_SESSIONS ADD simulator_instance VARCHAR(128) NULL;
  IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_SESSIONS_set_simulator')
    DROP TRIGGER dbo.tr_TRACE_FIX_SESSIONS_set_simulator;
  EXEC('
    CREATE TRIGGER dbo.tr_TRACE_FIX_SESSIONS_set_simulator ON dbo.TRACE_FIX_SESSIONS
    AFTER INSERT
    AS
    BEGIN
      SET NOCOUNT ON;
      UPDATE s SET s.simulator_instance = x.program_name
      FROM dbo.TRACE_FIX_SESSIONS s
      INNER JOIN inserted i ON s.beginstring = i.beginstring AND s.sendercompid = i.sendercompid AND s.sendersubid = i.sendersubid AND s.senderlocid = i.senderlocid
        AND s.targetcompid = i.targetcompid AND s.targetsubid = i.targetsubid AND s.targetlocid = i.targetlocid AND s.session_qualifier = i.session_qualifier
      CROSS APPLY (SELECT program_name AS program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
    END');
END
GO

-- TRACE_FIX_MESSAGES
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES') AND name = 'simulator_instance')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES ADD simulator_instance VARCHAR(128) NULL;
  IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_set_simulator')
    DROP TRIGGER dbo.tr_TRACE_FIX_MESSAGES_set_simulator;
  EXEC('
    CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_set_simulator ON dbo.TRACE_FIX_MESSAGES
    AFTER INSERT
    AS
    BEGIN
      SET NOCOUNT ON;
      UPDATE m SET m.simulator_instance = x.program_name
      FROM dbo.TRACE_FIX_MESSAGES m
      INNER JOIN inserted i ON m.beginstring = i.beginstring AND m.sendercompid = i.sendercompid AND m.sendersubid = i.sendersubid AND m.senderlocid = i.senderlocid
        AND m.targetcompid = i.targetcompid AND m.targetsubid = i.targetsubid AND m.targetlocid = i.targetlocid AND m.session_qualifier = i.session_qualifier AND m.msgseqnum = i.msgseqnum
      CROSS APPLY (SELECT program_name AS program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
    END');
END
GO

-- TRACE_FIX_MESSAGES_LOG
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES_LOG')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES_LOG') AND name = 'simulator_instance')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES_LOG ADD simulator_instance VARCHAR(128) NULL;
  IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_LOG_set_simulator')
    DROP TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_set_simulator;
  EXEC('
    CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_set_simulator ON dbo.TRACE_FIX_MESSAGES_LOG
    AFTER INSERT
    AS
    BEGIN
      SET NOCOUNT ON;
      UPDATE m SET m.simulator_instance = x.program_name
      FROM dbo.TRACE_FIX_MESSAGES_LOG m
      INNER JOIN inserted i ON m.id = i.id
      CROSS APPLY (SELECT program_name AS program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
    END');
END
GO

-- TRACE_FIX_EVENT_LOG
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_EVENT_LOG')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_EVENT_LOG') AND name = 'simulator_instance')
    ALTER TABLE dbo.TRACE_FIX_EVENT_LOG ADD simulator_instance VARCHAR(128) NULL;
  IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_EVENT_LOG_set_simulator')
    DROP TRIGGER dbo.tr_TRACE_FIX_EVENT_LOG_set_simulator;
  EXEC('
    CREATE TRIGGER dbo.tr_TRACE_FIX_EVENT_LOG_set_simulator ON dbo.TRACE_FIX_EVENT_LOG
    AFTER INSERT
    AS
    BEGIN
      SET NOCOUNT ON;
      UPDATE e SET e.simulator_instance = x.program_name
      FROM dbo.TRACE_FIX_EVENT_LOG e
      INNER JOIN inserted i ON e.id = i.id
      CROSS APPLY (SELECT program_name AS program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID) x;
    END');
END
GO

PRINT 'simulator_instance column and triggers added. Values will be FixSimulator-Primary or FixSimulator-Secondary.';
