# FINRA Specs – Java RAG (no Python, no GPU)

The simulator uses **Java-only** spec retrieval so it runs on **Linux without GPU**.

## Build the index (once, or after updating PDFs)

From the **project root** (where `docs/Specification/` lives):

```bash
java -cp target/fix-simulator-5.4.0-jar-with-dependencies.jar \
  com.wizcom.fix.simulator.specs.SpecRetrievalMain build
```

Index is written under `docs/Specification/.lucene-index/`.

## Query from command line

```bash
java -cp target/fix-simulator-5.4.0-jar-with-dependencies.jar \
  com.wizcom.fix.simulator.specs.SpecRetrievalMain query "required fields TradeCaptureReport"

# Limit to one product and number of results
java -cp target/fix-simulator-5.4.0-jar-with-dependencies.jar \
  com.wizcom.fix.simulator.specs.SpecRetrievalMain query "tag 571" SP 5
```

## From Java code

```java
File baseDir = new File(System.getProperty("user.dir"));
SpecRetrieval retrieval = new SpecRetrieval(baseDir);
retrieval.ensureIndex();  // builds index if missing
List<SpecChunk> hits = retrieval.query("required fields", "SP", 5);
```

## Linux

- Run the same commands; use `/` in classpath: `target/fix-simulator-5.4.0-jar-with-dependencies.jar`.
- No GPU or Python required; PDF text is extracted with Apache PDFBox and search uses Apache Lucene.
