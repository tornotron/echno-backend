package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ContractTermEntry;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.ContractTerm;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ContractTermEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Writes accepted contract terms to the package's contract term register (design section 4.5), one row
 * per term, with provenance on the value and the clause. The register is the module's own: these terms
 * describe the main contract the tender leads to, so nothing is written to {@code contract_deduction_rule}
 * or to sub-contract fields (Q1).
 *
 * <p>One value per term is applied at a time: two accepted values for the same term are refused before
 * any row, so a person rejects one first. A later accepted value replaces the one in the register, and
 * the candidate it replaces is marked superseded.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class ContractTermApplier implements IngestionApplier {

    public static final String TARGET = "CONTRACT_TERM";
    static final int VALUE_MAX = 1000;
    static final int CLAUSE_MAX = 100;
    static final int MAX_TERMS = 500;

    private final IngestPackageRepository packages;
    private final CandidateRepository candidates;
    private final SegmentRepository segments;
    private final ContractTermEntryRepository entries;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    public ContractTermApplier(IngestPackageRepository packages, CandidateRepository candidates, SegmentRepository segments,
                               ContractTermEntryRepository entries, TenantEntityHelper tenantEntityHelper,
                               UserContextService userContextService) {
        this.packages = packages;
        this.candidates = candidates;
        this.segments = segments;
        this.entries = entries;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.CONTRACT_TERM;
    }

    /** The register is the module's own table, so its guard is the module's apply guard. */
    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return "@access.can('document-ingestion', 'apply')";
    }

    @Override
    public void preflight(UUID packageId) {
        requirePackage(packageId);
        Map<ContractTerm, Candidate> accepted = new HashMap<>();
        for (Candidate c : candidates.findForReview(packageId, CandidateKind.CONTRACT_TERM, CandidateStatus.ACCEPTED,
                null, PageRequest.of(0, MAX_TERMS))) {
            ContractTerm term = term(c);
            if (accepted.put(term, c) != null) {
                throw new InvalidRequestException("Two values are accepted for " + term.label() + ". Reject one of them "
                        + "first.");
            }
        }
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        ContractTerm term = term(candidate);
        String value = text(accepted.get(ContractTerm.VALUE));
        if (value == null) {
            throw new InvalidRequestException("The accepted " + term.label() + " is empty");
        }
        if (value.length() > VALUE_MAX) {
            throw new InvalidRequestException("The " + term.label() + " is longer than " + VALUE_MAX + " characters; "
                    + "shorten it in the review first");
        }
        String clause = text(accepted.get(ContractTerm.CLAUSE));
        if (clause != null && clause.length() > CLAUSE_MAX) {
            throw new InvalidRequestException("The clause \"" + clause + "\" is longer than " + CLAUSE_MAX
                    + " characters; correct it in the review first");
        }
        IngestPackage p = requirePackage(candidate.getPackageId());
        ContractTermEntry row = entries.findByPackageAndTerm(p.getId(), term.key()).orElseGet(() -> {
            ContractTermEntry created = new ContractTermEntry();
            created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            created.setPackageId(p.getId());
            created.setTerm(term.key());
            return created;
        });
        if (row.getCandidateId() != null && !row.getCandidateId().equals(candidate.getId())) {
            candidates.findByIdScoped(row.getCandidateId())
                    .filter(prior -> prior.getStatus() == CandidateStatus.APPLIED)
                    .ifPresent(prior -> {
                        prior.setStatus(CandidateStatus.SUPERSEDED);
                        candidates.save(prior);
                    });
        }
        row.setProjectId(p.getProjectId());
        row.setValueText(value);
        row.setClause(clause);
        row.setSourceClass(candidate.getSegmentId() == null ? null
                : segments.findByIdScoped(candidate.getSegmentId()).map(s -> s.getDocClass()).orElse(null));
        row.setDocumentId(candidate.getDocumentId());
        row.setCandidateId(candidate.getId());
        row.setUpdatedBy(userContextService.getCurrentUserId());
        ContractTermEntry saved = entries.save(row);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ContractTerm.VALUE, "value");
        if (clause != null) {
            fields.put(ContractTerm.CLAUSE, "clause");
        }
        return new AppliedTarget(TARGET, saved.getId().toString(), fields);
    }

    private static ContractTerm term(Candidate c) {
        return ContractTerm.of(c.getNaturalKey()).orElseThrow(() -> new InvalidRequestException(
                "\"" + c.getNaturalKey() + "\" is not one of the contract terms"));
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
