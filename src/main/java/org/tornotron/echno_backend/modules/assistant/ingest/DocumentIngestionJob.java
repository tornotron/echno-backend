package org.tornotron.echno_backend.modules.assistant.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.dto.IngestStatusDto;

/**
 * Executes document ingestion inside an organization's tenant context and transaction.
 *
 * <p>Uses {@link TenantScopedJobRunner#callForTenantInTransaction(Long, java.util.concurrent.Callable)}
 * to guarantee that Hibernate's {@code orgFilter} is active, refusing null tenant ids.
 */
@Slf4j
@Component
@AssistantModuleEnabled
public class DocumentIngestionJob {

    private final TenantScopedJobRunner tenantRunner;
    private final DocumentIngestionService ingestionService;

    public DocumentIngestionJob(TenantScopedJobRunner tenantRunner,
                                DocumentIngestionService ingestionService) {
        this.tenantRunner = tenantRunner;
        this.ingestionService = ingestionService;
    }

    /**
     * Ingests a document for an organization.
     *
     * @param orgId        target organization id
     * @param attachmentId target attachment id
     * @param projectId    optional project id
     * @return the ingestion status result
     */
    public IngestStatusDto run(Long orgId, Long attachmentId, Long projectId) {
        if (orgId == null) {
            throw new TenantIdMissingException("Cannot run document ingestion job without an organization id");
        }
        return tenantRunner.callForTenantInTransaction(orgId, () -> {
            log.info("Running document ingestion for org {} attachment {}", orgId, attachmentId);
            return ingestionService.ingest(attachmentId, projectId);
        });
    }
}
