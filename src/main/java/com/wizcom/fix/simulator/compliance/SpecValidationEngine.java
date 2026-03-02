package com.wizcom.fix.simulator.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.MsgType;
import quickfix.fix44.TradeCaptureReport;

import java.io.InputStream;
import java.util.*;

/**
 * Validation uses TRACE_SP_FIX_Reference.xlsx when present (mandatory/required/ignored per message sheet);
 * otherwise falls back to config/finra/spec_required_tags.yaml.
 */
public class SpecValidationEngine {
    private static final Logger log = LoggerFactory.getLogger(SpecValidationEngine.class);
    private static final String SPEC_TAGS_RESOURCE = "config/finra/spec_required_tags.yaml";
    private static final String COMPLIANCE_OPTIONS = "config/finra/compliance_options.yaml";

    private final Map<String, Map<String, Object>> specByProduct;
    private final SpecReferenceHelper specRef;
    private final TraceReferenceLoader traceLoader;
    private final boolean rejectOnMissingRequired;

    @SuppressWarnings("unchecked")
    public SpecValidationEngine() {
        Map<String, Object> data = loadYaml(SPEC_TAGS_RESOURCE);
        Map<String, Map<String, Object>> byProduct = new HashMap<>();
        if (data != null) {
            for (String product : Arrays.asList("SP", "CA", "TS")) {
                Object prodObj = data.get(product);
                if (prodObj instanceof Map) byProduct.put(product, (Map<String, Object>) prodObj);
            }
        }
        this.specByProduct = byProduct;
        this.specRef = new SpecReferenceHelper();
        this.traceLoader = new TraceReferenceLoader(null);
        this.rejectOnMissingRequired = loadRejectOnMissingRequired();
    }

    private boolean loadRejectOnMissingRequired() {
        Map<String, Object> opts = loadYaml(COMPLIANCE_OPTIONS);
        if (opts == null) return true;
        Object v = opts.get("reject_on_missing_required");
        if (v == null) return true;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return !"false".equalsIgnoreCase(((String) v).trim());
        return true;
    }

    /**
     * Validate application message. For 35=AE (TradeCaptureReport) runs SP rules.
     * Other message types pass (admin handled elsewhere).
     */
    public ValidationResult validate(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getField(new MsgType()).getValue();
            if (!"AE".equals(msgType)) {
                return ValidationResult.ok();
            }
            return validateTradeCaptureReport((TradeCaptureReport) message, sessionID);
        } catch (Exception e) {
            log.debug("Validation error: {}", e.getMessage());
            return ValidationResult.fail(4062, "DATA TYPE VIOLATION");
        }
    }

    /** Reject any trade from Gateway when Price (31=LastPx) equals 555. */
    private static final double REJECT_PRICE_31 = 555.0;

    private ValidationResult validateTradeCaptureReport(TradeCaptureReport msg, SessionID sessionID) throws FieldNotFound {
        if (msg.isSetField(31)) {
            double lastPx = msg.getLastPx().getValue();
            if (Math.abs(lastPx - REJECT_PRICE_31) < 1e-9) {
                log.warn("Rejecting trade: 31 (LastPx/Price) = 555 from Gateway");
                return ValidationResult.fail(4059, "TRADE REJECTED: Price 31 (LastPx) must not be 555");
            }
        }
        String product = specRef.getProduct(sessionID);
        int transType = msg.getTradeReportTransType().getValue();
        String sheetName = TraceReferenceLoader.sheetForTradeReportTransType(transType);
        TraceSheetFields sheetFields = traceLoader.getSheetFields(sheetName);

        List<Integer> required;
        Set<Integer> requiredButIgnored;
        boolean useExcel = "SP".equals(product) && traceLoader.isLoaded() && sheetFields != null && !sheetFields.getRequiredTags().isEmpty();
        if (useExcel) {
            required = sheetFields.getRequiredTags();
            requiredButIgnored = sheetFields.getRequiredButIgnored();
        } else {
            Map<String, Object> productSpec = specByProduct.getOrDefault(product, specByProduct.get("SP"));
            if (productSpec == null) productSpec = Collections.emptyMap();
            required = toIntList(productSpec.get("required_tags"));
            requiredButIgnored = toIntSet(productSpec.get("required_but_ignored"));
            if (required.isEmpty()) {
                required = Arrays.asList(571, 32, 31, 75, 60, 552, 48, 22, 64, 939);
                requiredButIgnored = new HashSet<>(Arrays.asList(37, 17));
            }
        }

        List<String> missing = new ArrayList<>();

        for (int tag : required) {
            if (!msg.isSetField(tag)) {
                if (requiredButIgnored.contains(tag)) {
                    log.debug("Field {} is missing (required in FIX but ignored - trade is valid)", tag);
                    continue;
                }
                missing.add(tag + " (" + specRef.getFieldName(tag) + ")");
            }
        }
        if (!msg.isSetField(552) || msg.getNoSides().getValue() < 1) {
            missing.add("552 (NoSides)");
        }

        List<Integer> conditionalMissing = new ArrayList<>();
        if (!useExcel) {
            Map<String, Object> productSpec = specByProduct.getOrDefault(product, specByProduct.get("SP"));
            if (productSpec == null) productSpec = Collections.emptyMap();
            List<Integer> condNew = toIntList(productSpec.get("conditional_new"));
            List<Integer> condCancel = toIntList(productSpec.get("conditional_cancel"));
            List<Integer> condCorrection = toIntList(productSpec.get("conditional_correction"));
            List<Integer> condReversal = toIntList(productSpec.get("conditional_reversal"));
            if (transType == 0) for (int tag : condNew) { if (!msg.isSetField(tag)) conditionalMissing.add(tag); }
            else if (transType == 1) for (int tag : condCancel) { if (!msg.isSetField(tag)) conditionalMissing.add(tag); }
            else if (transType == 2) for (int tag : condCorrection) { if (!msg.isSetField(tag)) conditionalMissing.add(tag); }
            else if (transType == 4) for (int tag : condReversal) { if (!msg.isSetField(tag)) conditionalMissing.add(tag); }
        }
        for (int tag : conditionalMissing) {
            missing.add(tag + " (" + specRef.getFieldName(tag) + ", conditional)");
        }

        String side54Issue = checkSide54(msg);
        if (side54Issue != null) missing.add(side54Issue);

        if (!missing.isEmpty()) {
            String list = String.join(", ", missing);
            if (rejectOnMissingRequired) {
                return ValidationResult.fail(4058, "MISSING REQUIRED OR INVALID: " + list);
            }
            log.warn("The following fields are missing or invalid: {}. Not rejecting the trade/message.", list);
        }
        return ValidationResult.ok();
    }

    /** Returns a short description if Side 54 is invalid, or null if ok. */
    private String checkSide54(TradeCaptureReport msg) {
        try {
            if (!msg.isSetField(552)) return null;
            int noSides = msg.getNoSides().getValue();
            if (noSides < 1) return null;
            quickfix.fix44.TradeCaptureReport.NoSides sidesGroup = new quickfix.fix44.TradeCaptureReport.NoSides();
            for (int i = 1; i <= noSides; i++) {
                msg.getGroup(i, sidesGroup);
                int side = sidesGroup.getSide().getValue();
                if (side != 1 && side != 2) {
                    return "54 (Side) invalid value " + side + " (must be 1=BUY or 2=Sell)";
                }
            }
        } catch (Exception e) {
            return "54 (Side) " + e.getMessage();
        }
        return null;
    }

    private static List<Integer> toIntList(Object o) {
        List<Integer> out = new ArrayList<>();
        if (!(o instanceof Collection)) return out;
        for (Object x : (Collection<?>) o) {
            if (x instanceof Number) out.add(((Number) x).intValue());
            else if (x != null) try { out.add(Integer.parseInt(x.toString())); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static Set<Integer> toIntSet(Object o) {
        Set<Integer> out = new HashSet<>();
        if (!(o instanceof Collection)) return out;
        for (Object x : (Collection<?>) o) {
            if (x instanceof Number) out.add(((Number) x).intValue());
            else if (x != null) try { out.add(Integer.parseInt(x.toString())); } catch (NumberFormatException ignored) { }
        }
        return out;
    }


    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream is = SpecValidationEngine.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            return new Yaml().load(is);
        } catch (Exception e) {
            log.warn("Could not load {}: {}", resource, e.getMessage());
            return null;
        }
    }
}
