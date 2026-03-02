package com.wizcom.fix.simulator.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads tag number -> field name from FIX 4.4 specification XML.
 * Tries docs/FIX44.xml first, then classpath FIX44.xml. All tags come from here.
 */
public final class FIX44TagLoader {
    private static final Logger log = LoggerFactory.getLogger(FIX44TagLoader.class);
    private static final String DOCS_PATH = "docs" + File.separator + "FIX44.xml";
    private static final String CLASSPATH_RESOURCE = "FIX44.xml";

    private final Map<Integer, String> tagToName = new HashMap<>();

    public FIX44TagLoader() {
        load();
    }

    /**
     * Optional base dir (e.g. user.dir). If null, uses user.dir for docs path.
     */
    public FIX44TagLoader(File baseDir) {
        load(baseDir);
    }

    private void load() {
        load(new File(System.getProperty("user.dir", ".")));
    }

    private void load(File baseDir) {
        File docsFile = baseDir != null ? new File(baseDir, DOCS_PATH.replace("/", File.separator)) : null;
        if (docsFile != null && docsFile.isFile()) {
            if (parseFile(docsFile)) return;
        }
        try (InputStream is = FIX44TagLoader.class.getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (is != null && parseStream(is)) return;
        } catch (Exception e) {
            log.debug("Could not load {} from classpath: {}", CLASSPATH_RESOURCE, e.getMessage());
        }
        log.warn("No FIX44.xml loaded; tag names will fall back to TagNN");
    }

    private boolean parseFile(File file) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            return parseDocument(doc);
        } catch (Exception e) {
            log.warn("Could not parse {}: {}", file.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    private boolean parseStream(InputStream is) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            Document doc = f.newDocumentBuilder().parse(is);
            return parseDocument(doc);
        } catch (Exception e) {
            log.debug("Could not parse FIX44 from stream: {}", e.getMessage());
            return false;
        }
    }

    private boolean parseDocument(Document doc) {
        NodeList fields = doc.getElementsByTagName("field");
        int count = 0;
        for (int i = 0; i < fields.getLength(); i++) {
            if (!(fields.item(i) instanceof Element)) continue;
            Element el = (Element) fields.item(i);
            String num = el.getAttribute("number");
            String name = el.getAttribute("name");
            if (num == null || name == null || num.isEmpty() || name.isEmpty()) continue;
            try {
                int tag = Integer.parseInt(num.trim());
                tagToName.put(tag, name.trim());
                count++;
            } catch (NumberFormatException ignored) { }
        }
        if (count > 0) {
            log.info("FIX44 tag map loaded: {} tags from FIX44.xml", count);
            return true;
        }
        return false;
    }

    public String getFieldName(int tag) {
        return tagToName.getOrDefault(tag, "Tag" + tag);
    }

    public Map<Integer, String> getTagToName() {
        return new HashMap<>(tagToName);
    }
}
