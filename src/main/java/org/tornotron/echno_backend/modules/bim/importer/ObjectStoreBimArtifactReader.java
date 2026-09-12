package org.tornotron.echno_backend.modules.bim.importer;

import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import software.amazon.awssdk.core.exception.SdkException;

@Component
@RequiredArgsConstructor
public class ObjectStoreBimArtifactReader implements BimArtifactReader {

    private final FileStorageService fileStorageService;

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return fileStorageService.openObject(key);
        } catch (SdkException e) {
            throw new IOException("Could not read " + key + " from the object store: " + e.getMessage(), e);
        }
    }
}
