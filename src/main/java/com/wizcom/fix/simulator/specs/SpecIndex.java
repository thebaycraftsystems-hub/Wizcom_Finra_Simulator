package com.wizcom.fix.simulator.specs;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lucene full-text index over spec chunks (Java-only, no GPU, runs on Linux).
 */
public class SpecIndex {
    private static final Logger log = LoggerFactory.getLogger(SpecIndex.class);
    private static final String FIELD_PRODUCT = "product";
    private static final String FIELD_SPEC = "specName";
    private static final String FIELD_SOURCE = "source";
    private static final String FIELD_PAGE = "page";
    private static final String FIELD_TEXT = "text";

    private final Path indexDir;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    public SpecIndex(Path indexDir) {
        this.indexDir = indexDir;
    }

    /**
     * Build or rebuild the index from the given chunks. Call after loading PDFs with SpecLoader.
     */
    public void build(List<SpecChunk> chunks) throws IOException {
        Files.createDirectories(indexDir);
        try (Directory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            for (SpecChunk c : chunks) {
                Document doc = new Document();
                doc.add(new StringField(FIELD_PRODUCT, c.getProduct(), Field.Store.YES));
                doc.add(new StringField(FIELD_SPEC, c.getSpecName(), Field.Store.YES));
                doc.add(new StringField(FIELD_SOURCE, c.getSource(), Field.Store.YES));
                doc.add(new StoredField(FIELD_PAGE, c.getPage()));
                doc.add(new TextField(FIELD_TEXT, c.getText(), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
            log.info("Spec index built: {} chunks in {}", chunks.size(), indexDir);
        }
    }

    /**
     * Query the index. Returns up to topN chunks, optionally filtered by product.
     */
    public List<SpecChunk> query(String queryString, String productFilter, int topN) throws IOException {
        List<SpecChunk> out = new ArrayList<>();
        if (!Files.isDirectory(indexDir)) return out;
        try (Directory dir = FSDirectory.open(indexDir);
             IndexReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            String s = queryString.trim();
            if (s.isEmpty()) {
                TopDocs top = searcher.search(new MatchAllDocsQuery(), topN);
                for (ScoreDoc sd : top.scoreDocs) {
                    Document d = searcher.doc(sd.doc);
                    out.add(toChunk(d));
                }
                return out;
            }
            String expanded = s.replaceAll("([a-z])([A-Z])", "$1 $2");
            QueryParser parser = new QueryParser(FIELD_TEXT, analyzer);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            Query query;
            try {
                query = parser.parse(QueryParser.escape(expanded));
            } catch (Exception e) {
                query = new MatchAllDocsQuery();
            }
            BooleanQuery.Builder bq = new BooleanQuery.Builder();
            bq.add(query, BooleanClause.Occur.MUST);
            if (productFilter != null && !productFilter.isEmpty()) {
                bq.add(new TermQuery(new Term(FIELD_PRODUCT, productFilter)), BooleanClause.Occur.FILTER);
            }
            TopDocs top = searcher.search(bq.build(), topN);
            for (ScoreDoc sd : top.scoreDocs) {
                out.add(toChunk(searcher.doc(sd.doc)));
            }
        } catch (Exception e) {
            log.debug("Spec query failed: {}", e.getMessage());
        }
        return out;
    }

    private static SpecChunk toChunk(Document d) {
        return new SpecChunk(
            d.get(FIELD_PRODUCT),
            d.get(FIELD_SPEC),
            d.get(FIELD_SOURCE),
            d.getField(FIELD_PAGE).numericValue().intValue(),
            d.get(FIELD_TEXT)
        );
    }

    public Path getIndexPath() { return indexDir; }

    /** Check if index exists without throwing (Java/Lucene 8). */
    public boolean indexExists() {
        try {
            if (!Files.isDirectory(indexDir)) return false;
            try (Directory dir = FSDirectory.open(indexDir);
                 IndexReader r = DirectoryReader.open(dir)) {
                return r != null;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
