package org.tornotron.echno_backend.modules.vision;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Settings for the computer vision module, under {@code echno.vision}. */
@Data
@Component
@VisionModuleEnabled
@ConfigurationProperties(prefix = "echno.vision")
public class VisionProperties {

    /**
     * SPDX ids a model version's weights may carry to be activated. Checkpoint families under a
     * licence that binds the weights stay out by not being listed.
     */
    private List<String> allowedLicences = List.of("Apache-2.0", "BSD-3-Clause", "MIT");

    /** Whether a producer may post against a DRAFT version. Staging only. */
    private boolean acceptDraftModels = false;

    /** At most this many detections of one job become observations, best score first. */
    private int maxObservationsPerImage = 50;
}
