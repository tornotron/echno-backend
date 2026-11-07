package org.tornotron.echno_backend.modules.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.vision.domain.CvModel;
import org.tornotron.echno_backend.modules.vision.domain.CvModelClass;
import org.tornotron.echno_backend.modules.vision.domain.CvModelFamily;
import org.tornotron.echno_backend.modules.vision.domain.CvModelTask;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersion;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;
import org.tornotron.echno_backend.modules.vision.repository.CvModelRepository;
import org.tornotron.echno_backend.modules.vision.repository.CvModelVersionRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The registry tables on the real migration and the real engine: the entities map onto them, the
 * JSON columns round-trip, and the database refuses a second active version of a model, which is
 * the guarantee behind the activation swap.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CvModelRegistryMappingIT extends AbstractIntegrationTest {

    @Autowired
    private CvModelRepository models;

    @Autowired
    private CvModelVersionRepository versions;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void aVersionRoundTripsItsClassesAndMetrics() {
        CvModel model = models.saveAndFlush(model("ppe-rt"));
        CvModelVersion version = version(model, "0.1.0", CvModelVersionStatus.DRAFT);
        version.setMetrics(Map.of("map50", 0.71));
        versions.saveAndFlush(version);
        entityManager.clear();

        CvModelVersion read = versions.findByModelAndVersion(model.getId(), "0.1.0").orElseThrow();

        assertThat(read.getClasses()).singleElement().satisfies(c -> {
            assertThat(c.key()).isEqualTo("ppe_helmet");
            assertThat(c.shape()).isEqualTo("BOX");
            assertThat(c.threshold()).isEqualByComparingTo("0.35");
        });
        assertThat(read.getMetrics()).containsEntry("map50", 0.71);
        assertThat(models.findByModelKey("ppe-rt")).isPresent();
    }

    @Test
    void theDatabaseRefusesASecondActiveVersionOfAModel() {
        CvModel model = models.saveAndFlush(model("ppe-unique"));
        versions.saveAndFlush(version(model, "0.1.0", CvModelVersionStatus.ACTIVE));
        versions.saveAndFlush(version(model, "0.2.0", CvModelVersionStatus.DRAFT));

        assertThatThrownBy(() -> versions.saveAndFlush(version(model, "0.3.0", CvModelVersionStatus.ACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theCatalogueReadsWithNoTenant() {
        TenantContext.clear();
        CvModel model = models.saveAndFlush(model("ppe-global"));
        entityManager.clear();

        assertThat(models.findById(model.getId())).isPresent();
    }

    private static CvModel model(String key) {
        CvModel model = new CvModel();
        model.setModelKey(key);
        model.setName("Personal protective equipment");
        model.setTask(CvModelTask.DETECT);
        model.setFamily(CvModelFamily.SAFETY);
        return model;
    }

    private static CvModelVersion version(CvModel model, String version, CvModelVersionStatus status) {
        CvModelVersion row = new CvModelVersion();
        row.setModelId(model.getId());
        row.setVersion(version);
        row.setStatus(status);
        row.setClasses(List.of(new CvModelClass("ppe_helmet", "BOX", new BigDecimal("0.35"))));
        row.setLicence("Apache-2.0");
        row.setBaseWeights("rf-detr-nano COCO (Apache-2.0)");
        row.setWeightsUri("s3://echno-models/ppe/" + version + "/model.onnx");
        row.setWeightsSha256("a".repeat(64));
        row.setRuntime("onnxruntime");
        row.setInputSize(384);
        return row;
    }
}
