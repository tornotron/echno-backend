package org.tornotron.echno_backend.modules.vision.jobs;

import java.io.IOException;
import java.io.InputStream;

/** Where the worker's output is read from. Production reads the object store; tests hand in bytes. */
public interface VisionArtifactReader {

    InputStream open(String key) throws IOException;
}
