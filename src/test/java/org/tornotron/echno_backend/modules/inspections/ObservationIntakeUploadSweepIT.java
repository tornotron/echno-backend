package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.modules.inspections.api.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.api.RegionShape;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationIntakeUpload;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationRegion;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationIntakeUploadRepository;
import org.tornotron.echno_backend.modules.inspections.service.ObservationIntakeUploadService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * What the sweep deletes, on the real tables: an image no finding cites, once it is old enough. A
 * region and an {@code evidenceRefs} entry each keep one, a young one is left alone, and another
 * tenant's is not looked at.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ObservationIntakeUploadService.class, TenantEntityHelper.class, UserContextService.class})
class ObservationIntakeUploadSweepIT extends AbstractIntegrationTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime OLD = CUTOFF.minusDays(1);
    private static final LocalDateTime YOUNG = CUTOFF.plusDays(1);

    @Autowired
    private ObservationIntakeUploadService service;

    @Autowired
    private ObservationIntakeUploadRepository uploads;

    @MockitoBean
    private AttachmentService attachmentService;

    @MockitoBean
    private AttachmentMapper attachmentMapper;

    @PersistenceContext
    private EntityManager entityManager;

    private Organization org;
    private Organization otherOrg;
    private Long projectId;

    @BeforeEach
    void seed() {
        org = organization("Sweep Org A");
        otherOrg = organization("Sweep Org B");
        Project project = new Project();
        project.setProjectName("Tower S");
        project.setOrganization(org);
        entityManager.persist(project);
        entityManager.flush();
        projectId = project.getId();
        TenantContext.setCurrentOrgId(org.getId());
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", org.getId());
    }

    @AfterEach
    void clearTenant() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @Test
    void anOldImageNothingCitesIsDeleted() {
        Upload stale = upload(org, OLD);

        assertThat(service.deleteUnreferenced(CUTOFF, 100)).isEqualTo(1);

        verify(attachmentService).deleteAttachmentOf(ObservationEvidence.intakeUploadOwnerOf(stale.uploadId()), stale.attachmentId());
    }

    @Test
    void aRegionKeepsAnImage() {
        Upload cited = upload(org, OLD);
        drawRegionOn(cited.attachmentId());

        assertThat(service.deleteUnreferenced(CUTOFF, 100)).isZero();

        verify(attachmentService, never()).deleteAttachmentOf(any(), eq(cited.attachmentId()));
    }

    @Test
    void anEvidenceRefKeepsAnImage() {
        Upload cited = upload(org, OLD);
        Upload other = upload(org, OLD);
        Observation observation = observation();
        observation.setEvidenceRefs(List.of(Map.of("attachmentId", cited.attachmentId(), "frame", 7)));
        entityManager.flush();

        assertThat(service.deleteUnreferenced(CUTOFF, 100)).isEqualTo(1);

        verify(attachmentService, never()).deleteAttachmentOf(any(), eq(cited.attachmentId()));
        verify(attachmentService).deleteAttachmentOf(any(), eq(other.attachmentId()));
    }

    @Test
    void aYoungImageIsLeftAlone() {
        upload(org, YOUNG);

        assertThat(service.deleteUnreferenced(CUTOFF, 100)).isZero();

        verify(attachmentService, never()).deleteAttachmentOf(any(), any());
    }

    @Test
    void anotherTenantsImageIsNotLookedAt() {
        upload(otherOrg, OLD);

        assertThat(service.deleteUnreferenced(CUTOFF, 100)).isZero();

        verify(attachmentService, never()).deleteAttachmentOf(any(), any());
    }

    @Test
    void theLimitBoundsARun() {
        upload(org, OLD);
        upload(org, OLD);
        upload(org, OLD);

        assertThat(service.deleteUnreferenced(CUTOFF, 2)).isEqualTo(2);

        verify(attachmentService, times(2)).deleteAttachmentOf(any(), any());
    }

    @Test
    void aPresignRowWithNothingLeftGoesOnceItIsOldAndARowWithAnImageStays() {
        UUID empty = uploadRow(org, OLD);
        UUID young = uploadRow(org, YOUNG);
        Upload cited = upload(org, OLD);
        drawRegionOn(cited.attachmentId());

        service.deleteUnreferenced(CUTOFF, 100);
        entityManager.clear();

        assertThat(uploads.findByIdScoped(empty)).isEmpty();
        assertThat(uploads.findByIdScoped(young)).isPresent();
        assertThat(uploads.findByIdScoped(cited.uploadId())).isPresent();
    }

    @Test
    void theOrganizationListIsEveryOrganizationWithAnUpload() {
        upload(org, OLD);
        upload(otherOrg, OLD);
        entityManager.unwrap(Session.class).disableFilter("orgFilter");

        assertThat(uploads.findOrganizationIdsWithUploads(PageRequest.of(0, 500)))
                .contains(org.getId(), otherOrg.getId());
    }

    private record Upload(UUID uploadId, Long attachmentId) {}

    private Upload upload(Organization owner, LocalDateTime createdAt) {
        UUID id = uploadRow(owner, createdAt);
        Attachment attachment = new Attachment();
        attachment.setEntityType(ObservationEvidence.INTAKE_UPLOAD_ENTITY_TYPE);
        attachment.setEntityUuid(id);
        attachment.setStorageKey("observations/intake/" + owner.getId() + "/" + id + "/frame.jpg");
        attachment.setContentType("image/jpeg");
        attachment.setOrganization(owner);
        entityManager.persist(attachment);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE attachment SET created_at = :at WHERE id = :id")
                .setParameter("at", createdAt).setParameter("id", attachment.getId()).executeUpdate();
        entityManager.clear();
        return new Upload(id, attachment.getId());
    }

    private UUID uploadRow(Organization owner, LocalDateTime createdAt) {
        ObservationIntakeUpload row = new ObservationIntakeUpload();
        row.setId(UUID.randomUUID());
        row.setOrganization(owner);
        row.setProjectId(projectId);
        row.setProducerSubject("drone-fleet");
        entityManager.persist(row);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE inspection_observation_intake_uploads SET created_at = :at WHERE id = :id")
                .setParameter("at", createdAt).setParameter("id", row.getId()).executeUpdate();
        return row.getId();
    }

    private Observation observation() {
        Observation observation = new Observation();
        observation.setOrganization(org);
        observation.setProjectId(projectId);
        observation.setSource(ObservationSource.DRONE);
        observation.setTitle("Seen");
        observation.setReviewStatus(ObservationReviewStatus.PENDING);
        observation.setOutcomeKind(ObservationOutcomeKind.NONE);
        observation.setObservedAt(CUTOFF);
        entityManager.persist(observation);
        entityManager.flush();
        return observation;
    }

    private void drawRegionOn(Long attachmentId) {
        ObservationRegion region = new ObservationRegion();
        region.setOrganization(org);
        region.setObservationId(observation().getId());
        region.setAttachmentId(attachmentId);
        region.setShape(RegionShape.BOX);
        region.setX1(new BigDecimal("0.1"));
        region.setY1(new BigDecimal("0.1"));
        region.setX2(new BigDecimal("0.2"));
        region.setY2(new BigDecimal("0.2"));
        region.setClassKey("ppe_helmet");
        region.setOrigin(RegionOrigin.PRODUCER);
        region.setLineOrder(0);
        entityManager.persist(region);
        entityManager.flush();
    }

    private Organization organization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        entityManager.persist(organization);
        return organization;
    }
}
