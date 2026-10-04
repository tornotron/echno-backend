package org.tornotron.echno_backend.risk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.risk.dto.RiskDto;
import org.tornotron.echno_backend.risk.dto.RiskImportRequest;
import org.tornotron.echno_backend.risk.dto.RiskRequest;

/**
 * A project's risk register: list, record, change, remove, and the one-time import of risks a
 * browser held before the register was kept here.
 *
 * <p>Every call resolves the project inside the caller's organization first, so a project id from
 * another tenant answers 404 the same as one that does not exist.
 *
 * <p>Creates and imports issue R-numbers as one past the highest the project holds. Two people
 * saving at the same moment would read the same highest number; the unique index on
 * {@code (project_id, risk_number)} turns the second insert into a unique violation, and the
 * whole unit of work is run again through {@link TransactionRetryTemplate}, which then reads the
 * winner's number. So these two methods are not {@code @Transactional}: the transaction belongs
 * to the template, one per attempt.
 */
@Service
@RequiredArgsConstructor
public class ProjectRiskService {

    private final ProjectRiskRepository riskRepository;
    private final ProjectRepository projectRepository;
    private final TransactionRetryTemplate retryTemplate;

    /** The project's register in R-number order. */
    @Transactional(readOnly = true)
    public List<RiskDto> list(Long projectId) {
        requireProject(projectId);
        return riskRepository
                .findByProjectIdAndOrganization_IdOrderByRiskNumberAsc(projectId, TenantContext.getCurrentOrgId())
                .stream()
                .map(RiskDto::from)
                .toList();
    }

    /** One risk of the project. */
    @Transactional(readOnly = true)
    public RiskDto get(Long projectId, UUID riskId) {
        requireProject(projectId);
        return RiskDto.from(requireRisk(projectId, riskId));
    }

    /** Records a risk with the project's next R-number. */
    public RiskDto create(Long projectId, RiskRequest request) {
        return retryTemplate.execute("ProjectRiskService.create",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> {
                    Project project = requireProject(projectId);
                    int next = riskRepository.findMaxRiskNumber(projectId, TenantContext.getCurrentOrgId()) + 1;
                    ProjectRisk risk = newRisk(project, next);
                    apply(risk, request);
                    return RiskDto.from(riskRepository.saveAndFlush(risk));
                });
    }

    /**
     * Imports risks in the order given, each with the next R-number. A risk whose import reference
     * the project already holds, or that appears twice in the request, is skipped, so the same
     * browser's register imported twice is added once.
     *
     * @return The risks added by this call, which is fewer than were sent when some were skipped.
     */
    public List<RiskDto> importRisks(Long projectId, RiskImportRequest request) {
        return retryTemplate.execute("ProjectRiskService.importRisks",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> {
                    Project project = requireProject(projectId);
                    Long organizationId = TenantContext.getCurrentOrgId();
                    List<String> refs = request.risks().stream()
                            .map(RiskRequest::importRef)
                            .filter(Objects::nonNull)
                            .toList();
                    Set<String> seen = new HashSet<>(refs.isEmpty()
                            ? List.of()
                            : riskRepository.findExistingImportRefs(projectId, organizationId, refs));
                    int next = riskRepository.findMaxRiskNumber(projectId, organizationId);
                    List<ProjectRisk> added = new ArrayList<>();
                    for (RiskRequest line : request.risks()) {
                        String ref = line.importRef() == null || line.importRef().isBlank()
                                ? null : line.importRef().trim();
                        if (ref != null && !seen.add(ref)) {
                            continue;
                        }
                        ProjectRisk risk = newRisk(project, ++next);
                        apply(risk, line);
                        risk.setImportRef(ref);
                        added.add(risk);
                    }
                    List<ProjectRisk> saved = riskRepository.saveAllAndFlush(added);
                    return saved.stream().map(RiskDto::from).toList();
                });
    }

    /**
     * Replaces the editable fields of a risk. When the request names the version the editor
     * started from and it is no longer current, someone else saved in between and the update is
     * refused rather than written over theirs.
     */
    @Transactional
    public RiskDto update(Long projectId, UUID riskId, RiskRequest request) {
        requireProject(projectId);
        ProjectRisk risk = requireRisk(projectId, riskId);
        if (request.version() != null && !request.version().equals(risk.getVersion())) {
            throw new ErrorResponseException(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "This risk was changed by someone else after you opened it. Reload it and make your change again."),
                    null);
        }
        apply(risk, request);
        return RiskDto.from(riskRepository.saveAndFlush(risk));
    }

    /** Removes a risk. Its R-number is not reissued while a higher one exists. */
    @Transactional
    public void delete(Long projectId, UUID riskId) {
        requireProject(projectId);
        riskRepository.delete(requireRisk(projectId, riskId));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + projectId + " was not found in this organization"));
    }

    private ProjectRisk requireRisk(Long projectId, UUID riskId) {
        return riskRepository.findByIdAndProjectIdAndOrganization_Id(riskId, projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Risk " + riskId + " was not found on project " + projectId));
    }

    private static ProjectRisk newRisk(Project project, int number) {
        ProjectRisk risk = new ProjectRisk();
        risk.setOrganization(project.getOrganization());
        risk.setProjectId(project.getId());
        risk.setRiskNumber(number);
        return risk;
    }

    private static void apply(ProjectRisk risk, RiskRequest request) {
        risk.setTitle(request.title().trim());
        risk.setDescription(blankToNull(request.description()));
        risk.setCategory(request.category());
        risk.setSubCategory(blankToNull(request.subCategory()));
        risk.setStatus(request.status());
        risk.setOwner(blankToNull(request.owner()));
        risk.setProbability(request.probability());
        risk.setImpact(request.impact());
        risk.setRiskScore(RiskScale.score(request.probability(), request.impact()));
        risk.setResidualProbability(request.residualProbability());
        risk.setResidualImpact(request.residualImpact());
        risk.setResidualScore(RiskScale.score(request.residualProbability(), request.residualImpact()));
        risk.setResponseType(request.responseType());
        risk.setContingencyPlan(blankToNull(request.contingencyPlan()));
        risk.setIdentifiedDate(request.identifiedDate());
        risk.setReviewDate(request.reviewDate());
        risk.setClosedDate(request.closedDate());
        risk.setCostImpact(request.costImpact());
        risk.setScheduleImpactDays(request.scheduleImpact());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
