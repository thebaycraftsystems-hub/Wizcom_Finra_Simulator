-- Lifecycle state storage for FINRA TS compliance engine only.
-- This table is separate from message dump tables (TRACE_FIX_*). Do not use for raw FIX persistence.
-- Run in database trace_fix.

USE trace_fix;
GO

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_LIFECYCLE_STATE')
CREATE TABLE dbo.TRACE_LIFECYCLE_STATE (
  session_key   VARCHAR(256) NOT NULL,
  trade_report_id VARCHAR(64) NOT NULL,
  state         VARCHAR(32) NOT NULL,
  updated_at    DATETIME NOT NULL DEFAULT GETUTCDATE(),
  PRIMARY KEY (session_key, trade_report_id)
);
GO
