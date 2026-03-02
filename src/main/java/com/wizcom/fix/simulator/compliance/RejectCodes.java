package com.wizcom.fix.simulator.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/**
 * Loads reject codes from config/finra/reject_codes.yaml. No hardcoding.
 */
public final class RejectCodes {
    private static final Logger log = LoggerFactory.getLogger(RejectCodes.class);
    private static final String RESOURCE = "config/finra/reject_codes.yaml";

    private final Map<Integer, String> codes = new java.util.HashMap<>();

    @SuppressWarnings("unchecked")
    public RejectCodes() {
        Map<String, Object> data = loadYaml(RESOURCE);
        Object codesObj = data != null ? data.get("codes") : null;
        if (codesObj instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) codesObj).entrySet()) {
                Object k = e.getKey();
                Object v = e.getValue();
                int code;
                if (k instanceof Number) code = ((Number) k).intValue();
                else try { code = Integer.parseInt(String.valueOf(k)); } catch (NumberFormatException ex) { continue; }
                codes.put(code, v != null ? v.toString() : "REJECTED");
            }
        }
    }

    public String getText(int code) {
        return codes.getOrDefault(code, "REJECTED");
    }

    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream is = RejectCodes.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            return new Yaml().load(is);
        } catch (Exception e) {
            log.warn("Could not load {}: {}", resource, e.getMessage());
            return null;
        }
    }
}
