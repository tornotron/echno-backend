package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;

import java.time.Duration;
import java.util.stream.Collectors;

@Component
public class OrganizationDtoConvertor {

    private static EmployeeDto convertEmployeeToEmployeeDto(Employee employee) {
        EmployeeDto employeeDto = new EmployeeDto();
        employeeDto.setId(employee.getId());
        employeeDto.setEmployeeId(employee.getEmployeeId());
        employeeDto.setEmployeeName(employee.getEmployeeName());
        employeeDto.setDesignation(employee.getDesignation());
        employeeDto.setDepartment(employee.getDepartment());
        employeeDto.setJoiningDate(employee.getJoiningDate());
        if (employee.getManager() != null) {
            employeeDto.setManagerId(employee.getManager().getId());
            employeeDto.setManagerName(employee.getManager().getEmployeeName());
        }
        employeeDto.setShiftTiming(employee.getShiftTiming());
        employeeDto.setStatus(employee.getStatus());
        employeeDto.setSalary(employee.getSalary());
        employeeDto.setGender(employee.getGender());
        employeeDto.setAddress(employee.getAddress());
        employeeDto.setPhoneNumber(employee.getPhoneNumber());
        employeeDto.setEmailAddress(employee.getEmailAddress());
        employeeDto.setDateOfBirth(employee.getDateOfBirth());
        employeeDto.setBloodGroup(employee.getUser().getBloodGroup());
        employeeDto.setQualification(employee.getUser().getQualification());
        employeeDto.setSkills(employee.getUser().getSkills());
        employeeDto.setExperience(employee.getUser().getExperience());
        employeeDto.setCvUrl(employee.getUser().getCvUrl());
        employeeDto.setEmergencyContact(employee.getUser().getEmergencyContact());
        employeeDto.setRole(employee.getUser().getRole());
        employeeDto.setOrgRoles(employee.getOrgRoles());
        employeeDto.setProfilePictureUrl(employee.getUser().getProfilePictureUrl());
        employeeDto.setCreatedAt(employee.getUser().getCreatedAt());
        employeeDto.setUpdatedAt(employee.getUser().getUpdatedAt());

        return employeeDto;
    }


    public static AttachmentDto convertAttachmentToDto(Attachment attachment, FileStorageService fileStorageService) {
        AttachmentDto dto = new AttachmentDto();
        dto.setId(attachment.getId());
        dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
        dto.setEntityType(attachment.getEntityType());
        dto.setContentType(attachment.getContentType());
        dto.setFileSize(attachment.getFileSize());
        dto.setFileName(attachment.getOriginalFilename());
        dto.setCreatedAt(attachment.getCreatedAt().toString());
        dto.setUpdatedAt(attachment.getUpdatedAt().toString());
        return dto;
    }

    public static OrganizationSimpleDto convertOrganizationToSimpleDto(Organization organization) {
        OrganizationSimpleDto dto = new OrganizationSimpleDto();
        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        dto.setOrganizationAddress(organization.getOrganizationAddress());
        dto.setOrganizationEmail(organization.getOrganizationEmail());
        dto.setOrganizationPhone(organization.getOrganizationPhone());
        dto.setOrganizationWebsite(organization.getOrganizationWebsite());
        dto.setOrganizationLogo(organization.getOrganizationLogo());
        dto.setCreatedAt(organization.getCreatedAt());
        dto.setIsActive(organization.getIsActive());
        dto.setCreatorId(organization.getCreatorId());
        return dto;
    }


    public static OrganizationDto convertOrganizationToDto(Organization organization, FileStorageService fileStorageService) {
        OrganizationDto dto = new OrganizationDto();

        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        dto.setOrganizationAddress(organization.getOrganizationAddress());
        dto.setOrganizationEmail(organization.getOrganizationEmail());
        dto.setOrganizationPhone(organization.getOrganizationPhone());
        dto.setOrganizationWebsite(organization.getOrganizationWebsite());
        dto.setOrganizationLogo(organization.getOrganizationLogo());
        dto.setCreatedAt(organization.getCreatedAt());
        dto.setEmployees(organization.getEmployees().stream()
                .map(employee -> EmployeeDtoConvertor.convertEmployeeToDto(employee,fileStorageService))
                .collect(Collectors.toList()));
        dto.setProjects(organization.getProjects().stream()
                .map(project -> ProjectDtoConvertor.convertProjectToDto(project,fileStorageService))
                .collect(Collectors.toList()));
        dto.setAttachments(organization.getAttachments().stream()
                .map(attachment -> convertAttachmentToDto(attachment, fileStorageService))
                .collect(Collectors.toList()));
        dto.setIsActive(organization.getIsActive());
        dto.setCreatorId(organization.getCreatorId());
        return dto;

    }

}
