package org.tornotron.echno_ingestfixtures.violating.modules.documentingestion.extraction.llm;

import org.tornotron.echno_ingestfixtures.violating.project.ProjectService;

/** A sub-package of extraction is held by the same rule: an LLM reader writes candidates only. */
public class ChunkReaderThatWritesAProject {
    public void read(ProjectService projects) {
        projects.create("From a chunk");
    }
}
