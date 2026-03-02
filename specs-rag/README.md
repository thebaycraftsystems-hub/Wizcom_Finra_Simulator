# Specs RAG – now in Java

Spec retrieval is implemented in **Java** (no Python, no GPU) so the FINRA simulator runs on Linux headless servers.

- **Build index:** from project root run  
  `java -cp target/fix-simulator-5.4.0-jar-with-dependencies.jar com.wizcom.fix.simulator.specs.SpecRetrievalMain build`
- **Query:** same jar, args `query "your query" [SP|CA|TS] [topN]`
- **Docs:** see `docs/Specification/README_SPECS_RAG.md`

Python scripts that were here have been removed.
