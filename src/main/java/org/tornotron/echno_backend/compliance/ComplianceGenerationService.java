package org.tornotron.echno_backend.compliance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.compliance.ai.OpenAiCompatibleComplianceService;
import org.tornotron.echno_backend.compliance.ai.ComplianceSuggestion;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.compliance.repository.ComplianceRuleRepository;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.inspection.InspectionCategory;
import org.tornotron.echno_backend.inspection.InspectionOrigin;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapper;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates compliance-type inspections for an approved project. It resolves the
 * project's state (from its address) and type, loads the matching curated
 * {@link ComplianceRule}s, asks {@link OpenAiCompatibleComplianceService} which of them apply,
 * and materialises each "applies" decision as a suggested, AI-generated inspection.
 *
 * <p>Idempotent: a (projectId, complianceRuleRef) pair that already has an inspection
 * is skipped, so a re-run (the manual regenerate endpoint, or a repeated approval)
 * only adds compliances that are missing. The document number series is shared with
 * manual inspections ({@code INSP}).
 *
 * <h2>How that idempotence survives two runs at once</h2>
 *
 * <p>The skip is an ordinary read, and a read cannot be the guarantee: two overlapping
 * runs both see "no such row" and both insert. They do not conflict in the database's
 * eyes either, because each inserts a different row (its own id, its own inspection
 * number), so {@code SERIALIZABLE} has nothing to abort. Two clicks on regenerate, or a
 * regenerate started while approval's automatic run is still going, produced two
 * compliance inspections for the same rule.
 *
 * <p>The guarantee is therefore in the schema: a unique index on
 * {@code (organization_id, project_id, compliance_rule_ref)} over the rows that carry a
 * rule reference (changelog {@code 060}). The loser of the race has its insert rejected
 * rather than duplicated.
 *
 * <p>The rejection cannot be caught per rule and skipped. Calling {@code save} does not
 * send the insert; Hibernate holds it until it next has to flush, which is the following
 * rule's existence query or the commit, so by the time the constraint speaks the loop has
 * moved on from the rule it is about. And wherever the failure lands, a failed statement
 * leaves the transaction unusable, so there is nothing left to continue into either. The
 * write phase is instead run through
 * {@link TransactionRetryTemplate} with the unique violation nominated as retryable: the
 * whole phase restarts, and on the restart the existence check reads the row the other
 * run has now committed and skips it, which was the intended outcome all along. The
 * request answers 200 with whatever this run genuinely created, and the user never learns
 * there was a race. Only a run that keeps losing until its attempts are gone surfaces a
 * 409, which is the honest answer at that point.
 *
 * <h2>Why this class owns no transaction of its own</h2>
 *
 * <p>The model call takes 34 to 47 seconds today and grows with the size of the curated
 * rule set. Running it inside a transaction pinned one of twenty pool connections for
 * that whole time while doing nothing but waiting on a third party, so a handful of
 * concurrent users could exhaust the pool. {@link #generateForProject} is therefore
 * three phases: read the inputs in one transaction, call the model with no transaction
 * and no connection held, then persist in a second transaction.
 *
 * <p>Both transactions are opened by calling {@link TransactionRetryTemplate}, which opens
 * them through {@code TransactionalWorkRunner}, and that choice is load bearing rather
 * than stylistic. {@code HibernateFilterConfig} advises
 * {@code @Transactional} methods in this package tree to enable the {@code orgFilter}
 * tenant filter on the session. A programmatic {@code TransactionTemplate} carries no
 * such annotation, so splitting the boundary that way would have run both phases with
 * tenant filtering off, and (because that filter fails open rather than closed) would
 * have done so silently.
 *
 * <p>The corollary is that the caller must have a tenant context on the thread before
 * calling in. Request threads get one from {@code TenantFilter}; background callers must
 * go through {@code TenantScopedJobRunner}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceGenerationService {

    private static final String DOC_TYPE = "INSP";

    private final ProjectRepository projectRepository;
    private final ComplianceRuleRepository ruleRepository;
    private final OpenAiCompatibleComplianceService complianceAiService;
    private final InspectionRepository inspectionRepository;
    private final EntryNumberGenerator numberGen;
    private final TenantEntityHelper tenantEntityHelper;
    private final InspectionMapper inspectionMapper;
    private final TransactionRetryTemplate retryTemplate;

    /**
     * What the read phase hands to the phases after it. The entities are detached once the
     * read transaction closes, which is safe here because every field the model call and
     * the write phase go on to read is a basic column loaded eagerly with the row: the
     * project's name, type and address, and each rule's code, name, phase, default risk,
     * description, authority and resolution options. Nothing in here is dereferenced
     * through a lazy association, and nothing may start being, or it will fail outside a
     * session.
     */
    private record GenerationInputs(Project project, String state, List<ComplianceRule> candidateRules) {}

    /**
     * Generates the missing compliance inspections for a project. Returns the DTOs of
     * the rows created by this call; the list is empty in the genuine "generation ran
     * but nothing applied" cases (the AI found no applicable rule, or every applicable
     * compliance already exists).
     *
     * <p>Precondition failures are surfaced instead of silently returning empty so the
     * caller can tell the user what to fix: a missing project raises
     * {@link ResourceNotFoundException} (404); a project with no type, an address with
     * no recognisable state, no rules registered for that jurisdiction, or an
     * unconfigured AI service each raise {@link InvalidRequestException} (400).
     *
     * <p>Runs in three phases so the model call holds no database connection; see the class
     * javadoc for why the boundaries are drawn where they are.
     *
     * @param projectId the project to generate for
     * @param orgId     the owning organization; must match the current tenant context
     */
    public List<InspectionDto> generateForProject(Long projectId, Long orgId) {
        GenerationInputs inputs = retryTemplate.execute(
                "ComplianceGenerationService.loadInputs", () -> loadInputs(projectId, orgId));

        // Outside any transaction: this is the 34 to 47 second call, and it must not be
        // holding a pool connection while it waits.
        List<ComplianceSuggestion> suggestions = complianceAiService.suggestCompliances(
                inputs.project(), inputs.state(), inputs.candidateRules());

        // An empty list now means only "there was nothing to ask": the AI service raises a
        // ComplianceAiException for a call that failed or answered with something unusable,
        // rather than returning empty and letting a truncated response read as a clean run
        // in which nothing applied. A model that assessed every rule and found none
        // applicable still returns one element per rule.
        if (suggestions.isEmpty()) {
            if (!complianceAiService.isConfigured()) {
                throw new InvalidRequestException(
                        "The compliance AI service is not configured, so suggestions cannot be "
                                + "generated. Set the compliance AI key and try again.");
            }
            log.info("Compliance AI had nothing to assess for project {}", projectId);
            return List.of();
        }

        // Restarted on a unique violation as well as on a serialization abort: losing the
        // race to another run is exactly the case the retry resolves, because the second
        // attempt's existence check sees the row the winner committed.
        return retryTemplate.execute(
                "ComplianceGenerationService.persist",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> persist(projectId, orgId, inputs, suggestions));
    }

    /**
     * Read phase. Resolves the project, its jurisdiction and the candidate rules, and
     * raises the precondition failures so the caller can tell the user what to fix before
     * anything slow happens.
     */
    private GenerationInputs loadInputs(Long projectId, Long orgId) {
        Project project = projectRepository.findByIdAndOrganization_Id(projectId, orgId).orElse(null);
        if (project == null) {
            throw new ResourceNotFoundException(
                    "No project with id " + projectId + " was found in this organization.");
        }

        ProjectType projectType = project.getProjectType();
        if (projectType == null) {
            throw new InvalidRequestException(
                    "This project has no type set. Add a project type (for example Residential or "
                            + "Commercial) before generating compliance.");
        }

        // A project that states its own state is taken at its word; scanning the free-text
        // address is only the fallback for projects that predate the field, and it cannot find
        // what the address does not say (an address of "Chennai" names no state at all).
        // Approval refuses a project this returns null for, so reaching the throw below means
        // the state was cleared between approval and generation.
        String state = IndianStateResolver.forProject(
                project.getProjectState(), project.getProjectAddress());
        if (state == null) {
            throw new InvalidRequestException(
                    "This project has no state set, and its address does not name one, so the "
                            + "applicable regulations cannot be determined. Set the project's state "
                            + "(for example Tamil Nadu) and try again.");
        }

        List<ComplianceRule> candidateRules =
                ruleRepository.findByStateIgnoreCaseAndProjectTypeAndActiveTrue(state, projectType);
        if (candidateRules.isEmpty()) {
            throw new InvalidRequestException(
                    "No compliance rules are registered for this project's state (" + state
                            + ") and type (" + projectType + ") yet. Once rules for that jurisdiction "
                            + "are added, generation will produce results.");
        }

        return new GenerationInputs(project, state, candidateRules);
    }

    /**
     * Write phase. Materialises each "applies" decision that does not already have an
     * inspection. The document-number sequence is locked here, inside the second
     * transaction, so it is held for the length of a few inserts rather than the length of
     * the model call.
     *
     * <p>Written to be safe to run from the start again, because the caller restarts it on
     * a conflict: it holds no state across attempts, re-reads what already exists, and
     * returns a list built fresh each time. Nothing outside the database has changed by the
     * time a conflict can surface, which is the condition the retry template asks for. A restart re-draws document numbers, so an
     * abandoned attempt leaves a gap in the {@code INSP} series; the series is a
     * human-readable identifier and not a count, so a gap costs nothing.
     */
    private List<InspectionDto> persist(Long projectId,
                                        Long orgId,
                                        GenerationInputs inputs,
                                        List<ComplianceSuggestion> suggestions) {
        Map<String, ComplianceRule> rulesByCode = inputs.candidateRules().stream()
                .collect(Collectors.toMap(ComplianceRule::getCode, Function.identity(), (a, b) -> a));

        Organization organization = tenantEntityHelper.resolveCurrentOrganization();

        // Group by phase (pre -> ongoing -> post) then code so the created rows read in
        // lifecycle order; the web can also regroup by compliancePhase.
        List<ComplianceSuggestion> ordered = suggestions.stream()
                .filter(ComplianceSuggestion::applies)
                .filter(s -> rulesByCode.containsKey(s.ruleCode()))
                .sorted(Comparator
                        .comparingInt((ComplianceSuggestion s) -> rulesByCode.get(s.ruleCode()).getPhase().ordinal())
                        .thenComparing(ComplianceSuggestion::ruleCode))
                .toList();

        List<InspectionDto> created = new ArrayList<>();
        for (ComplianceSuggestion suggestion : ordered) {
            ComplianceRule rule = rulesByCode.get(suggestion.ruleCode());

            // The fast path, and the one that makes a restarted attempt converge. It is not
            // the guarantee: the unique index from changelog 060 is, and it is what a run
            // that raced past this check collides with. See the class javadoc.
            if (inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                    projectId, rule.getCode(), orgId)) {
                log.debug("Compliance {} already exists for project {}; skipping", rule.getCode(), projectId);
                continue;
            }

            Inspection inspection = new Inspection();
            inspection.setInspectionNumber(numberGen.next(DOC_TYPE));
            inspection.setTitle(rule.getName());
            inspection.setType(InspectionType.COMPLIANCE);
            inspection.setCategory(InspectionCategory.COMPLIANCE);
            inspection.setStatus(InspectionStatus.SUGGESTED);
            inspection.setOrigin(InspectionOrigin.AI_GENERATED);
            inspection.setProjectId(projectId);
            inspection.setCompliancePhase(rule.getPhase());
            inspection.setRiskLevel(resolveRisk(suggestion.riskLevel(), rule.getDefaultRiskLevel()));
            inspection.setResolutionOptions(resolveResolutionOptions(suggestion, rule));
            inspection.setComplianceRuleRef(rule.getCode());
            inspection.setAiRationale(suggestion.rationale());
            inspection.setOrganization(organization);

            Inspection saved = inspectionRepository.save(inspection);
            created.add(inspectionMapper.toDto(saved));
        }

        log.info("Generated {} compliance inspection(s) for project {} (state '{}', type {})",
                created.size(), projectId, inputs.state(), inputs.project().getProjectType());
        return created;
    }

    /** The AI-assessed risk level if it parses, else the rule's default. */
    private ComplianceRiskLevel resolveRisk(String aiRiskLevel, ComplianceRiskLevel fallback) {
        if (aiRiskLevel != null && !aiRiskLevel.isBlank()) {
            try {
                return ComplianceRiskLevel.fromValue(aiRiskLevel);
            } catch (IllegalArgumentException e) {
                log.debug("Unrecognised AI risk level '{}'; using rule default {}", aiRiskLevel, fallback);
            }
        }
        return fallback;
    }

    /** The AI resolution options (newline-joined) if given, else the rule's own. */
    private String resolveResolutionOptions(ComplianceSuggestion suggestion, ComplianceRule rule) {
        if (suggestion.resolutionOptions() != null && !suggestion.resolutionOptions().isEmpty()) {
            return String.join("\n", suggestion.resolutionOptions());
        }
        return rule.getResolutionOptions();
    }
}
