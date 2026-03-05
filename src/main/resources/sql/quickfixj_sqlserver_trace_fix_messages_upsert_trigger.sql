-- TRACE_FIX_MESSAGES: allow both Primary and Secondary to write (upsert by session+msgseqnum).
-- QuickFIX/J does INSERT only; when Secondary inserts the same session+msgseqnum already written by Primary,
-- the INSERT would fail with duplicate key, so no Secondary rows appeared. This trigger runs INSTEAD OF INSERT
-- and does a MERGE: if row exists (same PK), UPDATE message and simulator_instance; otherwise INSERT.
-- Run in the same database as TRACE_FIX_MESSAGES (e.g. trace_simulator or trace_fix).

-- Drop the existing AFTER INSERT trigger (it only runs when INSERT succeeds; we replace with INSTEAD OF).
IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_set_simulator')
  DROP TRIGGER dbo.tr_TRACE_FIX_MESSAGES_set_simulator;
GO

-- INSTEAD OF INSERT: MERGE so Secondary (and Primary) can both "insert" the same session+msgseqnum.
IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_instead_of_insert')
  DROP TRIGGER dbo.tr_TRACE_FIX_MESSAGES_instead_of_insert;
GO

CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_instead_of_insert ON dbo.TRACE_FIX_MESSAGES
INSTEAD OF INSERT
AS
SET NOCOUNT ON;
DECLARE @program_name VARCHAR(128) = (SELECT program_name FROM sys.dm_exec_sessions WHERE session_id = @@SPID);

MERGE dbo.TRACE_FIX_MESSAGES AS t
USING (SELECT beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier, msgseqnum, message FROM inserted) AS i
ON t.beginstring = i.beginstring AND t.sendercompid = i.sendercompid AND t.sendersubid = i.sendersubid AND t.senderlocid = i.senderlocid
   AND t.targetcompid = i.targetcompid AND t.targetsubid = i.targetsubid AND t.targetlocid = i.targetlocid AND t.session_qualifier = i.session_qualifier
   AND t.msgseqnum = i.msgseqnum
WHEN MATCHED THEN
  UPDATE SET t.message = i.message, t.simulator_instance = @program_name
WHEN NOT MATCHED BY TARGET THEN
  INSERT (beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid, session_qualifier, msgseqnum, message, simulator_instance)
  VALUES (i.beginstring, i.sendercompid, i.sendersubid, i.senderlocid, i.targetcompid, i.targetsubid, i.targetlocid, i.session_qualifier, i.msgseqnum, i.message, @program_name);
GO
