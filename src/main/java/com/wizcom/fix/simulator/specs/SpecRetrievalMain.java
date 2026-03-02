package com.wizcom.fix.simulator.specs;

import java.io.File;
import java.util.List;

/**
 * CLI to build spec index and query (Java-only, no GPU). For Linux:
 *   java -cp ... com.wizcom.fix.simulator.specs.SpecRetrievalMain build
 *   java -cp ... com.wizcom.fix.simulator.specs.SpecRetrievalMain query "required fields TradeCaptureReport"
 * Base dir defaults to user.dir; set -Dspec.base.dir=/path if needed.
 */
public class SpecRetrievalMain {
    public static void main(String[] args) {
        String base = System.getProperty("spec.base.dir", System.getProperty("user.dir", "."));
        File baseDir = new File(base);
        SpecRetrieval retrieval = new SpecRetrieval(baseDir);

        if (args.length == 0 || "build".equalsIgnoreCase(args[0])) {
            retrieval.rebuildIndex();
            System.out.println("Spec index built (Java, no GPU). Index under docs/Specification/.lucene-index");
            return;
        }
        if ("buildThenQuery".equalsIgnoreCase(args[0])) {
            retrieval.rebuildIndex();
            String q = args.length > 1 ? args[1] : "required fields TradeCaptureReport";
            List<SpecChunk> hits = retrieval.query(q, null, 3);
            System.out.println("Hits: " + hits.size());
            for (SpecChunk c : hits) System.out.println("  " + c.getProduct() + " p." + c.getPage());
            return;
        }
        if ("query".equalsIgnoreCase(args[0])) {
            String q = args.length > 1 ? args[1] : "required fields TradeCaptureReport";
            String product = (args.length > 2 && !"null".equalsIgnoreCase(args[2])) ? args[2] : null;
            int top = args.length > 3 ? Integer.parseInt(args[3]) : 5;
            retrieval.ensureIndex();
            List<SpecChunk> hits = retrieval.query(q, product, top);
            if (hits.isEmpty()) {
                System.out.println("No results. Index path: " + retrieval.getIndexPath());
                System.out.println("Run from project root: SpecRetrievalMain build");
                return;
            }
            for (int i = 0; i < hits.size(); i++) {
                SpecChunk c = hits.get(i);
                System.out.println("\n--- " + (i + 1) + " [" + c.getProduct() + " " + c.getSpecName() + " p." + c.getPage() + "] ---");
                String text = c.getText();
                System.out.println(text.length() > 1200 ? text.substring(0, 1200) + "..." : text);
            }
            return;
        }
        System.out.println("Usage: SpecRetrievalMain build | query \"your query\" [SP|CA|TS] [topN]");
    }
}
