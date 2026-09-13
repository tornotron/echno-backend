package org.tornotron.echno_backend.modules.inspections.dataset;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the consented evidence export (#791). The bucket and prefix come from
 * configuration because the store differs per environment: a MinIO bucket on the IITM staging,
 * a Spaces bucket in production. The export never writes under the dataset's immutable
 * {@code raw/} prefix; that is the anonymisation tooling's to fill.
 */
@Data
@Component
@ConfigurationProperties(prefix = "echno.dataset-export")
public class DatasetExportProperties {

    /** Whether the scheduled sweep runs at all. The on-demand endpoint is independent of it. */
    private boolean enabled = false;

    /** The dataset bucket, separate from the attachment bucket. */
    private String bucket = "echno-datasets";

    /** The dataset prefix inside the bucket; runs land under {@code <prefix>/export/<runKey>/}. */
    private String prefix = "construction-images";

    private String cron = "0 0 3 * * SUN";

    private String zone = "UTC";

    /**
     * Objects copied per run and organization. The export is idempotent per object, so a
     * backlog larger than this drains across successive runs rather than holding one
     * transaction open for the whole corpus.
     */
    private int maxObjectsPerRun = 500;
}
