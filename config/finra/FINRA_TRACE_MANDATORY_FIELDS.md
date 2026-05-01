# FINRA TRACE – Mandatory fields (TradeCaptureReport 35=AE)

- **SP (Securitized Products):** Mandatory and required-but-ignored come from **docs/TRACE_SP_FIX_Reference.xlsx** when the file is present. Tag names in errors come from that workbook (else FIX44.xml).
- **CA and TS:** Use **spec_required_tags.yaml** until TRACE CA/TS reference sheets are provided. Tag names from FIX44.xml or tag_field_names.yaml.

## Currently required (spec_required_tags.yaml)

| Tag | FIX name       | Notes                    |
|-----|----------------|--------------------------|
| 571 | TradeReportID  | Client-generated ID      |
| 32  | LastQty        | Trade volume             |
| 31  | LastPx         | Price                    |
| 75  | TradeDate      | Trade date               |
| 60  | TransactTime   | Timestamp                |
| 552 | NoSides        | Number of sides (≥1)     |
| 48  | SecurityID     | CUSIP or symbol          |
| 22  | SecurityIDSource | 1=CUSIP, 8=Exchange Symbol |
| 64  | SettlDate      | Settlement date          |
| 939 | TradeReportType| Report type              |

Plus **conditional** by TransType (487): NEW (487, 22011), CANCEL (487, 569), CORRECTION (487, 569, 22011, 22012, 1003, 1126), REVERSAL (487, 22009).

## Not mandatory for FINRA TRACE

- **63 (SettlType)** – not required per FINRA TRACE.
- **38 (OrderQty)** – not in TRACE specs.
- **55 (Symbol)** – not in TRACE specs.
- **37 (OrderID), 17 (ExecID)** – in `required_but_ignored`; if missing, trade is still accepted.

To change the list, edit `spec_required_tags.yaml` for each product (SP, CA, TS).
