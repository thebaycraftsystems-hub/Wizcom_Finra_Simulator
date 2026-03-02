# TRACE SP FIX Reference (Excel)

**This workbook is for SP (Securitized Products) only.** For CA and TS you will provide reference Excel files later; until then CA and TS use **config/finra/spec_required_tags.yaml**.

The simulator reads **TRACE_SP_FIX_Reference.xlsx** from this folder (`docs/`) for **SP** sessions to determine:

- **Mandatory / required fields** per message type (from the **Req'd** column: `Y` = required by FIX 4.4, `F` = required by FINRA)
- **Required-but-ignored** fields (from the **Comment / FINRA Notes** column: when the text contains "ignored", the field is required by FIX but its value is ignored by TRACE; missing does not cause reject)

## Sheets used

| Sheet             | Message type              | FIX MsgType | Use for TransType |
|-------------------|---------------------------|-------------|-------------------|
| 08_TradeNew       | Trade Capture Report – New| AE          | 0                 |
| 09_TradeCancel    | Trade Capture Report – Cancel | AE      | 1                 |
| 11_TradeCorrection| Trade Capture Report – Correct | AE     | 2                 |
| 10_TradeReversal  | Trade Capture Report – Reversal | AE    | 4                 |
| 01_Logon … 07_TestRequest | Admin messages   | A, 5, 4, 2, 3, 0, 1 | (reference)   |

Other sheets (Overview, RejectCodes, CustomFields, PartyRoles, Timestamps, Examples) are not used for validation.

## File location

- **Preferred:** `docs/TRACE_SP_FIX_Reference.xlsx` (relative to the process working directory, e.g. project root or install dir).
- **Fallback:** classpath resource `TRACE_SP_FIX_Reference.xlsx` (e.g. in the jar or `src/main/resources`).

- **SP:** If the Excel file is not found, the simulator falls back to **config/finra/spec_required_tags.yaml** for required and required-but-ignored tags.
- **CA / TS:** Always use **spec_required_tags.yaml** until TRACE CA and TRACE TS reference Excel files are provided.

## Column meaning

- **Tag #** – FIX tag number (or group tag like "→ 54" for Side).
- **FIX Field Name** – Official name (used in error messages).
- **Req'd** – `Y` = required by FIX 4.4, `F` = required by FINRA, blank = optional.
- **Comment / FINRA Notes** – If it contains "ignored", the tag is treated as required-but-ignored.
