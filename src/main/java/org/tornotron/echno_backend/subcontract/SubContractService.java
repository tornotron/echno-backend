package org.tornotron.echno_backend.subcontract;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.subcontract.dto.ContractMilestoneDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractCreationDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractDto;
import org.tornotron.echno_backend.subcontract.mapper.SubContractMapper;

import java.util.List;

/**
 * CRUD + list for subcontracts. The subcontract is a header plus a list of
 * milestones; each milestone carries its own organization so tenant scoping
 * applies to the child rows directly.
 */
@Service
public class SubContractService {

    private final SubContractRepository subContractRepository;
    private final SubContractMapper subContractMapper;
    private final TenantEntityHelper tenantEntityHelper;

    public SubContractService(SubContractRepository subContractRepository,
                              SubContractMapper subContractMapper,
                              TenantEntityHelper tenantEntityHelper) {
        this.subContractRepository = subContractRepository;
        this.subContractMapper = subContractMapper;
        this.tenantEntityHelper = tenantEntityHelper;
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

        // Replace the milestone collection: clear in place (orphanRemoval deletes the old
        // rows) and re-add from the request, keeping Hibernate's collection tracking intact.
        subContract.getMilestones().clear();
        applyMilestones(subContract, creationDto.getMilestones(), organization);

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
        subContractRepository.delete(subContract);
    }

    /** Copies the header scalars from the creation DTO. Project/supervisor/account-manager stay plain ids. */
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

        subContract.setType(dto.getType());
        subContract.setStatus(dto.getStatus());

        subContract.setContractValue(dto.getContractValue());
        if (dto.getCurrency() != null) {
            subContract.setCurrency(dto.getCurrency());
        }
        subContract.setMobilizationAdvance(dto.getMobilizationAdvance());
        subContract.setRetentionPercentage(dto.getRetentionPercentage());
        subContract.setTotalPaid(dto.getTotalPaid());
        subContract.setTotalDue(dto.getTotalDue());
        subContract.setPaymentTerms(dto.getPaymentTerms());

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

    /** Builds and attaches the milestones, wiring each child's back-reference and organization. */
    private void applyMilestones(SubContract subContract,
                                 List<ContractMilestoneDto> milestoneDtos,
                                 Organization organization) {
        if (milestoneDtos == null) {
            return;
        }
        for (ContractMilestoneDto milestoneDto : milestoneDtos) {
            ContractMilestone milestone = new ContractMilestone();
            milestone.setName(milestoneDto.getName());
            milestone.setDescription(milestoneDto.getDescription());
            milestone.setTargetDate(milestoneDto.getTargetDate());
            milestone.setCompletionDate(milestoneDto.getCompletionDate());
            milestone.setPaymentPercentage(milestoneDto.getPaymentPercentage());
            milestone.setAmount(milestoneDto.getAmount());
            milestone.setStatus(milestoneDto.getStatus());
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
        return (value == null || value.isBlank()) ? null : "%" + value.trim().toLowerCase() + "%";
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
