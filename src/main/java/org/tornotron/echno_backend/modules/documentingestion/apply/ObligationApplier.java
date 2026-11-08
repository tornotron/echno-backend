package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.ObligationEntry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.Obligations;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ObligationEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Writes accepted obligations to the Contract Obligation Register (design section 4.5), one row per
 * obligation, with provenance on each field. The register holds the obligations found and accepted, never
 * a complete list, and keeps each as printed: Echno does not interpret the clause.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class ObligationApplier implements IngestionApplier {

    public static final String TARGET = "OBLIGATION";

    /** Payload field to register field, with the register column's length. */
    private static final Map<String, Column> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put(Obligations.CLAUSE, new Column("clause", 100));
        COLUMNS.put(Obligations.DEADLINE, new Column("deadline", 255));
        COLUMNS.put(Obligations.RESPONSIBLE_PARTY, new Column("responsibleParty", 255));
        COLUMNS.put(Obligations.TOPIC, new Column("topic", 255));
        COLUMNS.put(Obligations.TRIGGER, new Column("trigger", 1000));
        COLUMNS.put(Obligations.REQUIRED_DOCUMENT, new Column("requiredDocument", 500));
        COLUMNS.put(Obligations.FINANCIAL_IMPACT, new Column("financialImpact", 1000));
    }

    private record Column(String field, int max) {
    }

    private final IngestPackageRepository packages;
    private final SegmentRepository segments;
    private final ObligationEntryRepository entries;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public ObligationApplier(IngestPackageRepository packages, SegmentRepository segments,
                             ObligationEntryRepository entries, TenantEntityHelper tenantEntityHelper,
                             UserContextService userContextService) {
        this.packages = packages;
        this.segments = segments;
        this.entries = entries;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.OBLIGATION;
    }

    /** The register is the module's own table, so its guard is the module's apply guard. */
    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return DocumentIngestionModule.APPLY_GUARD;
    }

    @Override
    public void preflight(UUID packageId) {
        requirePackage(packageId);
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        Map<String, String> values = new LinkedHashMap<>();
        COLUMNS.forEach((payloadField, column) -> {
            String v = text(accepted.get(payloadField));
            if (v != null && v.length() > column.max()) {
                throw new InvalidRequestException("The " + payloadField.replace('_', ' ') + " is longer than "
                        + column.max() + " characters; shorten it in the review first");
            }
            if (v != null) {
                values.put(payloadField, v);
            }
        });
        String deadline = values.get(Obligations.DEADLINE);
        if (deadline == null) {
            throw new InvalidRequestException("The obligation has no deadline; the register holds obligations with a "
                    + "stated deadline");
        }
        IngestPackage p = requirePackage(candidate.getPackageId());
        ObligationEntry row = entries.findByCandidate(candidate.getId()).orElseGet(() -> {
            ObligationEntry created = new ObligationEntry();
            created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            created.setPackageId(p.getId());
            created.setCandidateId(candidate.getId());
            return created;
        });
        row.setProjectId(p.getProjectId());
        row.setClause(values.get(Obligations.CLAUSE));
        row.setDeadlineText(deadline);
        row.setDeadlineDays(Obligations.days(deadline).orElse(null));
        row.setResponsibleParty(values.get(Obligations.RESPONSIBLE_PARTY));
        row.setTopic(values.get(Obligations.TOPIC));
        row.setTriggerEvent(values.get(Obligations.TRIGGER));
        row.setRequiredDocument(values.get(Obligations.REQUIRED_DOCUMENT));
        row.setFinancialImpact(values.get(Obligations.FINANCIAL_IMPACT));
        row.setSourceClass(candidate.getSegmentId() == null ? null
                : segments.findByIdScoped(candidate.getSegmentId()).map(s -> s.getDocClass()).orElse(null));
        row.setDocumentId(candidate.getDocumentId());
        row.setUpdatedBy(userContextService.getCurrentUserId());
        ObligationEntry saved = entries.save(row);
        Map<String, String> fields = new LinkedHashMap<>();
        values.keySet().forEach(k -> fields.put(k, COLUMNS.get(k).field()));
        return new AppliedTarget(TARGET, saved.getId().toString(), fields);
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
