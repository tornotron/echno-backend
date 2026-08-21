package org.tornotron.echno_backend.compliance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.compliance.ai.ClaudeComplianceService;
import org.tornotron.echno_backend.compliance.ai.ComplianceSuggestion;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.compliance.repository.ComplianceRuleRepository;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
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
 * {@link ComplianceRule}s, asks {@link ClaudeComplianceService} which of them apply,
 * and materialises each "applies" decision as a suggested, AI-generated inspection.
 *
 * <p>Idempotent: a (projectId, complianceRuleRef) pair that already has an inspection
 * is skipped, so a re-run (the manual regenerate endpoint, or a repeated approval)
 * only adds compliances that are missing. The document number series is shared with
 * manual inspections ({@code INSP}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceGenerationService {

    private static final String DOC_TYPE = "INSP";

    private final ProjectRepository projectRepository;
    private final ComplianceRuleRepository ruleRepository;
    private final ClaudeComplianceService claudeComplianceService;
    private final InspectionRepository inspectionRepository;
    private final EntryNumberGenerator numberGen;
    private final TenantEntityHelper tenantEntityHelper;
    private final InspectionMapper inspectionMapper;

    /**
     * Generates the missing compliance inspections for a project. Returns the DTOs of
     * the rows created by this call (empty when the AI is disabled/unconfigured, the
     * project has no resolvable state or type, no rules match, or everything already
     * exists).
     *
     * @param projectId the project to generate for
     * @param orgId     the owning organization; must match the current tenant context
     */
    @Transactional
    public List<InspectionDto> generateForProject(Long projectId, Long orgId) {
        Project project = projectRepository.findByIdAndOrganization_Id(projectId, orgId).orElse(null);
        if (project == null) {
            log.warn("Compliance generation skipped: project {} not found in organization {}", projectId, orgId);
            return List.of();
        }

        ProjectType projectType = project.getProjectType();
        if (projectType == null) {
            log.warn("Compliance generation skipped for project {}: projectType is not set", projectId);
            return List.of();
        }

        String state = IndianStateResolver.resolve(project.getProjectAddress());
        if (state == null) {
            log.warn("Compliance generation skipped for project {}: could not resolve a state from address '{}'",
                    projectId, project.getProjectAddress());
            return List.of();
        }

        List<ComplianceRule> candidateRules =
                ruleRepository.findByStateIgnoreCaseAndProjectTypeAndActiveTrue(state, projectType);
        if (candidateRules.isEmpty()) {
            log.info("No compliance rules registered for state '{}' and type {} (project {})",
                    state, projectType, projectId);
            return List.of();
        }

        List<ComplianceSuggestion> suggestions =
                claudeComplianceService.suggestCompliances(project, state, candidateRules);
        if (suggestions.isEmpty()) {
            log.info("Compliance AI returned no suggestions for project {}", projectId);
            return List.of();
        }

        Map<String, ComplianceRule> rulesByCode = candidateRules.stream()
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

            if (inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                    projectId, rule.getCode(), orgId)) {
                log.debug("Compliance {} already exists for project {}; skipping", rule.getCode(), projectId);
                continue;
            }

            Inspection inspection = new Inspection();
            inspection.setInspectionNumber(numberGen.next(DOC_TYPE));
            inspection.setTitle(rule.getName());
            inspection.setType(InspectionType.COMPLIANCE);
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
                created.size(), projectId, state, projectType);
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
