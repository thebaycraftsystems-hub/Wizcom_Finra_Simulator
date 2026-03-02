package com.wizcom.fix.simulator.compliance;

import com.wizcom.fix.simulator.specs.SpecChunk;
import com.wizcom.fix.simulator.specs.SpecRetrieval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import quickfix.SessionID;

import java.io.InputStream;
import java.util.*;

/**
 * Resolves tag -> field name from TRACE_SP_FIX_Reference.xlsx when present, else FIX44.xml; spec page from spec_references.yaml / spec index.
 */
public final class SpecReferenceHelper {
    private static final Logger log = LoggerFactory.getLogger(SpecReferenceHelper.class);
    private static final String SPEC_REF = "config/finra/spec_references.yaml";
    private static final String TAG_NAMES_YAML = "config/finra/tag_field_names.yaml";

    private final TraceReferenceLoader traceLoader;
    private final FIX44TagLoader fix44Tags;
    private final Map<Integer, String> tagNamesYaml = new HashMap<>();
    private final Map<String, Map<String, Object>> specByProduct = new HashMap<>();
    private SpecRetrieval specRetrieval;

    @SuppressWarnings("unchecked")
    public SpecReferenceHelper() {
        this.traceLoader = new TraceReferenceLoader(null);
        this.fix44Tags = new FIX44TagLoader(null);
        loadTagNamesYaml();
        loadSpecReferences();
    }

    @SuppressWarnings("unchecked")
    private void loadTagNamesYaml() {
        Map<String, Object> data = loadYaml(TAG_NAMES_YAML);
        if (data == null) return;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) data).entrySet()) {
            int tag;
            Object k = e.getKey();
            if (k instanceof Number) tag = ((Number) k).intValue();
            else try { tag = Integer.parseInt(String.valueOf(k)); } catch (NumberFormatException ex) { continue; }
            tagNamesYaml.put(tag, e.getValue() != null ? e.getValue().toString() : "Tag" + tag);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadSpecReferences() {
        Map<String, Object> data = loadYaml(SPEC_REF);
        if (data == null) return;
        for (String product : Arrays.asList("SP", "CA", "TS")) {
            Object prodObj = data.get(product);
            if (prodObj instanceof Map) specByProduct.put(product, (Map<String, Object>) prodObj);
        }
    }

    /**
     * Product from session: 56=FNRA and 57=SP -> SP; 57=CA -> CA; 57=TS -> TS.
     * Acceptor session: SenderSubID is SP/CA/TS.
     */
    public String getProduct(SessionID sessionID) {
        String sub = sessionID.getSenderSubID();
        if (sub == null) sub = "";
        if (sub.equalsIgnoreCase("SP")) return "SP";
        if (sub.equalsIgnoreCase("CA")) return "CA";
        if (sub.equalsIgnoreCase("TS")) return "TS";
        return "SP";
    }

    /** Field name from TRACE Excel when loaded, else FIX44.xml, else tag_field_names.yaml; else "TagNN". */
    public String getFieldName(int tag) {
        if (traceLoader.isLoaded()) {
            String fromTrace = traceLoader.getFieldName(tag);
            if (!("Tag" + tag).equals(fromTrace)) return fromTrace;
        }
        String fromFix44 = fix44Tags.getFieldName(tag);
        if (!("Tag" + tag).equals(fromFix44)) return fromFix44;
        return tagNamesYaml.getOrDefault(tag, "Tag" + tag);
    }

    /**
     * Spec reference for product and tag: "SpecName p.N" (exact page when available) or "SpecName (see spec)".
     * If YAML has page 0, looks up page from Java spec index when index exists (no PDF load).
     */
    public String getSpecReference(SessionID sessionID, int tag) {
        String product = getProduct(sessionID);
        Map<String, Object> prodSpec = specByProduct.get(product);
        String specName = prodSpec != null ? (String) prodSpec.get("spec_name") : null;
        if (specName == null) specName = "";

        int pageFromYaml = 0;
        if (prodSpec != null) {
            Object tagsObj = prodSpec.get("tags");
            if (tagsObj instanceof Map) {
                Object tagObj = ((Map<?, ?>) tagsObj).get(String.valueOf(tag));
                if (tagObj instanceof Map) {
                    Object pageObj = ((Map<?, ?>) tagObj).get("page");
                    if (pageObj instanceof Number) pageFromYaml = ((Number) pageObj).intValue();
                }
            }
        }
        if (pageFromYaml > 0) return specName + " p." + pageFromYaml;

        int pageFromIndex = lookupPageFromSpecIndex(product, tag);
        if (pageFromIndex > 0) return specName + " p." + pageFromIndex;
        return specName.isEmpty() ? "" : specName + " (see spec)";
    }

    /** Look up exact page for tag in product from Java spec index (only if index exists; no build). */
    private int lookupPageFromSpecIndex(String product, int tag) {
        try {
            if (specRetrieval == null) specRetrieval = new SpecRetrieval(null);
            if (!specRetrieval.indexExists()) return 0;
            String fieldName = getFieldName(tag);
            String query = fieldName + " " + tag;
            List<SpecChunk> hits = specRetrieval.queryIfIndexExists(query, product, 1);
            if (!hits.isEmpty() && hits.get(0).getPage() > 0) return hits.get(0).getPage();
        } catch (Exception e) {
            log.debug("Spec page lookup failed for tag {}: {}", tag, e.getMessage());
        }
        return 0;
    }

    /**
     * Build detailed message: "MISSING REQUIRED FIELD 38 (OrderQty). See SP_2.1 p.15"
     */
    public String buildMissingFieldMessage(int tag, SessionID sessionID) {
        String name = getFieldName(tag);
        String ref = getSpecReference(sessionID, tag);
        if (ref.isEmpty()) return "MISSING REQUIRED FIELD " + tag + " (" + name + ")";
        return "MISSING REQUIRED FIELD " + tag + " (" + name + "). See " + ref;
    }

    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream is = SpecReferenceHelper.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            return new Yaml().load(is);
        } catch (Exception e) {
            log.warn("Could not load {}: {}", resource, e.getMessage());
            return null;
        }
    }
}
