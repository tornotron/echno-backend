package org.tornotron.echno_backend.intend;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.IntendDtoConvertor;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.intend.dto.IntendCreationDto;
import org.tornotron.echno_backend.intend.dto.IntendDto;
import org.tornotron.echno_backend.intend.enums.IntendStatus;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.util.List;

@Service
public class IntendService {

    private final IntendRepository intendRepository;
    private final FileStorageService fileStorageService;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final InventoryService inventoryService;

    public IntendService(IntendRepository intendRepository, FileStorageService fileStorageService,
                         TenantEntityHelper tenantEntityHelper, EmployeeRepository employeeRepository,
                         ProjectRepository projectRepository, InventoryService inventoryService) {
        this.intendRepository = intendRepository;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.inventoryService = inventoryService;
    }


    @Transactional
    public IntendDto addIntend(IntendCreationDto intendCreationDto) {
        Intend intend = new Intend();
        Employee employee = employeeRepository.findByIdAndOrganizationId(intendCreationDto.getCreatedByEmployeeId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + intendCreationDto.getCreatedByEmployeeId()));

        Project project = projectRepository.findByIdAndOrganization_Id(intendCreationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + intendCreationDto.getProjectId()));

        intend.setCreatedBy(employee);
        intend.setProject(project);
        intend.setIntendNumber(intendCreationDto.getIntendNumber());
        intend.setStatus(IntendStatus.valueOf(intendCreationDto.getStatus()));
        intend.setExpectedOn(intendCreationDto.getExpectedOn());
        intend.setRemarks(intendCreationDto.getRemarks());
        intend.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        return IntendDtoConvertor.convertIntendToDto(intendRepository.save(intend), fileStorageService,inventoryService);
    }


    @Transactional(readOnly = true)
    public Page<IntendDto> getAllIntends(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return intendRepository.findAll(pageable)
                .map(intend -> IntendDtoConvertor.convertIntendToDto(intend, fileStorageService,inventoryService));
    }


    @Transactional(readOnly = true)
    public List<IntendDto> getAllIntends() {
        return intendRepository.findAll().stream()
                .map(intend -> IntendDtoConvertor.convertIntendToDto(intend, fileStorageService,inventoryService))
                .toList();
    }

    @Transactional(readOnly = true)
    public IntendDto getAnIntend(Long id) {
        return intendRepository.findById(id)
                .map(intend -> IntendDtoConvertor.convertIntendToDto(intend, fileStorageService,inventoryService))
                .orElseThrow(() -> new ResourceNotFoundException("Intend not found with id: " + id));
    }

    @Transactional
    public void deleteIntend(Long id) {
        if(!intendRepository.existsById(id)){
            throw new ResourceNotFoundException("Intend not found with id: " + id);
        }
        intendRepository.deleteById(id);
    }
}
