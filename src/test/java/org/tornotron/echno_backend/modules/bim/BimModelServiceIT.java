package org.tornotron.echno_backend.modules.bim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimImportJobDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelDto;
import org.tornotron.echno_backend.modules.bim.dto.CreateBimModelRequest;
import org.tornotron.echno_backend.modules.bim.mapper.BimMapperImpl;
import org.tornotron.echno_backend.modules.bim.repository.BimModelVersionRepository;
import org.tornotron.echno_backend.modules.bim.service.BimModelService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The module's tables through the migration, the tenant boundary on every read, and the
 * queueing of a worker job with its state mirrored on the version.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BimModelService.class, BimMapperImpl.class, UserContextService.class, TenantEntityHelper.class})
class BimModelServiceIT extends AbstractIntegrationTest {

    @Autowired
    private BimModelService service;

    @Autowired
    private BimModelVersionRepository versions;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectAId;
    private Long projectBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Bim Org A");
            Organization orgB = persistOrganization("Bim Org B");
            projectAId = persistProject("Tower A", orgA).getId();
            projectBId = persistProject("Tower B", orgB).getId();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        disableOrgFilter();
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM bim_import_jobs WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM bim_elements WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM bim_model_versions WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM bim_models WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void aModelIsCreatedOnTheCallersProjectAndListedBack() {
        BimModelDto created = service.create(projectAId, new CreateBimModelRequest("Architecture", "ARC model"));

        assertThat(created.id()).isNotNull();
        assertThat(created.projectId()).isEqualTo(projectAId);
        assertThat(created.versions()).isEmpty();
        assertThat(service.listForProject(projectAId)).extracting(BimModelDto::name).containsExactly("Architecture");
        assertThat(service.get(created.id()).name()).isEqualTo("Architecture");
    }

    @Test
    void anotherTenantsProjectAndModelReadAsAbsent() {
        UUID foreign = asTenant(orgBId, () ->
                service.create(projectBId, new CreateBimModelRequest("Structure", null)).id());

        assertThatThrownBy(() -> service.create(projectBId, new CreateBimModelRequest("Architecture", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listForProject(projectBId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listElements(foreign, null, false, 0, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void queueingAnImportMirrorsTheVersionAndRefusesADuplicateOpenJob() {
        BimModelDto model = service.create(projectAId, new CreateBimModelRequest("MEP", null));
        UUID versionId = persistVersion(model.id());

        BimImportJobDto job = service.enqueueImport(model.id(), versionId);

        assertThat(job.status()).isEqualTo(BimImportJobStatus.QUEUED);
        assertThat(job.attempt()).isZero();
        assertThat(service.getVersion(model.id(), versionId).status()).isEqualTo(BimVersionStatus.QUEUED);
        assertThat(service.listJobs(model.id(), versionId)).extracting(BimImportJobDto::id).containsExactly(job.id());
        assertThat(service.getJob(job.id()).versionId()).isEqualTo(versionId);
        assertThatThrownBy(() -> service.enqueueImport(model.id(), versionId))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aStrangerCannotSeeTheJobOrTheVersion() {
        BimModelDto model = service.create(projectAId, new CreateBimModelRequest("Civil", null));
        UUID versionId = persistVersion(model.id());
        BimImportJobDto job = service.enqueueImport(model.id(), versionId);
        entityManager.flush();

        asTenant(orgBId, () -> {
            assertThatThrownBy(() -> service.getJob(job.id())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.getVersion(model.id(), versionId))
                    .isInstanceOf(ResourceNotFoundException.class);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------

    private UUID persistVersion(UUID modelId) {
        BimModelVersion v = new BimModelVersion();
        v.setOrganization(entityManager.getReference(Organization.class, orgAId));
        v.setModelId(modelId);
        v.setProjectId(projectAId);
        v.setVersionNumber(1);
        v.setSourceKey(BimStorageLayout.sourceKey(modelId, UUID.randomUUID()));
        v.setSourceFilename("tower-a.ifc");
        v.setSourceSizeBytes(1024L);
        return versions.save(v).getId();
    }

    private <T> T asTenant(Long orgId, java.util.function.Supplier<T> work) {
        Long previous = TenantContext.getCurrentOrgId();
        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgId);
        enableOrgFilter(orgId);
        try {
            return work.get();
        } finally {
            disableOrgFilter();
            TenantContext.setCurrentOrgId(previous);
            enableOrgFilter(previous);
        }
    }

    private Project persistProject(String name, Organization org) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(org);
        entityManager.persist(project);
        entityManager.flush();
        return project;
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}
