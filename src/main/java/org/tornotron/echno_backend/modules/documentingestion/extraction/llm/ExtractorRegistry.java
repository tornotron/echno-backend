package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;

/**
 * Every extractor bean by key, with the operator's switch per key (design section 6.1), on by default
 * once the module is on.
 */
@Component
public class ExtractorRegistry {

    static final String SWITCH = "echno.modules." + DocumentIngestionModule.ID + ".extractors.%s.enabled";

    private final Map<String, Extractor> byKey = new LinkedHashMap<>();
    private final Environment environment;

    public ExtractorRegistry(List<Extractor> extractors, Environment environment) {
        this.environment = environment;
        for (Extractor e : extractors) {
            if (byKey.putIfAbsent(e.key(), e) != null) {
                throw new IllegalStateException("Two extractors share the key " + e.key());
            }
        }
    }

    public Optional<Extractor> find(String key) {
        return Optional.ofNullable(byKey.get(key)).filter(e -> isEnabled(e.key()));
    }

    public boolean isEnabled(String key) {
        return !"false".equalsIgnoreCase(environment.getProperty(SWITCH.formatted(key), "true").strip());
    }

    /** Enabled extractors that read whole documents, queued when a document's text arrives. */
    public List<Extractor> documentExtractors() {
        return byKey.values().stream().filter(e -> e.segmentClasses().isEmpty()).filter(e -> isEnabled(e.key())).toList();
    }

    /** Enabled extractors that read a confirmed segment of this class. */
    public List<Extractor> segmentExtractors(DocClass docClass) {
        return byKey.values().stream().filter(e -> e.segmentClasses().contains(docClass))
                .filter(e -> isEnabled(e.key())).toList();
    }
}
