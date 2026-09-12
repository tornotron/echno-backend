package org.tornotron.echno_backend.modules.bim.importer;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where the worker's outputs are read from. Production reads the object store; tests hand in
 * a map. Keys are full object keys as the import contract lays them out.
 */
public interface BimArtifactReader {

    InputStream open(String key) throws IOException;
}
