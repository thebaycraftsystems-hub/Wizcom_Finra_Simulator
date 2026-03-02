# FINRA TRACE TS Lifecycle Transition Matrix

Used by the Lifecycle Engine. Invalid transitions result in SPRE (35=AR) with appropriate reject code.

## States

| State | Description |
|-------|-------------|
| NEW_SUBMITTED | Trade report submitted (35=AE, TransType=0) |
| ACCEPTED | Accepted (SPEN) |
| MATCHED | Matched (SPMA) |
| DNR | Do Not Report |
| CORRECTED | Correction (SPCR) |
| CANCELLED | Cancelled (STCN) |
| REJECTED | Rejected (SPRE) |
| IGR3_TRIGGERED | IGR3 duplicate/second NEW |

## Allowed Transitions

| From State | Event | To State | Condition / Reject |
|------------|-------|---------|--------------------|
| NEW_SUBMITTED | ACCEPT | ACCEPTED | Version 1, first NEW for TradeReportID |
| NEW_SUBMITTED | DUPLICATE_NEW | IGR3_TRIGGERED | 4053 Duplicate Trade Report ID |
| NEW_SUBMITTED | REJECT | REJECTED | Validation failure |
| ACCEPTED | MATCH | MATCHED | — |
| ACCEPTED | CORRECTION | CORRECTED | Version 2, OriginalTradeID/OriginalControlDate |
| ACCEPTED | CANCEL | CANCELLED | — |
| DNR | SECOND_NEW | IGR3_TRIGGERED | 4063 DNR - second NEW not allowed |
| CORRECTED | CANCEL | CANCELLED | — |

## Invalid Transitions → SPRE

- Any transition not in the matrix → **4055** (INVALID LIFECYCLE TRANSITION).
- CORRECTION (v2) when no ACCEPTED NEW → **4064** (CORRECTION ONLY AFTER ACCEPTED NEW).
- Duplicate TradeReportID for NEW → **4053** (DUPLICATE TRADE REPORT ID).

## Version Rules

- NEW: version 1.
- CORRECTION: version 2; must reference accepted NEW (OriginalTradeID / OriginalControlDate).

## Storage

Lifecycle state is stored in **TRACE_LIFECYCLE_STATE** only. Raw FIX message persistence (TRACE_FIX_MESSAGES_LOG, etc.) is unchanged and remains the responsibility of the existing QuickFIX/J log layer.
