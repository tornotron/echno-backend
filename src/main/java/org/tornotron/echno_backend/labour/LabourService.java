package org.tornotron.echno_backend.labour;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.labour.mapper.LabourMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.labour.dto.LabourCreationDto;
import org.tornotron.echno_backend.labour.dto.LabourDto;
import org.tornotron.echno_backend.labour.dto.LabourSimpleDto;
import org.tornotron.echno_backend.labour.dto.LabourUpdateDto;
import org.tornotron.echno_backend.labour.enums.EmploymentType;
import org.tornotron.echno_backend.labour.enums.SkillLevel;
import org.tornotron.echno_backend.labour.enums.Status;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;

/**
 * CRUD for on-site labour records, scoped to the current organization.
 *
 * <p>Creating or updating a worker resolves and attaches the current project as their active
 * assignment. Updates are partial: only the fields supplied on the update DTO are applied.
 * Lookups are constrained to the caller's organization.
 */
@Service
public class LabourService {

    private final LabourRepository labourRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final ProjectRepository projectRepository;
    private final LabourMapper labourMapper;

    public LabourService(LabourRepository labourRepository, TenantEntityHelper tenantEntityHelper, ProjectRepository projectRepository, LabourMapper labourMapper) {
        this.labourRepository = labourRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.projectRepository = projectRepository;
        this.labourMapper = labourMapper;
    }

    /**
     * Creates a labour record and assigns it to the given current project.
     *
     * @param labourCreationDto The worker's identity, contact, employment, and pay details, plus the current project ID.
     * @return The created labour as a simple DTO.
     * @throws ResourceNotFoundException if the referenced current project cannot be found in the organization.
     */
    @Transactional
    public LabourSimpleDto createLabour(LabourCreationDto labourCreationDto) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        Labour labour = new Labour();
        labour.setLabourID(labourCreationDto.getLabourID());
        labour.setOrganization(org);
        labour.setFullName(labourCreationDto.getFullName());
        labour.setEmail(labourCreationDto.getEmail());
        labour.setAddress(labourCreationDto.getAddress());
        labour.setPhoneNumber(labourCreationDto.getPhoneNumber());
        labour.setEmergencyContactName(labourCreationDto.getEmergencyContactName());
        labour.setEmergencyContactNumber(labourCreationDto.getEmergencyContactPhone());
        labour.setSpecialization(labourCreationDto.getSpecialization());
        labour.setEmploymentType(EmploymentType.valueOf(labourCreationDto.getEmploymentType()));
        labour.setSkillLevel(SkillLevel.valueOf(labourCreationDto.getSkillLevel()));
        labour.setStatus(Status.valueOf(labourCreationDto.getStatus()));
        labour.setJoiningDate(labourCreationDto.getJoiningDate());
        labour.setCurrentProject(projectRepository.findByIdAndOrganization_Id(labourCreationDto.getCurrentProjectId(), org.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + labourCreationDto.getCurrentProjectId() + " was not found in this organization")));

        labour.setDailyRate(labourCreationDto.getDailyRate());
        labour.setOverTimeRate(labourCreationDto.getOverTimeRate());
        labour.setBankAccountNumber(labourCreationDto.getBankAccountNumber());
        labour.setBankName(labourCreationDto.getBankName());
        labour.setIfscCode(labourCreationDto.getIfscCode());
        labour.setAdditionalNotes(labourCreationDto.getAdditionalNotes());
        return labourMapper.toSimpleDto(labourRepository.save(labour));
    }

    /**
     * Returns a page of labour records ordered by ID.
     *
     * @param pageNo The zero-based page index.
     * @param pageSize The number of records per page.
     * @return A page of labour DTOs sorted ascending by ID.
     */
    @Transactional(readOnly = true)
    public Page<LabourDto> getAllLabours(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return labourRepository.findAll(pageable)
                .map(labourMapper::toDto);
    }

    /**
     * Retrieves a single labour record by its ID within the current organization.
     *
     * @param id The ID of the labour record to retrieve.
     * @return The labour DTO.
     * @throws ResourceNotFoundException if no labour record with the given ID exists in the organization.
     */
    @Transactional(readOnly = true)
    public LabourDto getALabour(Long id) {
        return labourRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .map(labourMapper::toDto).
                orElseThrow(() -> new ResourceNotFoundException("Labour with ID " + id + " was not found in this organization"));
    }

    /**
     * Applies a partial update to a labour record, changing only the fields present in the DTO.
     *
     * <p>If a new current project ID is supplied, it is resolved within the organization and set
     * as the worker's assignment.
     *
     * @param updates The fields to change; null fields are left untouched.
     * @param id The ID of the labour record to update.
     * @throws ResourceNotFoundException if the labour record or a referenced new current project cannot be found in the organization.
     */
    @Transactional
    public void partialUpdateALabour(LabourUpdateDto updates, Long id) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        Labour labour = labourRepository.findByIdAndOrganization_Id(id, org.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Labour with ID " + id + " was not found in this organization"));
        applyUpdates(updates, labour, org);
        labourRepository.save(labour);
    }

    private void applyUpdates(LabourUpdateDto updates, Labour labour, Organization org) {
        if (updates.getLabourID() != null) labour.setLabourID(updates.getLabourID());
        if (updates.getFullName() != null) labour.setFullName(updates.getFullName());
        if (updates.getEmail() != null) labour.setEmail(updates.getEmail());
        if (updates.getAddress() != null) labour.setAddress(updates.getAddress());
        if (updates.getPhoneNumber() != null) labour.setPhoneNumber(updates.getPhoneNumber());
        if (updates.getEmergencyContactName() != null) labour.setEmergencyContactName(updates.getEmergencyContactName());
        if (updates.getEmergencyContactPhone() != null) labour.setEmergencyContactNumber(updates.getEmergencyContactPhone());
        if (updates.getSpecialization() != null) labour.setSpecialization(updates.getSpecialization());
        if (updates.getEmploymentType() != null) labour.setEmploymentType(updates.getEmploymentType());
        if (updates.getSkillLevel() != null) labour.setSkillLevel(updates.getSkillLevel());
        if (updates.getStatus() != null) labour.setStatus(updates.getStatus());
        if (updates.getJoiningDate() != null) labour.setJoiningDate(updates.getJoiningDate());
        if (updates.getDailyRate() != null) labour.setDailyRate(updates.getDailyRate());
        if (updates.getOverTimeRate() != null) labour.setOverTimeRate(updates.getOverTimeRate());
        if (updates.getBankAccountNumber() != null) labour.setBankAccountNumber(updates.getBankAccountNumber());
        if (updates.getBankName() != null) labour.setBankName(updates.getBankName());
        if (updates.getIfscCode() != null) labour.setIfscCode(updates.getIfscCode());
        if (updates.getAdditionalNotes() != null) labour.setAdditionalNotes(updates.getAdditionalNotes());
        if (updates.getCurrentProjectId() != null) {
            Long projectId = updates.getCurrentProjectId();
            labour.setCurrentProject(projectRepository.findByIdAndOrganization_Id(projectId, org.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization")));
        }
    }

    /**
     * Deletes a labour record within the current organization.
     *
     * @param id The ID of the labour record to delete.
     * @throws ResourceNotFoundException if no labour record with the given ID exists in the organization.
     */
    @Transactional
    public void deleteALabour(Long id) {
        Labour labour = labourRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Labour with ID " + id + " was not found in this organization"));
        labourRepository.delete(labour);
    }

}
