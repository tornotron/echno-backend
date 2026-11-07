package org.tornotron.echno_ingestfixtures.clean.modules.documentingestion.extraction;

import org.tornotron.echno_ingestfixtures.clean.common.service.FileStorageService;

public class Extractor {
    public int read(FileStorageService storage) {
        return storage.read("k").length;
    }
}
