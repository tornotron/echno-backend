package org.tornotron.echno_backend.modules.vision.jobs;

import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import software.amazon.awssdk.core.exception.SdkException;

@Component
@VisionModuleEnabled
@RequiredArgsConstructor
public class ObjectStoreVisionArtifactReader implements VisionArtifactReader {

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
