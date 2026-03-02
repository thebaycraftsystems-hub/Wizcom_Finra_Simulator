package com.wizcom.fix.simulator.specs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

/**
 * Java-only spec retrieval (no Python, no GPU). Runs on Linux.
 * Builds a Lucene full-text index from FINRA spec PDFs and answers queries.
 */
public class SpecRetrieval {
    private static final Logger log = LoggerFactory.getLogger(SpecRetrieval.class);
    private static final String INDEX_SUBDIR = ".lucene-index";

    private final File baseDir;
    private final SpecLoader loader;
    private SpecIndex index;
    private boolean built;

    public SpecRetrieval(File baseDir) {
        this.baseDir = baseDir != null ? baseDir : new File(System.getProperty("user.dir", "."));
        this.loader = new SpecLoader(this.baseDir);
        this.index = new SpecIndex(Paths.get(this.baseDir.getAbsolutePath(), "docs", "Specification", INDEX_SUBDIR));
        this.built = false;
    }

    /**
     * Ensure index exists; if not, load PDFs and build it.
     */
    public void ensureIndex() {
        if (built && index.indexExists()) return;
        if (index.indexExists()) {
            built = true;
            return;
        }
        List<SpecChunk> chunks = loader.loadAll();
        if (chunks.isEmpty()) {
            log.debug("No spec chunks loaded; index not built");
            return;
        }
        try {
            index.build(chunks);
            built = true;
        } catch (Exception e) {
            log.warn("Could not build spec index: {}", e.getMessage());
        }
    }

    /**
     * Query specs. If index does not exist, builds it first from PDFs.
     *
     * @param queryString  free-text query (e.g. "required fields TradeCaptureReport")
     * @param productFilter SP, CA, or TS to limit to one spec; null for all
     * @param topN         max results
     * @return list of matching chunks (product, specName, page, text)
     */
    public List<SpecChunk> query(String queryString, String productFilter, int topN) {
        ensureIndex();
        if (!index.indexExists()) return java.util.Collections.emptyList();
        try {
            return index.query(queryString, productFilter, topN);
        } catch (Exception e) {
            log.debug("Spec query failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** True if the Lucene index exists (no build). Use for lookup without triggering PDF load. */
    public boolean indexExists() {
        return index.indexExists();
    }

    /**
     * Query only if index already exists (does not build). Use when you need a page number for error messages.
     */
    public List<SpecChunk> queryIfIndexExists(String queryString, String productFilter, int topN) {
        if (!index.indexExists()) return java.util.Collections.emptyList();
        try {
            return index.query(queryString, productFilter, topN);
        } catch (Exception e) {
            log.debug("Spec query failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** Directory where the Lucene index is stored (for diagnostics). */
    public String getIndexPath() {
        return index.getIndexPath().toAbsolutePath().toString();
    }

    /**
     * Rebuild the index from PDFs (e.g. after updating spec files). Call when you want a full reinstall.
     */
    public void rebuildIndex() {
        built = false;
        List<SpecChunk> chunks = loader.loadAll();
        if (chunks.isEmpty()) return;
        try {
            index.build(chunks);
            built = true;
        } catch (Exception e) {
            log.warn("Could not rebuild spec index: {}", e.getMessage());
        }
    }
}
