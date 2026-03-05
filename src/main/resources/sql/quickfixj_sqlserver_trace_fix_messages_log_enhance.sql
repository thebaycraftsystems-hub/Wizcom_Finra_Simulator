-- TRACE_FIX_MESSAGES_LOG: add columns MessageTypeTag, MessageType, TraceTradeReportID, msgseqnum
-- and trigger to populate them from raw FIX message (text column). Run in same DB as TRACE_FIX_MESSAGES_LOG.
-- QuickFIX/J only inserts standard columns; this trigger parses "text" and sets the new columns.
-- MessageTypeTag/MessageType from tag 35; TraceTradeReportID from tag 571; msgseqnum from tag 34 (MsgSeqNum).
-- sendercompid/targetcompid/sendersubid/targetsubid from tags 49, 56, 50, 57 (same mapping for 49=FNRA and 49!=FNRA).

-- 1) Add columns (NOT NULL columns use default for existing rows)
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'TRACE_FIX_MESSAGES_LOG')
BEGIN
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES_LOG') AND name = 'MessageTypeTag')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES_LOG ADD MessageTypeTag VARCHAR(3) NOT NULL DEFAULT '';
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES_LOG') AND name = 'MessageType')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES_LOG ADD MessageType VARCHAR(100) NULL;
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES_LOG') AND name = 'TraceTradeReportID')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES_LOG ADD TraceTradeReportID VARCHAR(20) NULL;
  IF NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('dbo.TRACE_FIX_MESSAGES_LOG') AND name = 'msgseqnum')
    ALTER TABLE dbo.TRACE_FIX_MESSAGES_LOG ADD msgseqnum INT NOT NULL DEFAULT 0;
END
GO

-- 2) Trigger to set MessageTypeTag, MessageType, TraceTradeReportID, msgseqnum from raw FIX (text). Tag 35 → MessageTypeTag/MessageType; tag 571 → TraceTradeReportID; tag 34 → msgseqnum. FIX uses SOH (CHAR(1)) as delimiter.
IF EXISTS (SELECT * FROM sys.triggers WHERE name = 'tr_TRACE_FIX_MESSAGES_LOG_parse_fix')
  DROP TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_parse_fix;
GO

CREATE TRIGGER dbo.tr_TRACE_FIX_MESSAGES_LOG_parse_fix ON dbo.TRACE_FIX_MESSAGES_LOG
AFTER INSERT
AS
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
    -- Tag 34 (MsgSeqNum): parse as INT; use 0 when missing or invalid
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
