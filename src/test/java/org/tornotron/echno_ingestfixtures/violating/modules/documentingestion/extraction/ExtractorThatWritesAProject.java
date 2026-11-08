package org.tornotron.echno_ingestfixtures.violating.modules.documentingestion.extraction;

import org.tornotron.echno_ingestfixtures.violating.project.ProjectService;

public class ExtractorThatWritesAProject {
    public void extract(ProjectService projects) {
        projects.create("From a tender");
    }
}
