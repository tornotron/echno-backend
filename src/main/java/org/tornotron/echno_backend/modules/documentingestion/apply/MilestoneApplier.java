package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.milestone.MilestoneTables;
import org.tornotron.echno_backend.modules.documentingestion.extraction.values.TenderValues;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.SubContractRepository;
import org.tornotron.echno_backend.subcontract.SubContractService;
import org.tornotron.echno_backend.subcontract.dto.ContractMilestoneDto;
import org.tornotron.echno_backend.wbs.WbsAccess;
import org.tornotron.echno_backend.wbs.WbsElementService;
import org.tornotron.echno_backend.wbs.dto.WbsElementCreationDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementDto;

/**
 * Writes accepted milestones (design section 4.6): each becomes a schedule milestone on the package's
 * project ({@code WbsElement} with {@code isMilestone}), through the WBS service, and, when the package
 * names a sub-contract, a payment milestone on it that a Milestone bill can use. A date printed as a time
 * from the start ("Month 4") is counted from the project's start date; with no start date the date stays
 * empty, as the candidate's flag said, and the text as printed goes in the description.
 */
@Component
@PreAuthorize("isAuthenticated()")
public class MilestoneApplier implements IngestionApplier {

    public static final String TARGET_WBS = "WBS_ELEMENT";
    public static final String TARGET_CONTRACT_MILESTONE = "CONTRACT_MILESTONE";
    /**
     * The WBS create guard, which is also the sub-contract update guard that writes a contract's
     * milestones; one string, so the applier asks what both endpoints ask.
     */
    public static final String WRITE_GUARD = WbsAccess.MANAGE_GUARD;
    static final int TITLE_MAX = 255;

    private final IngestPackageRepository packages;
    private final ProjectRepository projects;
    private final SubContractRepository subContracts;
    private final WbsElementService wbs;
    private final SubContractService contracts;

    public MilestoneApplier(IngestPackageRepository packages, ProjectRepository projects,
                            SubContractRepository subContracts, WbsElementService wbs, SubContractService contracts) {
        this.packages = packages;
        this.projects = projects;
        this.subContracts = subContracts;
        this.wbs = wbs;
        this.contracts = contracts;
    }

    // Metadata read while the context starts, with no one signed in; every write keeps the class guard.
    @Override
    @PreAuthorize("permitAll()")
    public CandidateKind kind() {
        return CandidateKind.MILESTONE;
    }

    @Override
    @PreAuthorize("permitAll()")
    public String targetGuard() {
        return WRITE_GUARD;
    }

    @Override
    public void preflight(UUID packageId) {
        IngestPackage p = requirePackage(packageId);
        if (p.getProjectId() == null) {
            throw new InvalidRequestException("The package has no project yet. Apply the project fields first, or "
                    + "create the package on a project.");
        }
        requireProject(p.getProjectId());
        if (p.getSubContractId() != null) {
            SubContract contract = subContracts.findByIdAndOrganization_Id(p.getSubContractId(),
                    TenantContext.getCurrentOrgId()).orElseThrow(() -> new ResourceNotFoundException(
                    "The package's sub-contract " + p.getSubContractId() + " no longer exists"));
            // The sub-contract can be picked before the project is made from the package, so check it here.
            if (!p.getProjectId().equals(contract.getProjectId())) {
                throw new InvalidRequestException("Sub-contract " + contract.getContractName() + " is not on the package's project, "
                        + "so no milestone is written. Pick a sub-contract of the package's project.");
            }
        }
    }

    @Override
    public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
        IngestPackage p = requirePackage(candidate.getPackageId());
        if (p.getProjectId() == null) {
            throw new InvalidRequestException("The package has no project to write the milestone to");
        }
        Project project = requireProject(p.getProjectId());
        String name = text(accepted.get(MilestoneTables.NAME));
        if (name == null) {
            throw new InvalidRequestException("The milestone has no name");
        }
        String time = text(accepted.get(MilestoneTables.TIME));
        LocalDate target = targetDate(time, project).orElse(null);
        BigDecimal percent = Optional.ofNullable(text(accepted.get(MilestoneTables.PERCENT)))
                .map(t -> TenderValues.percentOrNumber(t).orElseThrow(() -> new InvalidRequestException(
                        "The percentage \"" + t + "\" is not a number. Correct it in the review first.")))
                .orElse(null);
        BigDecimal amount = Optional.ofNullable(text(accepted.get(MilestoneTables.AMOUNT)))
                .map(t -> TenderValues.amount(t).orElseThrow(() -> new InvalidRequestException(
                        "The amount \"" + t + "\" is not a number. Correct it in the review first.")))
                .orElse(null);
        String description = time == null ? null : "As printed: " + time;

        WbsElementCreationDto dto = new WbsElementCreationDto();
        dto.setWbsCode("MS-" + candidate.getId().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
        dto.setTitle(name.length() <= TITLE_MAX ? name : name.substring(0, TITLE_MAX));
        dto.setDescription(description);
        dto.setIsMilestone(true);
        dto.setStartDate(target);
        dto.setEndDate(target);
        dto.setSortOrder(order(accepted));
        WbsElementDto element = wbs.createWbsElement(project.getId(), dto);
        Map<String, String> wbsFields = new LinkedHashMap<>();
        wbsFields.put(MilestoneTables.NAME, "title");
        if (time != null) {
            wbsFields.put(MilestoneTables.TIME, "endDate");
        }

        List<AppliedTarget> also = List.of();
        if (p.getSubContractId() != null) {
            ContractMilestoneDto m = new ContractMilestoneDto();
            m.setName(dto.getTitle());
            m.setDescription(description);
            m.setTargetDate(target);
            m.setPaymentPercentage(percent);
            m.setAmount(amount);
            Long milestoneId = contracts.addMilestone(p.getSubContractId(), m);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(MilestoneTables.NAME, "name");
            if (time != null) {
                fields.put(MilestoneTables.TIME, "targetDate");
            }
            if (percent != null) {
                fields.put(MilestoneTables.PERCENT, "paymentPercentage");
            }
            if (amount != null) {
                fields.put(MilestoneTables.AMOUNT, "amount");
            }
            also = List.of(new AppliedTarget(TARGET_CONTRACT_MILESTONE, milestoneId.toString(), fields));
        }
        return new AppliedTarget(TARGET_WBS, element.getId().toString(), wbsFields, also);
    }

    /**
     * A printed date, or a time from the project's start counted from its start date; else empty, as for
     * a time counted from another event ("3 months from the date of LOA") whose date is not known here.
     */
    static Optional<LocalDate> targetDate(String time, Project project) {
        if (time == null) {
            return Optional.empty();
        }
        Optional<LocalDate> date = TenderValues.date(time);
        if (date.isPresent()) {
            return date;
        }
        if (project.getStartDate() == null) {
            return Optional.empty();
        }
        return TenderValues.spanFromStart(time).map(s -> s.from(project.getStartDate().toLocalDate()));
    }

    private static Integer order(Map<String, Object> payload) {
        return payload.get(MilestoneTables.ORDER) instanceof Number n ? n.intValue() : null;
    }

    private IngestPackage requirePackage(UUID packageId) {
        return packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
    }

    private Project requireProject(Long projectId) {
        return projects.findByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("The package's project " + projectId + " no longer exists"));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
