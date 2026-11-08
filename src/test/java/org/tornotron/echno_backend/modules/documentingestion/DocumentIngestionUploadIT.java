package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ParseJobType;
import org.tornotron.echno_backend.modules.documentingestion.dto.CreatePackageRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentUploadDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PresignDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RegisterDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.job.ExtractJobQueue;
import org.tornotron.echno_backend.modules.documentingestion.job.ParseJobIngestor;
import org.tornotron.echno_backend.modules.documentingestion.repository.ParseJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.service.DocumentIngestionLimits;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestDocumentService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestPackageService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Packages, documents and the parse queue on the real migration: tenant isolation of every table,
 * the presign limits, a forged key, the queue row the worker reads, lease recovery, attempts
 * running out, and reading a closed job back, including a ZIP's members and a reused parse.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({IngestPackageService.class, IngestDocumentService.class, ParseJobIngestor.class,
        DocumentIngestionLimits.class, UserContextService.class, TenantEntityHelper.class,
        ExtractorRegistry.class, ExtractJobQueue.class, IngestLlmProperties.class, ClassificationExtractor.class,
        ClassificationWriter.class})
class DocumentIngestionUploadIT extends AbstractIntegrationTest {

    private static final long MB = 1024L * 1024;

    @Autowired
    private IngestPackageService packages;

    @Autowired
    private IngestDocumentService documents;

    @Autowired
    private ParseJobIngestor ingestor;

    @Autowired
    private ParseJobRepository jobs;

    @MockitoBean
    private FileStorageService storage;

    @MockitoBean
    private AttachmentService attachments;

    @MockitoBean
    private ContractBoqPort contractBoq;

    @PersistenceContext
    private EntityManager entityManager;

    private Long orgA;
    private Long orgB;

    @BeforeEach
    void twoOrganizations() {
        orgA = persistOrganization("Ingest A").getId();
        orgB = persistOrganization("Ingest B").getId();
        when(storage.generateUploadUrlForKey(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> new PresignedUpload(inv.getArgument(0), "https://store.test/put", inv.getArgument(1), 3600));
        when(storage.objectExists(anyString())).thenReturn(true);
        Attachment attachment = new Attachment();
        attachment.setId(41L);
        when(attachments.registerUploads(any(), any(org.tornotron.echno_backend.common.dto.AttachmentOwner.class), anyString()))
                .thenReturn(List.of(attachment));
        tenant(orgA);
    }

    @AfterEach
    void clear() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @Test
    void anotherTenantsPackageDocumentAndJobReadAsAbsent() {
        UUID pkg = packages.create(new CreatePackageRequest("Tender", null, null)).id();
        DocumentUploadDto upload = documents.presign(pkg, file("BOQ.xlsx", 5 * MB));
        DocumentDto registered = documents.register(pkg, upload.documentId(), new RegisterDocumentRequest(upload.upload().key()));
        assertThat(registered.latestJob()).isNotNull();

        asTenant(orgB, () -> {
            assertThatThrownBy(() -> packages.get(pkg)).isInstanceOf(ResourceNotFoundException.class);
            assertThat(packages.list(0, 20).getContent()).isEmpty();
            assertThatThrownBy(() -> documents.get(upload.documentId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> documents.list(pkg, 0, 50)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> documents.downloadUrl(upload.documentId())).isInstanceOf(ResourceNotFoundException.class);
            assertThat(jobs.findByIdScoped(registered.latestJob().id())).isEmpty();
            assertThatThrownBy(() -> documents.presign(pkg, file("x.pdf", MB))).isInstanceOf(ResourceNotFoundException.class);
            return null;
        });
        assertThat(packages.list(0, 20).getContent()).extracting(p -> p.id()).containsExactly(pkg);
    }

    @Test
    void presignRefusesTypesAndSizesOverTheLimits() {
        UUID pkg = packages.create(new CreatePackageRequest("Limits", null, null)).id();

        assertThatThrownBy(() -> documents.presign(pkg, file("Spec.pdf", 200 * MB + 1)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("limit");
        assertThat(documents.presign(pkg, file("Spec.pdf", 200 * MB)).documentId()).isNotNull();
        assertThat(documents.presign(pkg, file("All.zip", 1024 * MB)).documentId()).isNotNull();
        assertThatThrownBy(() -> documents.presign(pkg, file("All.zip", 1024 * MB + 1)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> documents.presign(pkg, file("Old.doc", MB)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("DOCX");
        assertThatThrownBy(() -> documents.presign(pkg, file("Set.rar", MB)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("ZIP");
        assertThatThrownBy(() -> documents.presign(pkg, file("photo.jpg", MB)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theKeyIsComposedHereUnderTheOrganizationAndAForgedOneIsRefused() {
        UUID pkg = packages.create(new CreatePackageRequest("Keys", null, null)).id();
        DocumentUploadDto upload = documents.presign(pkg, file("../../etc/GCC.pdf", MB));

        assertThat(upload.upload().key())
                .isEqualTo("doc-ingest/" + orgA + "/" + pkg + "/" + upload.documentId() + "/source");
        assertThat(documents.get(upload.documentId()).fileName()).isEqualTo("GCC.pdf");
        String foreign = "doc-ingest/" + orgB + "/" + pkg + "/" + upload.documentId() + "/source";
        assertThatThrownBy(() -> documents.register(pkg, upload.documentId(), new RegisterDocumentRequest(foreign)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("not issued");
        verify(storage, never()).objectExists(foreign);
    }

    @Test
    void registerQueuesTheJobTheWorkerReadsAndRefusesASecondRegister() {
        UUID pkg = packages.create(new CreatePackageRequest("Queue", null, null)).id();
        DocumentUploadDto upload = documents.presign(pkg, file("BOQ.xlsx", MB));
        when(storage.objectExists(upload.upload().key())).thenReturn(false);
        assertThatThrownBy(() -> documents.register(pkg, upload.documentId(), new RegisterDocumentRequest(upload.upload().key())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("upload");

        when(storage.objectExists(upload.upload().key())).thenReturn(true);
        DocumentDto doc = documents.register(pkg, upload.documentId(), new RegisterDocumentRequest(upload.upload().key()));

        assertThat(doc.status()).isEqualTo(DocumentStatus.QUEUED);
        ParseJob job = jobs.findByIdScoped(doc.latestJob().id()).orElseThrow();
        assertThat(job.getJobType()).isEqualTo(ParseJobType.PARSE);
        assertThat(job.getSourceKey()).isEqualTo(upload.upload().key());
        assertThat(job.getOutputPrefix()).isEqualTo("doc-ingest/" + orgA + "/" + pkg + "/" + doc.id() + "/output/");
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThatThrownBy(() -> documents.register(pkg, upload.documentId(), new RegisterDocumentRequest(upload.upload().key())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("already");
    }

    @Test
    void aDwgIsStoredButNeverQueued() {
        UUID pkg = packages.create(new CreatePackageRequest("Drawings", null, null)).id();
        DocumentUploadDto upload = documents.presign(pkg, file("A-101.dwg", MB));

        DocumentDto doc = documents.register(pkg, upload.documentId(), new RegisterDocumentRequest(upload.upload().key()));

        assertThat(doc.status()).isEqualTo(DocumentStatus.STORED_ONLY);
        assertThat(doc.error()).contains("PDF or DXF");
        assertThat(doc.latestJob()).isNull();
    }

    @Test
    void anExpiredLeaseIsRequeuedThenFailedWhenAttemptsRunOut() {
        ParseJob job = queuedJob("BOQ.xlsx");
        markRunning(job.getId(), 1, LocalDateTime.now().minusMinutes(1));

        assertThat(jobs.requeueExpiredLeases(LocalDateTime.now())).isEqualTo(1);
        entityManager.clear();
        assertThat(jobs.findByIdScoped(job.getId()).orElseThrow().getStatus()).isEqualTo(ParseJobStatus.QUEUED);

        markRunning(job.getId(), 3, LocalDateTime.now().minusMinutes(1));
        assertThat(jobs.requeueExpiredLeases(LocalDateTime.now())).isZero();
        assertThat(jobs.failExpiredLeases(LocalDateTime.now(), "gave up")).isEqualTo(1);
        entityManager.clear();

        ingestAsReplica(job.getId());
        DocumentDto doc = documents.get(job.getDocumentId());
        assertThat(doc.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.error()).isEqualTo("gave up");
        assertThat(documents.reparse(doc.id()).latestJob().status()).isEqualTo(ParseJobStatus.QUEUED);
    }

    @Test
    void aDoneJobIsReadBackWithItsCountsAndAReusedPrefixStaysInTheOrganization() {
        ParseJob job = queuedJob("BOQ.xlsx");
        String reused = "doc-ingest/" + orgA + "/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/output/";
        entityManager.createNativeQuery("UPDATE doc_ingest_parse_job SET status = 'DONE', finished_at = now(), "
                        + "source_sha256 = :sha, sheet_count = 3, row_count = 2000, reused_output_prefix = :p WHERE id = :id")
                .setParameter("sha", "a".repeat(64)).setParameter("p", reused).setParameter("id", job.getId())
                .executeUpdate();
        entityManager.clear();

        ingestAsReplica(job.getId());

        DocumentDto doc = documents.get(job.getDocumentId());
        assertThat(doc.status()).isEqualTo(DocumentStatus.PARSED);
        assertThat(doc.sha256()).isEqualTo("a".repeat(64));
        assertThat(doc.sheetCount()).isEqualTo(3);
        assertThat(doc.rowCount()).isEqualTo(2000);
        verify(storage).objectExists(reused + "meta.json");
    }

    @Test
    void aZipManifestBecomesChildDocumentsEachQueued() {
        ParseJob zip = queuedJob("Tender.zip");
        assertThat(zip.getJobType()).isEqualTo(ParseJobType.UNPACK);
        String manifest = "{\"members\":[{\"path\":\"boq/BOQ.xlsx\",\"sha256\":\"" + "b".repeat(64) + "\",\"sizeBytes\":10},"
                + "{\"path\":\"dwg/A-1.dwg\",\"sha256\":\"" + "c".repeat(64) + "\",\"sizeBytes\":20},"
                + "{\"path\":\"inner.zip\",\"sha256\":\"" + "d".repeat(64) + "\",\"sizeBytes\":30}]}";
        when(storage.readObject(eq(zip.getOutputPrefix() + "manifest.json"), anyLong())).thenReturn(Optional.of(
                new FileStorageService.StoredObject("k", "application/json", manifest.getBytes(StandardCharsets.UTF_8))));
        entityManager.createNativeQuery("UPDATE doc_ingest_parse_job SET status = 'DONE', finished_at = now() WHERE id = :id")
                .setParameter("id", zip.getId()).executeUpdate();
        entityManager.clear();

        ingestAsReplica(zip.getId());

        List<DocumentDto> all = documents.list(zip.getPackageId(), 0, 50).getContent();
        assertThat(all).hasSize(4);
        DocumentDto boq = all.stream().filter(d -> d.fileName().equals("BOQ.xlsx")).findFirst().orElseThrow();
        assertThat(boq.parentDocumentId()).isEqualTo(zip.getDocumentId());
        assertThat(boq.fileType()).isEqualTo(DocumentFileType.XLSX);
        assertThat(boq.latestJob().status()).isEqualTo(ParseJobStatus.QUEUED);
        ParseJob member = jobs.findByIdScoped(boq.latestJob().id()).orElseThrow();
        assertThat(member.getSourceKey()).isEqualTo("doc-ingest/" + orgA + "/" + zip.getPackageId() + "/members/" + "b".repeat(64));
        assertThat(all).filteredOn(d -> d.fileName().equals("A-1.dwg")).singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo(DocumentStatus.STORED_ONLY));
        assertThat(all).filteredOn(d -> d.fileName().equals("inner.zip")).singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo(DocumentStatus.FAILED));
        assertThat(documents.get(zip.getDocumentId()).status()).isEqualTo(DocumentStatus.PARSED);
    }

    @Test
    void queuedWorkOfAnOrganizationTakenOffTheAllowListIsClosed() {
        ParseJob job = queuedJob("BOQ.xlsx");

        assertThat(jobs.findOrganizationsWithQueuedJobs()).contains(orgA);
        assertThat(jobs.failQueuedForOrganization(orgA, LocalDateTime.now(), "off")).isEqualTo(1);
        entityManager.clear();

        assertThat(jobs.findByIdScoped(job.getId()).orElseThrow().getStatus()).isEqualTo(ParseJobStatus.FAILED);
    }

    @Test
    void anIngestClaimIsTakenOnceAndAClaimLostSkipsTheWrite() {
        ParseJob job = queuedJob("BOQ.xlsx");
        entityManager.createNativeQuery("UPDATE doc_ingest_parse_job SET status = 'FAILED', finished_at = now(), "
                + "error = 'bad file' WHERE id = :id").setParameter("id", job.getId()).executeUpdate();
        LocalDateTime now = LocalDateTime.now();

        assertThat(jobs.claimForIngest(job.getId(), orgB, "r1", now, now.minusMinutes(15))).isZero();
        assertThat(jobs.claimForIngest(job.getId(), orgA, "r1", now, now.minusMinutes(15))).isEqualTo(1);
        assertThat(jobs.claimForIngest(job.getId(), orgA, "r2", now, now.minusMinutes(15))).isZero();
        entityManager.clear();

        ingestor.ingest(job.getId(), "r2");
        assertThat(documents.get(job.getDocumentId()).status()).isEqualTo(DocumentStatus.QUEUED);
        ingestor.ingest(job.getId(), "r1");
        entityManager.flush();
        assertThat(documents.get(job.getDocumentId()).status()).isEqualTo(DocumentStatus.FAILED);
    }

    private ParseJob queuedJob(String fileName) {
        UUID pkg = packages.create(new CreatePackageRequest("Jobs", null, null)).id();
        DocumentUploadDto upload = documents.presign(pkg, file(fileName, MB));
        DocumentDto doc = documents.register(pkg, upload.documentId(), new RegisterDocumentRequest(upload.upload().key()));
        entityManager.flush();
        return jobs.findByIdScoped(doc.latestJob().id()).orElseThrow();
    }

    private void markRunning(UUID jobId, int attempt, LocalDateTime leaseExpiresAt) {
        entityManager.createNativeQuery("UPDATE doc_ingest_parse_job SET status = 'RUNNING', worker_id = 'w', "
                        + "attempt = :a, lease_expires_at = :l WHERE id = :id")
                .setParameter("a", attempt).setParameter("l", leaseExpiresAt).setParameter("id", jobId)
                .executeUpdate();
    }

    private void ingestAsReplica(UUID jobId) {
        LocalDateTime now = LocalDateTime.now();
        assertThat(jobs.claimForIngest(jobId, orgA, "replica", now, now.minusMinutes(15))).isEqualTo(1);
        entityManager.clear();
        ingestor.ingest(jobId, "replica");
        entityManager.flush();
        entityManager.clear();
    }

    private static PresignDocumentRequest file(String name, long size) {
        return new PresignDocumentRequest(name, null, size);
    }

    private void tenant(Long orgId) {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter("orgFilter");
        TenantContext.setCurrentOrgId(orgId);
        session.enableFilter("orgFilter").setParameter("organizationId", orgId);
    }

    private <T> T asTenant(Long orgId, Supplier<T> work) {
        tenant(orgId);
        try {
            return work.get();
        } finally {
            tenant(orgA);
        }
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name + " " + UUID.randomUUID());
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(UUID.randomUUID() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        return org;
    }
}
