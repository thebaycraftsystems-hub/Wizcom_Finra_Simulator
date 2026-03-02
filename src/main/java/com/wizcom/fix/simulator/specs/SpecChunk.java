package com.wizcom.fix.simulator.specs;

/**
 * One searchable chunk of a FINRA spec (product, spec name, page, text).
 * Used by Java-only spec retrieval (no GPU, runs on Linux).
 */
public class SpecChunk {
    private final String product;
    private final String specName;
    private final String source;
    private final int page;
    private final String text;

    public SpecChunk(String product, String specName, String source, int page, String text) {
        this.product = product != null ? product : "";
        this.specName = specName != null ? specName : "";
        this.source = source != null ? source : "";
        this.page = page;
        this.text = text != null ? text : "";
    }

    public String getProduct() { return product; }
    public String getSpecName() { return specName; }
    public String getSource() { return source; }
    public int getPage() { return page; }
    public String getText() { return text; }
}
