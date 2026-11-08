package org.tornotron.echno_ingestfixtures.violating.modules.documentingestion.extraction;

import org.tornotron.echno_ingestfixtures.violating.common.service.FileStorageService;

public class ExtractorThatReadsAFile {
    public int read(FileStorageService storage) {
        return storage.read("k").length;
    }
}
