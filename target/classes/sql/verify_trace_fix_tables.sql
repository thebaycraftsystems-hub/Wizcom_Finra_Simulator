-- Run this in database trace_fix to verify the 4 tables exist.
-- In SSMS: select database "trace_fix" in the dropdown, then execute (F5).
USE trace_fix;
GO

SELECT name AS table_name
  FROM sys.tables
 WHERE name IN ('TRACE_FIX_SESSIONS', 'TRACE_FIX_MESSAGES', 'TRACE_FIX_MESSAGES_LOG', 'TRACE_FIX_EVENT_LOG')
 ORDER BY name;

-- If you see 4 rows above, tables are ready for the Simulator.
-- If you see 0 rows, run quickfixj_sqlserver_schema.sql in this database first.
