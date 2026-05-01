# Spec reference and page numbers

## Do not reject on missing fields

**config/finra/compliance_options.yaml** sets `reject_on_missing_required: false` so that the simulator does **not** send SPRE when required or conditional fields are missing. Missing fields are logged at WARN; the trade is accepted. Set to `true` to restore reject-on-missing behaviour.

Validation errors include **field name** and **spec reference** (e.g. "See SP_2.1 p.15").

## Routing (56=FNRA, 57=SubID)

| 56 (TargetCompID) | 57 (TargetSubID) | Specification |
|-------------------|------------------|----------------|
| FNRA | SP | `docs/Specification/SP/SP_2.1.pdf` |
| FNRA | CA | `docs/Specification/CA/CA_2.1.pdf` |
| FNRA | TS | `docs/Specification/TS/TS_2.1.pdf` |

Product is taken from the **session** SenderSubID (SP/CA/TS).

## Updating page numbers

Edit **spec_references.yaml**. For each product (SP, CA, TS), set `page` for each tag to the page number in the corresponding PDF where that field is defined.

Example (after checking SP_2.1.pdf for a field):

```yaml
SP:
  spec_name: "SP_2.1"
  tags:
    571: { name: TradeReportID, page: 15 }
```

If `page` is 0 or missing, the error will show "SP_2.1 (see spec)" instead of "SP_2.1 p.15".

## Spec-driven required tags

**spec_required_tags.yaml** defines, per product (SP/CA/TS), only the tags that are **in the specification** as required. Validation checks only these tags. **OrderQty (38) and Symbol (55) are NOT in the specs** and are not listed there; missing 38 or 55 does not cause reject. Add or remove tags in that file to match the PDFs. Optional: use RAG over the spec PDFs to generate or audit this list.

## Tag numbers and field names (FIX44.xml)

**All tag numbers and field names** are taken from **FIX44.xml** (FIX 4.4 spec). The loader tries `docs/FIX44.xml` first, then classpath `FIX44.xml`. So SettlDate is **64**, SettlType is **63** (per FIX44.xml). **spec_required_tags.yaml** uses these same numbers. **tag_field_names.yaml** is only used as fallback when a tag is not in FIX44.xml.
