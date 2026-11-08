package org.tornotron.echno_backend.modules.vision;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.tornotron.echno_backend.modules.inspections.api.IntakeCandidate;
import org.tornotron.echno_backend.modules.inspections.api.IntakeRejectedException;
import org.tornotron.echno_backend.modules.vision.domain.CvModelFamily;
import org.tornotron.echno_backend.modules.vision.domain.CvModelTask;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;
import org.tornotron.echno_backend.modules.vision.dto.ChangeCvModelVersionStatusRequest;
import org.tornotron.echno_backend.modules.vision.dto.CreateCvModelRequest;
import org.tornotron.echno_backend.modules.vision.dto.CvModelClassDto;
import org.tornotron.echno_backend.modules.vision.dto.RegisterCvModelVersionRequest;
import org.tornotron.echno_backend.modules.vision.intake.VisionIntakeCheck;
import org.tornotron.echno_backend.modules.vision.service.CvModelRegistryService;
import org.tornotron.echno_backend.modules.vision.taxonomy.CvClassTaxonomy;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The check against a real registry: a version registered with {@code ppe_helmet} accepts that class
 * and refuses {@code crack}, and each refusal carries the code a producer can act on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = VisionModuleEnabled.PROPERTY + "=true")
@EnableConfigurationProperties
@Import({VisionIntakeCheck.class, CvModelRegistryService.class, CvClassTaxonomy.class, VisionProperties.class,
        UserContextService.class, com.fasterxml.jackson.databind.ObjectMapper.class})
class VisionIntakeCheckIT extends AbstractIntegrationTest {

    @Autowired
    private VisionIntakeCheck check;

    @Autowired
    private CvModelRegistryService registry;

    @Autowired
    private VisionProperties properties;

    private final String key = "ppe-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void registerAnActiveAndADraftVersion() {
        properties.setAcceptDraftModels(false);
        registry.createModel(new CreateCvModelRequest(key, "PPE", CvModelTask.DETECT, CvModelFamily.SAFETY));
        registry.registerVersion(key, version("0.1.0"));
        registry.changeStatus(key, "0.1.0", new ChangeCvModelVersionStatusRequest(CvModelVersionStatus.ACTIVE, null));
        registry.registerVersion(key, version("0.2.0"));
    }

    @Test
    void anActiveVersionAcceptsItsOwnClass() {
        assertThatCode(() -> check.check(detection(key, "0.1.0", null, "ppe_helmet", "BOX"))).doesNotThrowAnyException();
    }

    @Test
    void aClassTheVersionDoesNotOutputIsRefused() {
        assertRefused(detection(key, "0.1.0", null, "crack", "POLYGON"), "UNKNOWN_CLASS");
    }

    @Test
    void theWrongShapeForAClassIsRefused() {
        assertRefused(detection(key, "0.1.0", null, "ppe_helmet", "POLYGON"), "SHAPE_MISMATCH");
    }

    @Test
    void anUnknownModelOrVersionOrNoneAtAllIsRefused() {
        assertRefused(detection("no-such-model", "0.1.0", null, "ppe_helmet", "BOX"), "UNKNOWN_MODEL_VERSION");
        assertRefused(detection(key, "9.9.9", null, "ppe_helmet", "BOX"), "UNKNOWN_MODEL_VERSION");
        assertRefused(detection(null, null, null, "ppe_helmet", "BOX"), "UNKNOWN_MODEL_VERSION");
    }

    @Test
    void aDraftIsRefusedUnlessStagingSaysOtherwise() {
        assertRefused(detection(key, "0.2.0", null, "ppe_helmet", "BOX"), "MODEL_NOT_ACTIVE");

        properties.setAcceptDraftModels(true);

        assertThatCode(() -> check.check(detection(key, "0.2.0", null, "ppe_helmet", "BOX"))).doesNotThrowAnyException();
    }

    @Test
    void aRetiredVersionIsRefusedEvenWithDraftsAccepted() {
        properties.setAcceptDraftModels(true);
        registry.changeStatus(key, "0.1.0", new ChangeCvModelVersionStatusRequest(CvModelVersionStatus.RETIRED, null));

        assertRefused(detection(key, "0.1.0", null, "ppe_helmet", "BOX"), "MODEL_NOT_ACTIVE");
    }

    @Test
    void aCategoryMustBeTheClassOfTheFirstRegion() {
        assertThatCode(() -> check.check(detection(key, "0.1.0", "ppe_helmet", "ppe_helmet", "BOX")))
                .doesNotThrowAnyException();
        assertRefused(detection(key, "0.1.0", "safety", "ppe_helmet", "BOX"), "VALIDATION");
    }

    @Test
    void aFindingThatIsNotAModelsOutputIsLeftAlone() {
        IntakeCandidate fromADrone = new IntakeCandidate("DRONE", null, null, "crack",
                List.of(new IntakeCandidate.Region("anything", "BOX", null)));

        assertThatCode(() -> check.check(fromADrone)).doesNotThrowAnyException();
    }

    @Test
    void aScoreOnARegionMakesAFindingAModelsOutputWhateverItsSource() {
        IntakeCandidate scored = new IntakeCandidate("DRONE", null, null, null,
                List.of(new IntakeCandidate.Region("ppe_helmet", "BOX", new BigDecimal("0.8"))));

        assertRefused(scored, "UNKNOWN_MODEL_VERSION");
    }

    @Test
    void aReviewerMayNameAnyTaxonomyClassOfTheRightShape() {
        assertThatCode(() -> check.checkReviewerClass("changes.regions[0]", "vehicle", "BOX")).doesNotThrowAnyException();
        assertThatThrownBy(() -> check.checkReviewerClass("changes.regions[0]", "not_a_class", "BOX"))
                .isInstanceOf(IntakeRejectedException.class).hasFieldOrPropertyWithValue("code", "UNKNOWN_CLASS");
        assertThatThrownBy(() -> check.checkReviewerClass("changes.regions[0]", "crack", "BOX"))
                .isInstanceOf(IntakeRejectedException.class).hasFieldOrPropertyWithValue("code", "SHAPE_MISMATCH");
    }

    private void assertRefused(IntakeCandidate candidate, String code) {
        assertThatThrownBy(() -> check.check(candidate))
                .isInstanceOf(IntakeRejectedException.class)
                .hasFieldOrPropertyWithValue("code", code);
    }

    private static IntakeCandidate detection(String model, String version, String category, String classKey, String shape) {
        return new IntakeCandidate("AI", model, version, category,
                List.of(new IntakeCandidate.Region(classKey, shape, new BigDecimal("0.8"))));
    }

    private static RegisterCvModelVersionRequest version(String version) {
        return new RegisterCvModelVersionRequest(version,
                List.of(new CvModelClassDto("ppe_helmet", "BOX", new BigDecimal("0.35"))), "Apache-2.0",
                "rf-detr-nano COCO (Apache-2.0)", "s3://echno-models/ppe/" + version + "/model.onnx", "a".repeat(64),
                "onnxruntime", 384, null, null, Map.of("map50", 0.7), "s3://echno-models/runs/x/eval/report.md");
    }
}
