package org.tornotron.echno_backend.modules.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.vision.domain.CvModelFamily;
import org.tornotron.echno_backend.modules.vision.domain.CvModelTask;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;
import org.tornotron.echno_backend.modules.vision.dto.ChangeCvModelVersionStatusRequest;
import org.tornotron.echno_backend.modules.vision.dto.CreateCvModelRequest;
import org.tornotron.echno_backend.modules.vision.dto.CvModelClassDto;
import org.tornotron.echno_backend.modules.vision.dto.CvModelDto;
import org.tornotron.echno_backend.modules.vision.dto.CvModelVersionDto;
import org.tornotron.echno_backend.modules.vision.dto.RegisterCvModelVersionRequest;
import org.tornotron.echno_backend.modules.vision.service.CvModelConflictException;
import org.tornotron.echno_backend.modules.vision.service.CvModelRegistryService;
import org.tornotron.echno_backend.modules.vision.taxonomy.CvClassTaxonomy;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The registry on the real migration: a family and a version land DRAFT, activating swaps the active
 * version in one transaction, and activation refuses a licence off the allowlist and a version with
 * nothing to read. Retired is terminal.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = VisionModuleEnabled.PROPERTY + "=true")
@EnableConfigurationProperties
@Import({CvModelRegistryService.class, CvClassTaxonomy.class, VisionProperties.class, UserContextService.class,
        com.fasterxml.jackson.databind.ObjectMapper.class})
class CvModelRegistryServiceIT extends AbstractIntegrationTest {

    @Autowired
    private CvModelRegistryService service;

    private final String key = "ppe-" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    void aFamilyAndAVersionLandDraftAndReadBack() {
        service.createModel(family(key));

        CvModelVersionDto version = service.registerVersion(key, version("0.1.0", "Apache-2.0"));

        assertThat(version.status()).isEqualTo(CvModelVersionStatus.DRAFT);
        assertThat(service.getVersion(key, "0.1.0").classes()).extracting(CvModelClassDto::key)
                .containsExactly("ppe_helmet");
        CvModelDto listed = service.listModels().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
        assertThat(listed.versions()).extracting(CvModelVersionDto::version).containsExactly("0.1.0");
    }

    @Test
    void activatingRetiresThePreviousActiveVersion() {
        service.createModel(family(key));
        service.registerVersion(key, version("0.1.0", "Apache-2.0"));
        service.registerVersion(key, version("0.2.0", "MIT"));

        service.changeStatus(key, "0.1.0", to(CvModelVersionStatus.ACTIVE));
        CvModelVersionDto second = service.changeStatus(key, "0.2.0", to(CvModelVersionStatus.ACTIVE));

        assertThat(second.status()).isEqualTo(CvModelVersionStatus.ACTIVE);
        assertThat(second.activatedAt()).isNotNull();
        CvModelVersionDto first = service.getVersion(key, "0.1.0");
        assertThat(first.status()).isEqualTo(CvModelVersionStatus.RETIRED);
        assertThat(first.retiredAt()).isNotNull();
    }

    @Test
    void aLicenceOffTheAllowlistCannotBeActivated() {
        service.createModel(family(key));
        service.registerVersion(key, version("0.1.0", "PML-1.0"));

        assertThatThrownBy(() -> service.changeStatus(key, "0.1.0", to(CvModelVersionStatus.ACTIVE)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("PML-1.0");
        assertThat(service.getVersion(key, "0.1.0").status()).isEqualTo(CvModelVersionStatus.DRAFT);
    }

    @Test
    void aVersionWithNothingToReadCannotBeActivated() {
        service.createModel(family(key));
        service.registerVersion(key, new RegisterCvModelVersionRequest("0.1.0",
                List.of(helmet()), "Apache-2.0", "rf-detr-nano COCO (Apache-2.0)", "s3://echno-models/ppe/0.1.0/model.onnx",
                "a".repeat(64), "onnxruntime", 384, null, null, null, "s3://echno-models/runs/x/eval/report.md"));
        service.registerVersion(key, new RegisterCvModelVersionRequest("0.2.0",
                List.of(helmet()), "Apache-2.0", "rf-detr-nano COCO (Apache-2.0)", "s3://echno-models/ppe/0.2.0/model.onnx",
                "a".repeat(64), "onnxruntime", 384, null, null, Map.of("map50", 0.7), null));

        assertThatThrownBy(() -> service.changeStatus(key, "0.1.0", to(CvModelVersionStatus.ACTIVE)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("metrics");
        assertThatThrownBy(() -> service.changeStatus(key, "0.2.0", to(CvModelVersionStatus.ACTIVE)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("report");
    }

    @Test
    void retiredIsTerminal() {
        service.createModel(family(key));
        service.registerVersion(key, version("0.1.0", "Apache-2.0"));
        service.changeStatus(key, "0.1.0", to(CvModelVersionStatus.RETIRED));

        assertThatThrownBy(() -> service.changeStatus(key, "0.1.0", to(CvModelVersionStatus.ACTIVE)))
                .isInstanceOf(CvModelConflictException.class);
    }

    @Test
    void aDraftIsNotATarget() {
        service.createModel(family(key));
        service.registerVersion(key, version("0.1.0", "Apache-2.0"));

        assertThatThrownBy(() -> service.changeStatus(key, "0.1.0", to(CvModelVersionStatus.DRAFT)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aDuplicateIsAConflictAndAnUnknownNameIsNotFound() {
        service.createModel(family(key));
        service.registerVersion(key, version("0.1.0", "Apache-2.0"));

        assertThatThrownBy(() -> service.createModel(family(key))).isInstanceOf(CvModelConflictException.class);
        assertThatThrownBy(() -> service.registerVersion(key, version("0.1.0", "Apache-2.0")))
                .isInstanceOf(CvModelConflictException.class);
        assertThatThrownBy(() -> service.getVersion("no-such-model", "0.1.0"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getVersion(key, "9.9.9")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aVersionsClassesAreASubsetOfTheTaxonomyInTheRightShape() {
        service.createModel(family(key));

        assertThatThrownBy(() -> service.registerVersion(key, classes(new CvModelClassDto("not_a_class", "BOX", BigDecimal.ONE))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("taxonomy");
        assertThatThrownBy(() -> service.registerVersion(key, classes(new CvModelClassDto("ppe_helmet", "POLYGON", BigDecimal.ONE))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("drawn as BOX");
        assertThatThrownBy(() -> service.registerVersion(key, classes(new CvModelClassDto("crack", "POLYGON", BigDecimal.ONE))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("DETECT model");
        assertThatThrownBy(() -> service.registerVersion(key, classes(helmet(), helmet())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("twice");
    }

    private static CreateCvModelRequest family(String key) {
        return new CreateCvModelRequest(key, "Personal protective equipment", CvModelTask.DETECT, CvModelFamily.SAFETY);
    }

    private static CvModelClassDto helmet() {
        return new CvModelClassDto("ppe_helmet", "BOX", new BigDecimal("0.35"));
    }

    private static RegisterCvModelVersionRequest version(String version, String licence) {
        return new RegisterCvModelVersionRequest(version, List.of(helmet()), licence,
                "rf-detr-nano COCO (Apache-2.0)", "s3://echno-models/ppe/" + version + "/model.onnx", "a".repeat(64),
                "onnxruntime", 384, "v0.0-synthetic", "s3://echno-models/runs/x", Map.of("map50", 0.7),
                "s3://echno-models/runs/x/eval/report.md");
    }

    private static RegisterCvModelVersionRequest classes(CvModelClassDto... classes) {
        return new RegisterCvModelVersionRequest("1.0.0", List.of(classes), "Apache-2.0",
                "rf-detr-nano COCO (Apache-2.0)", "s3://echno-models/ppe/1.0.0/model.onnx", "a".repeat(64),
                "onnxruntime", 384, null, null, null, null);
    }

    private static ChangeCvModelVersionStatusRequest to(CvModelVersionStatus status) {
        return new ChangeCvModelVersionStatusRequest(status, "test");
    }
}
