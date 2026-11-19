package org.tornotron.echno_backend.modules.assistant.ingest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantDocumentUnit;
import org.tornotron.echno_backend.modules.assistant.dto.IngestStatusDto;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "keycloak-initializer.initialize-on-startup=false",
        "echno.cache.provider=caffeine",
        "echno.modules.assistant.enabled=true"
})
class DocumentIngestionServiceIT extends AbstractIntegrationTest {

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private AssistantDocumentUnitRepository unitRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private AttachmentService attachmentService;

    @MockBean
    private FileStorageService fileStorageService;

    private Long orgId;
    private Long projectId;
    private Long attachmentId;

    @BeforeEach
    void setUp() {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> {
            Organization org = new Organization();
            org.setOrganizationName("Ingest Test Org " + UUID.randomUUID());
            org.setOrganizationAddress("123 Construction Ave");
            org.setOrganizationEmail("ingest-" + UUID.randomUUID() + "@test.org");
            org.setOrganizationPhone("0000000000");
            org = organizationRepository.save(org);
            orgId = org.getId();

            Project project = new Project();
            project.setProjectName("Ingest Test Project");
            project.setOrganization(org);
            project = projectRepository.save(project);
            projectId = project.getId();

            Attachment attachment = new Attachment();
            attachment.setEntityType("PROJECT");
            attachment.setEntityId(projectId);
            attachment.setStorageKey("org-" + orgId + "/docs/tower-alpha-spec.txt");
            attachment.setUrl("https://cdn.echno.internal/org-" + orgId + "/docs/tower-alpha-spec.txt");
            attachment.setOriginalFilename("tower-alpha-spec.txt");
            attachment.setContentType("text/plain");
            attachment.setFileSize(256L);
            attachment.setOrganization(org);
            attachment = attachmentRepository.save(attachment);
            attachmentId = attachment.getId();
        });
        TenantContext.setCurrentOrgId(orgId);
    }

    @AfterEach
    void tearDown() {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> {
            if (attachmentId != null && attachmentRepository.existsById(attachmentId)) {
                attachmentRepository.deleteById(attachmentId);
            }
            if (attachmentId != null) {
                unitRepository.deleteByAttachmentId(attachmentId);
            }
            if (projectId != null && projectRepository.existsById(projectId)) {
                projectRepository.deleteById(projectId);
            }
            if (orgId != null && organizationRepository.existsById(orgId)) {
                organizationRepository.deleteById(orgId);
            }
        });
        TenantContext.clear();
    }

    @Test
    @DisplayName("Ingests document chunks into CockroachDB vector table and reports status")
    void ingestsDocumentAndQueriesStatus() {
        String docText = "Structural Specification for Tower Alpha:\n\n"
                + "The foundation consists of bored cast-in-situ concrete piles of 600mm diameter "
                + "extending to hard stratum at 18m depth. Grade M35 self-compacting concrete is specified.\n\n"
                + "All reinforcement bars shall be Fe500D TMT conforming to IS 1786.";
        byte[] docBytes = docText.getBytes(StandardCharsets.UTF_8);

        AttachmentDto attachment = new AttachmentDto();
        attachment.setId(attachmentId);
        attachment.setUrl("https://cdn.echno.internal/org-" + orgId + "/docs/tower-alpha-spec.txt");
        attachment.setFileName("tower-alpha-spec.txt");
        attachment.setContentType("text/plain");

        when(attachmentService.getAttachments(eq(List.of(attachmentId))))
                .thenReturn(List.of(attachment));
        when(fileStorageService.keyForStoredReference(anyString()))
                .thenReturn(Optional.of("org-" + orgId + "/docs/tower-alpha-spec.txt"));
        when(fileStorageService.readObject(eq("org-" + orgId + "/docs/tower-alpha-spec.txt"), anyLong()))
                .thenReturn(Optional.of(new FileStorageService.StoredObject(
                        "org-" + orgId + "/docs/tower-alpha-spec.txt", "text/plain", docBytes)));

        // 1. Ingest
        IngestStatusDto ingestResult = ingestionService.ingest(attachmentId, projectId);
        assertThat(ingestResult.status()).isEqualTo("COMPLETED");
        assertThat(ingestResult.chunkCount()).isGreaterThan(0);

        // 2. Verify DB state
        List<AssistantDocumentUnit> units = unitRepository.findByAttachmentId(attachmentId);
        assertThat(units).hasSize(ingestResult.chunkCount());
        for (AssistantDocumentUnit unit : units) {
            assertThat(unit.getOrganization().getId()).isEqualTo(orgId);
            assertThat(unit.getProjectId()).isEqualTo(projectId);
            assertThat(unit.getAttachmentId()).isEqualTo(attachmentId);
            assertThat(unit.getContent()).isNotBlank();
            assertThat(unit.getContentHash()).isNotBlank();
            assertThat(unit.getEmbedding()).isNotNull();
            assertThat(unit.getEmbedding().length).isEqualTo(1024);
        }

        // 3. Status check
        IngestStatusDto status = ingestionService.status(attachmentId);
        assertThat(status.status()).isEqualTo("COMPLETED");
        assertThat(status.chunkCount()).isEqualTo(units.size());

        // 4. Re-ingestion deduplication (same text hashes should reuse embeddings smoothly)
        IngestStatusDto reingestResult = ingestionService.ingest(attachmentId, projectId);
        assertThat(reingestResult.status()).isEqualTo("COMPLETED");
        assertThat(reingestResult.chunkCount()).isEqualTo(units.size());

        // 5. Cascade delete verified
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(s -> {
            attachmentRepository.deleteById(attachmentId);
        });
        assertThat(unitRepository.findByAttachmentId(attachmentId)).isEmpty();
    }
}
