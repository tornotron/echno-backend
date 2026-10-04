package org.tornotron.echno_backend.subcontract;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.subcontract.dto.ContractMilestoneDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractCreationDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractDto;
import org.tornotron.echno_backend.subcontract.mapper.SubContractMapper;
import org.tornotron.echno_backend.subcontract.enums.SubContractPaymentTerms;
import org.tornotron.echno_backend.subcontract.enums.SubContractStatus;
import org.tornotron.echno_backend.subcontract.enums.SubContractType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CRUD + list for subcontracts. The subcontract is a header plus a list of
 * milestones; each milestone carries its own organization so tenant scoping
 * applies to the child rows directly.
 *
 * <p>An update keeps a milestone that the request names by id, so its id survives an edit of the
 * contract: bills and requirements recorded against it stay attached. A milestone left out of the
 * request is removed, unless a module still holds records against it ({@link SubContractRecords});
 * the same check guards deleting the whole contract.
 */
@Service
public class SubContractService {

    private final SubContractRepository subContractRepository;
    private final SubContractMapper subContractMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final ObjectProvider<SubContractRecords> contractRecords;

    public SubContractService(SubContractRepository subContractRepository,
                              SubContractMapper subContractMapper,
                              TenantEntityHelper tenantEntityHelper,
                              ObjectProvider<SubContractRecords> contractRecords) {
        this.subContractRepository = subContractRepository;
        this.subContractMapper = subContractMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.contractRecords = contractRecords;
    }

    @Transactional
    public SubContractDto create(SubContractCreationDto creationDto) {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        SubContract subContract = new SubContract();
        subContract.setOrganization(organization);
        applyHeaderFields(subContract, creationDto);
        applyMilestones(subContract, creationDto.getMilestones(), organization);
        SubContract saved = subContractRepository.saveAndFlush(subContract);
        return subContractMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public SubContractDto getById(Long id) {
        SubContract subContract = subContractRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subcontract with ID " + id + " was not found in this organization"));
        return subContractMapper.toDto(subContract);
    }


    @Transactional(readOnly = true)
    public Page<SubContractDto> getPaginated(int pageNo, int pageSize, String search, String status, String type) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return subContractRepository.search(searchPattern(search), blankToNull(status), blankToNull(type), pageable)
                .map(subContractMapper::toDto);
    }

    @Transactional
    public SubContractDto update(Long id, SubContractCreationDto creationDto) {
        SubContract subContract = subContractRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subcontract with ID " + id + " was not found in this organization"));
        Organization organization = subContract.getOrganization();

        applyHeaderFields(subContract, creationDto);

        // Merge the milestones by id: a named milestone is updated in place so its id, and what
        // is recorded against it, survives the edit; an unnamed one is added; a missing one is
        // removed (orphanRemoval) once no module holds records against it.
        mergeMilestones(subContract, creationDto.getMilestones(), organization);

        // saveAndFlush before mapping so the freshly inserted milestone ids are populated
        // on the returned DTO (without the flush the child ids are null).
        SubContract saved = subContractRepository.saveAndFlush(subContract);
        return subContractMapper.toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        SubContract subContract = subContractRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subcontract with ID " + id + " was not found in this organization"));
        for (SubContractRecords records : contractRecords.orderedStream().toList()) {
            String held = records.recordsHeldAgainstContract(id);
            if (held != null) {
                throw new InvalidRequestException("Subcontract " + id + " has " + held
                        + " recorded against it, so it cannot be deleted");
            }
        }
        subContractRepository.delete(subContract);
    }

    /**
     * Copies the header scalars from the creation DTO. Project/supervisor/account-manager stay
     * plain ids. Type, status and payment terms are held to their vocabularies (#863): they were
     * stored as sent, so the form and the list badge drifted onto different sets of values.
     */
    private void applyHeaderFields(SubContract subContract, SubContractCreationDto dto) {
        subContract.setContractId(dto.getContractId());
        subContract.setContractName(dto.getContractName());
        subContract.setWorkDescription(dto.getWorkDescription());
        subContract.setScopeOfWork(dto.getScopeOfWork());

        subContract.setContractorName(dto.getContractorName());
        subContract.setContractorContactPerson(dto.getContractorContactPerson());
        subContract.setContractorPhone(dto.getContractorPhone());
        subContract.setContractorEmail(dto.getContractorEmail());
        subContract.setContractorAddress(dto.getContractorAddress());
        subContract.setContractorGst(dto.getContractorGst());
        subContract.setContractorPan(dto.getContractorPan());
        subContract.setContractorLicense(dto.getContractorLicense());

        subContract.setType(SubContractType.requireValid("type", dto.getType()));
        subContract.setStatus(SubContractStatus.requireValid("status", dto.getStatus()));

        subContract.setContractValue(dto.getContractValue());
        if (dto.getCurrency() != null) {
            subContract.setCurrency(dto.getCurrency());
        }
        subContract.setMobilizationAdvance(dto.getMobilizationAdvance());
        subContract.setRetentionPercentage(dto.getRetentionPercentage());
        subContract.setTotalPaid(dto.getTotalPaid());
        subContract.setTotalDue(dto.getTotalDue());
        subContract.setPaymentTerms(SubContractPaymentTerms.requireValid("paymentTerms", dto.getPaymentTerms()));

        subContract.setStartDate(dto.getStartDate());
        subContract.setEndDate(dto.getEndDate());
        subContract.setActualCompletionDate(dto.getActualCompletionDate());
        subContract.setCompletionPercentage(dto.getCompletionPercentage());

        subContract.setProjectId(dto.getProjectId());
        subContract.setProjectName(dto.getProjectName());

        subContract.setQualityRating(dto.getQualityRating());
        subContract.setTimelinessRating(dto.getTimelinessRating());
        subContract.setSafetyRating(dto.getSafetyRating());
        subContract.setOverallRating(dto.getOverallRating());

        subContract.setInsuranceProvider(dto.getInsuranceProvider());
        subContract.setInsurancePolicyNumber(dto.getInsurancePolicyNumber());
        subContract.setInsuranceExpiry(dto.getInsuranceExpiry());

        subContract.setBankName(dto.getBankName());
        subContract.setBankAccountNumber(dto.getBankAccountNumber());
        subContract.setBankIfsc(dto.getBankIfsc());

        subContract.setSupervisorId(dto.getSupervisorId());
        subContract.setAccountManagerId(dto.getAccountManagerId());

        subContract.setPenaltyClause(dto.getPenaltyClause());
        subContract.setWarrantyPeriod(dto.getWarrantyPeriod());
        subContract.setNotes(dto.getNotes());
    }

    private void mergeMilestones(SubContract subContract,
                                 List<ContractMilestoneDto> milestoneDtos,
                                 Organization organization) {
        List<ContractMilestoneDto> incoming = milestoneDtos == null ? List.of() : milestoneDtos;
        Map<Long, ContractMilestone> existing = new HashMap<>();
        for (ContractMilestone milestone : subContract.getMilestones()) {
            existing.put(milestone.getId(), milestone);
        }
        Set<Long> kept = new HashSet<>();
        for (ContractMilestoneDto dto : incoming) {
            if (dto.getId() == null) {
                continue;
            }
            if (!existing.containsKey(dto.getId())) {
                throw new InvalidRequestException("Milestone " + dto.getId() + " is not part of subcontract "
                        + subContract.getId());
            }
            if (!kept.add(dto.getId())) {
                throw new InvalidRequestException("Milestone " + dto.getId() + " is listed twice");
            }
        }
        List<Long> removed = existing.keySet().stream().filter(id -> !kept.contains(id)).toList();
        if (!removed.isEmpty()) {
            for (SubContractRecords records : contractRecords.orderedStream().toList()) {
                String held = records.recordsHeldAgainstMilestones(removed);
                if (held != null) {
                    throw new InvalidRequestException("A milestone removed from this subcontract has " + held
                            + " recorded against it. Keep the milestone, or cancel those first");
                }
            }
            subContract.getMilestones().removeIf(milestone -> removed.contains(milestone.getId()));
        }
        for (ContractMilestoneDto dto : incoming) {
            if (dto.getId() != null) {
                copyMilestoneFields(Objects.requireNonNull(existing.get(dto.getId())), dto);
            } else {
                ContractMilestone milestone = new ContractMilestone();
                copyMilestoneFields(milestone, dto);
                milestone.setOrganization(organization);
                subContract.addMilestone(milestone);
            }
        }
    }

    private static void copyMilestoneFields(ContractMilestone milestone, ContractMilestoneDto dto) {
        milestone.setName(dto.getName());
        milestone.setDescription(dto.getDescription());
        milestone.setTargetDate(dto.getTargetDate());
        milestone.setCompletionDate(dto.getCompletionDate());
        milestone.setPaymentPercentage(dto.getPaymentPercentage());
        milestone.setAmount(dto.getAmount());
        milestone.setStatus(dto.getStatus());
    }

    /** Builds and attaches the milestones, wiring each child's back-reference and organization. */
    private void applyMilestones(SubContract subContract,
                                 List<ContractMilestoneDto> milestoneDtos,
                                 Organization organization) {
        if (milestoneDtos == null) {
            return;
        }
        for (ContractMilestoneDto milestoneDto : milestoneDtos) {
            ContractMilestone milestone = new ContractMilestone();
            copyMilestoneFields(milestone, milestoneDto);
            milestone.setOrganization(organization);
            subContract.addMilestone(milestone);
        }
    }

    /**
     * Builds a lower-cased {@code %...%} LIKE pattern for the search term, or null
     * when blank. The pattern is assembled here rather than with SQL {@code CONCAT}
     * so no null bind lands inside a {@code ||}, which CockroachDB mistypes as bytes.
     */
    private static String searchPattern(String value) {
        return (value == null || value.isBlank()) ? null : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
