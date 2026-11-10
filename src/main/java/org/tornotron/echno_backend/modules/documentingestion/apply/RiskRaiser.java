package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.ContractTermEntry;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.ObligationEntry;
import org.tornotron.echno_backend.modules.documentingestion.domain.Provenance;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;
import org.tornotron.echno_backend.modules.documentingestion.dto.RaiseRiskRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RaisedRiskDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.ContractTerm;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.Obligations;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ContractTermEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ObligationEntryRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ProvenanceRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;
import org.tornotron.echno_backend.risk.ProjectRiskService;
import org.tornotron.echno_backend.risk.dto.RiskDto;
import org.tornotron.echno_backend.risk.dto.RiskImportRequest;
import org.tornotron.echno_backend.risk.dto.RiskRequest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Raises a project risk from a contract term or an obligation in the registers (design section 4.5),
 * through the risk register's import, with the import reference set from the candidate the row came from.
 * The import skips a reference the project already holds, so raising twice gives one risk; the second
 * call returns the first risk. Asks the risk register's own manage guard, as its endpoint does.
 *
 * <p>The risk names the term or obligation and quotes it as printed. What it means for the project is the
 * person's call: Echno does not interpret the clause.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class RiskRaiser {

    public static final String TARGET_RISK = "PROJECT_RISK";
    /** Prefix of the import reference, then the candidate id; 55 characters, inside the column's 64. */
    public static final String IMPORT_REF_PREFIX = "document-ingestion:";
    static final String DEFAULT_CATEGORY = "contractual-legal";
    static final String DEFAULT_PROBABILITY = "medium";
    static final String DEFAULT_IMPACT = "moderate";
    static final String DEFAULT_RESPONSE = "mitigate";
    static final int TITLE_MAX = 255;
    static final int DESCRIPTION_MAX = 4000;

    private final ApplyGuard guard;
    private final ContractTermEntryRepository terms;
    private final ObligationEntryRepository obligations;
    private final IngestPackageRepository packages;
    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final ProvenanceRepository provenance;
    private final ProjectRiskService risks;
    private final TransactionRetryTemplate retry;
    private final UserContextService userContextService;

    public RiskRaiser(ApplyGuard guard, ContractTermEntryRepository terms, ObligationEntryRepository obligations,
                      IngestPackageRepository packages, CandidateRepository candidates, SourceRefRepository sources,
                      ProvenanceRepository provenance, ProjectRiskService risks, TransactionRetryTemplate retry,
                      UserContextService userContextService) {
        this.guard = guard;
        this.terms = terms;
        this.obligations = obligations;
        this.packages = packages;
        this.candidates = candidates;
        this.sources = sources;
        this.provenance = provenance;
        this.risks = risks;
        this.retry = retry;
        this.userContextService = userContextService;
    }

    /** What a risk is raised from: the project, the candidate, and the risk's words. */
    private record Seed(Long projectId, Candidate candidate, String sourceField, String title, String description) {
    }

    public RaisedRiskDto raiseFromTerm(UUID termId, RaiseRiskRequest request) {
        guard.require("@access.can('compliance', 'create')");
        Seed seed = retry.execute("DocumentIngestion.riskSeedTerm", () -> {
            ContractTermEntry row = terms.findByIdScoped(termId)
                    .orElseThrow(() -> new ResourceNotFoundException("No contract term " + termId));
            Long projectId = requireProject(row.getPackageId());
            Candidate c = requireCandidate(row.getCandidateId());
            String label = ContractTerm.of(row.getTerm()).map(ContractTerm::label).orElse(row.getTerm());
            String clause = row.getClause() == null ? "" : " (clause " + row.getClause() + ")";
            String title = "Contract term: " + label + clause;
            String description = label + " as " + source(row.getSourceClass()) + " print it" + clause + ": \""
                    + row.getValueText() + "\".";
            return new Seed(projectId, c, ContractTerm.VALUE, title, description);
        });
        RaisedRiskDto raised = raise(seed, request);
        retry.executeWithoutResult("DocumentIngestion.riskLinkTerm", () -> terms.findByIdScoped(termId).ifPresent(row -> {
            row.setRiskId(raised.riskId());
            terms.save(row);
        }));
        return raised;
    }

    public RaisedRiskDto raiseFromObligation(UUID obligationId, RaiseRiskRequest request) {
        guard.require("@access.can('compliance', 'create')");
        Seed seed = retry.execute("DocumentIngestion.riskSeedObligation", () -> {
            ObligationEntry row = obligations.findByIdScoped(obligationId)
                    .orElseThrow(() -> new ResourceNotFoundException("No obligation " + obligationId));
            Long projectId = requireProject(row.getPackageId());
            Candidate c = requireCandidate(row.getCandidateId());
            String clause = row.getClause() == null ? "" : " (clause " + row.getClause() + ")";
            String about = firstOf(row.getTopic(), row.getRequiredDocument(), "obligation");
            String title = "Obligation: " + about + " within " + row.getDeadlineText() + clause;
            StringBuilder d = new StringBuilder();
            d.append(firstOf(row.getResponsibleParty(), "A party")).append(" within ").append(row.getDeadlineText());
            if (row.getTriggerEvent() != null) {
                d.append(" of ").append(row.getTriggerEvent());
            }
            d.append(", as ").append(source(row.getSourceClass())).append(" print it").append(clause).append('.');
            if (row.getRequiredDocument() != null) {
                d.append(" Document: ").append(row.getRequiredDocument()).append('.');
            }
            if (row.getFinancialImpact() != null) {
                d.append(" Money at stake, as printed: ").append(row.getFinancialImpact()).append('.');
            }
            return new Seed(projectId, c, Obligations.DEADLINE, title, d.toString());
        });
        RaisedRiskDto raised = raise(seed, request);
        retry.executeWithoutResult("DocumentIngestion.riskLinkObligation", () ->
                obligations.findByIdScoped(obligationId).ifPresent(row -> {
                    row.setRiskId(raised.riskId());
                    obligations.save(row);
                }));
        return raised;
    }

    /**
     * Imports the risk once per candidate. The risk service runs its own transaction with retries, so
     * this method holds none; the provenance on a new risk is written after, once.
     */
    private RaisedRiskDto raise(Seed seed, RaiseRiskRequest request) {
        RaiseRiskRequest r = request == null ? RaiseRiskRequest.defaults() : request;
        String ref = IMPORT_REF_PREFIX + seed.candidate().getId();
        Optional<RiskDto> before = risks.findImported(seed.projectId(), ref);
        if (before.isPresent()) {
            return new RaisedRiskDto(before.get().id(), seed.projectId(), before.get().riskId(), false);
        }
        String description = seed.description() + " Raised from document ingestion; Echno shows the clause as printed "
                + "and does not interpret it.";
        RiskRequest line = new RiskRequest(cut(firstOf(blank(r.title()), seed.title()), TITLE_MAX), cut(description,
                DESCRIPTION_MAX), firstOf(r.category(), DEFAULT_CATEGORY), null, "identified", blank(r.owner()),
                firstOf(r.probability(), DEFAULT_PROBABILITY), firstOf(r.impact(), DEFAULT_IMPACT),
                firstOf(r.probability(), DEFAULT_PROBABILITY), firstOf(r.impact(), DEFAULT_IMPACT),
                firstOf(r.responseType(), DEFAULT_RESPONSE), null, LocalDate.now(), null, null, null, null, null, ref);
        List<RiskDto> added = risks.importRisks(seed.projectId(), new RiskImportRequest(List.of(line)));
        boolean created = !added.isEmpty();
        // A second raise that arrived at the same moment lost the import; the risk is the winner's.
        RiskDto risk = created ? added.get(0) : risks.findImported(seed.projectId(), ref)
                .orElseThrow(() -> new IllegalStateException("The risk raised under " + ref + " was not found"));
        if (created) {
            retry.executeWithoutResult("DocumentIngestion.riskProvenance", () -> writeProvenance(seed, risk));
        }
        return new RaisedRiskDto(risk.id(), seed.projectId(), risk.riskId(), created);
    }

    private void writeProvenance(Seed seed, RiskDto risk) {
        if (!provenance.findForTarget(TARGET_RISK, risk.id().toString(), PageRequest.of(0, 1)).isEmpty()) {
            return;
        }
        Candidate c = requireCandidate(seed.candidate().getId());
        Map<String, Object> accepted = c.getAcceptedPayload() == null ? c.getPayload() : c.getAcceptedPayload();
        SourceRef ref = sources.findForCandidates(List.of(c.getId())).stream()
                .filter(s -> seed.sourceField().equals(s.getField())).findFirst().orElse(null);
        Provenance p = new Provenance();
        p.setOrganization(c.getOrganization());
        p.setTargetType(TARGET_RISK);
        p.setTargetId(risk.id().toString());
        p.setTargetField("description");
        p.setValueExtracted(text(c.getPayload().get(seed.sourceField())));
        p.setValueAccepted(text(accepted.get(seed.sourceField())));
        p.setSourceRefId(ref == null ? null : ref.getId());
        p.setEnteredByHand(false);
        p.setCandidateId(c.getId());
        p.setAcceptedBy(c.getReviewedBy());
        p.setAcceptedAt(c.getReviewedAt());
        p.setAppliedBy(userContextService.getCurrentUserId());
        p.setAppliedAt(LocalDateTime.now());
        provenance.save(p);
    }

    private Long requireProject(UUID packageId) {
        IngestPackage p = packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
        if (p.getProjectId() == null) {
            throw new InvalidRequestException("The package has no project yet, and a risk belongs to a project. Apply "
                    + "the project fields first, or create the package on a project.");
        }
        return p.getProjectId();
    }

    private Candidate requireCandidate(UUID candidateId) {
        return candidates.findByIdScoped(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("No candidate " + candidateId));
    }

    private static String source(String sourceClass) {
        if ("SCC".equals(sourceClass)) {
            return "the special conditions";
        }
        if ("CONTRACT_DATA".equals(sourceClass)) {
            return "the contract data";
        }
        return "the general conditions";
    }

    private static String firstOf(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
