package com.wizcom.fix.simulator.specs;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 * Loads FINRA spec PDFs and extracts text per page (Java-only, no GPU).
 * Paths are relative to a base directory (e.g. user.dir on Linux).
 */
public class SpecLoader {
    private static final Logger log = LoggerFactory.getLogger(SpecLoader.class);
    private static final String SPEC_TAGS_RESOURCE = "config/finra/spec_required_tags.yaml";

    private final File baseDir;

    public SpecLoader(File baseDir) {
        this.baseDir = baseDir != null ? baseDir : new File(System.getProperty("user.dir", "."));
    }

    /**
     * Load spec document list from YAML (spec_file, spec_name, product) then extract text from each PDF.
     */
    @SuppressWarnings("unchecked")
    public List<SpecChunk> loadAll() {
        List<SpecChunk> out = new ArrayList<>();
        Map<String, Object> config = loadSpecConfig();
        if (config == null) {
            addDefaultSpecs(out);
            return out;
        }
        for (String product : Arrays.asList("SP", "CA", "TS")) {
            Object prodObj = config.get(product);
            if (!(prodObj instanceof Map)) continue;
            Map<String, Object> prod = (Map<String, Object>) prodObj;
            String specFile = getString(prod, "spec_file");
            String specName = getString(prod, "spec_name");
            if (specFile == null || specFile.isEmpty()) continue;
            File pdf = new File(baseDir, specFile.replace("/", File.separator));
            if (!pdf.isFile()) {
                log.warn("Spec PDF not found: {}", pdf.getAbsolutePath());
                continue;
            }
            extractPdf(pdf, product, specName != null ? specName : product, out);
        }
        return out;
    }

    private void addDefaultSpecs(List<SpecChunk> out) {
        String[] paths = { "docs/Specification/SP/SP_2.1.pdf", "docs/Specification/CA/CA_2.1.pdf", "docs/Specification/TS/TS_2.1.pdf" };
        String[] products = { "SP", "CA", "TS" };
        String[] names = { "SP_2.1", "CA_2.1", "TS_2.1" };
        for (int i = 0; i < paths.length; i++) {
            File pdf = new File(baseDir, paths[i].replace("/", File.separator));
            if (pdf.isFile()) extractPdf(pdf, products[i], names[i], out);
        }
    }

    private void extractPdf(File pdf, String product, String specName, List<SpecChunk> out) {
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = doc.getNumberOfPages();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                if (text != null && !text.trim().isEmpty()) {
                    out.add(new SpecChunk(product, specName, pdf.getName(), p, text));
                }
            }
            log.info("Loaded {} pages from {} ({})", pages, pdf.getName(), product);
        } catch (Exception e) {
            log.warn("Failed to load {}: {}", pdf.getAbsolutePath(), e.getMessage());
        }
    }

    private static String getString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString().trim() : null;
    }

    private static Map<String, Object> loadSpecConfig() {
        try (InputStream is = SpecLoader.class.getClassLoader().getResourceAsStream(SPEC_TAGS_RESOURCE)) {
            if (is == null) return null;
            Object o = new Yaml().load(is);
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (Exception e) {
            log.debug("Could not load {}: {}", SPEC_TAGS_RESOURCE, e.getMessage());
            return null;
        }
    }
}
