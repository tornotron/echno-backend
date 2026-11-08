package org.tornotron.echno_backend.modules.vision.intake;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.inspections.api.IntakeCandidate;
import org.tornotron.echno_backend.modules.inspections.api.IntakeRejectedException;
import org.tornotron.echno_backend.modules.inspections.api.ObservationIntakeCheck;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.VisionProperties;
import org.tornotron.echno_backend.modules.vision.domain.CvModel;
import org.tornotron.echno_backend.modules.vision.domain.CvModelClass;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersion;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;
import org.tornotron.echno_backend.modules.vision.repository.CvModelRepository;
import org.tornotron.echno_backend.modules.vision.repository.CvModelVersionRepository;
import org.tornotron.echno_backend.modules.vision.taxonomy.CvClassTaxonomy;

/**
 * Holds a model's findings to the registry: the model and version must be known and active, and
 * each region must name a class that version outputs, in that class's shape.
 *
 * <p>It runs whether or not the posting organization is entitled to the module. The registry is
 * global, and a producer should not get past it by posting to an organization without the add-on.
 * A finding that is not a model's output (a drone's capture with no score) is left alone.
 *
 * <p>A reviewer choosing a class through a MODIFY is held to the taxonomy instead, since a reviewer
 * may name a class the model never outputs: that is how a missed object is recorded.
 */
@Component
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionIntakeCheck implements ObservationIntakeCheck {

    static final String UNKNOWN_MODEL_VERSION = "UNKNOWN_MODEL_VERSION";
    static final String MODEL_NOT_ACTIVE = "MODEL_NOT_ACTIVE";
    static final String UNKNOWN_CLASS = "UNKNOWN_CLASS";
    static final String SHAPE_MISMATCH = "SHAPE_MISMATCH";
    static final String VALIDATION = "VALIDATION";

    private final CvModelRepository models;
    private final CvModelVersionRepository versions;
    private final CvClassTaxonomy taxonomy;
    private final VisionProperties properties;

    @Override
    public void check(IntakeCandidate candidate) {
        if (!candidate.isModelOutput()) {
            return;
        }
        CvModelVersion version = requireUsableVersion(candidate);
        List<IntakeCandidate.Region> regions = candidate.regions();
        for (int i = 0; i < regions.size(); i++) {
            checkRegion(version, regions.get(i), "regions[" + i + "].classKey");
        }
        if (candidate.category() != null && !candidate.category().isBlank() && !regions.isEmpty()
                && !candidate.category().equals(regions.getFirst().classKey())) {
            throw new IntakeRejectedException(VALIDATION, "category", "category '" + candidate.category()
                    + "' must equal the class of the first region, '" + regions.getFirst().classKey() + "'");
        }
    }

    @Override
    public void checkReviewerClass(String where, String classKey, String shape) {
        String expected = taxonomy.shapeOf(classKey).orElseThrow(() -> new IntakeRejectedException(UNKNOWN_CLASS,
                where + ".classKey", where + ": class '" + classKey + "' is not in the taxonomy"));
        if (!expected.equals(shape)) {
            throw new IntakeRejectedException(SHAPE_MISMATCH, where + ".shape",
                    where + ": class '" + classKey + "' is drawn as " + expected + ", not " + shape);
        }
    }

    private CvModelVersion requireUsableVersion(IntakeCandidate candidate) {
        String modelName = candidate.modelName();
        String modelVersion = candidate.modelVersion();
        if (modelName == null || modelName.isBlank() || modelVersion == null || modelVersion.isBlank()) {
            throw new IntakeRejectedException(UNKNOWN_MODEL_VERSION, "modelVersion",
                    "A model's finding must name modelName and modelVersion");
        }
        CvModel model = models.findByModelKey(modelName).orElseThrow(() -> new IntakeRejectedException(
                UNKNOWN_MODEL_VERSION, "modelName", "No model with key '" + modelName + "' is registered"));
        CvModelVersion version = versions.findByModelAndVersion(model.getId(), modelVersion)
                .orElseThrow(() -> new IntakeRejectedException(UNKNOWN_MODEL_VERSION, "modelVersion",
                        "No version " + modelVersion + " of model '" + modelName + "' is registered"));
        boolean usable = version.getStatus() == CvModelVersionStatus.ACTIVE
                || (version.getStatus() == CvModelVersionStatus.DRAFT && properties.isAcceptDraftModels());
        if (!usable) {
            throw new IntakeRejectedException(MODEL_NOT_ACTIVE, "modelVersion",
                    "Version " + modelVersion + " of model '" + modelName + "' is " + version.getStatus());
        }
        return version;
    }

    private static void checkRegion(CvModelVersion version, IntakeCandidate.Region region, String field) {
        CvModelClass modelClass = version.getClasses().stream()
                .filter(c -> c.key().equals(region.classKey()))
                .findFirst()
                .orElseThrow(() -> new IntakeRejectedException(UNKNOWN_CLASS, field, "Version " + version.getVersion()
                        + " does not output class '" + region.classKey() + "'"));
        if (!modelClass.shape().equals(region.shape())) {
            throw new IntakeRejectedException(SHAPE_MISMATCH, field, "Class '" + region.classKey() + "' is drawn as "
                    + modelClass.shape() + ", not " + region.shape());
        }
    }
}
