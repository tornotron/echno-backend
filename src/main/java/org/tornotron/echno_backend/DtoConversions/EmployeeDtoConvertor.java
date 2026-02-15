package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

import java.time.Duration;
import java.util.stream.Collectors;

@Component
public class EmployeeDtoConvertor {

    private static AttachmentDto convertAttachmentToDto(Attachment attachment, FileStorageService fileStorageService) {
        AttachmentDto dto = new AttachmentDto();
        dto.setId(attachment.getId());
        dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
        dto.setEntityType(attachment.getEntityType());
        dto.setContentType(attachment.getContentType());
        dto.setFileName(attachment.getOriginalFilename());
        dto.setFileSize(attachment.getFileSize());
        dto.setCreatedAt(attachment.getCreatedAt().toString());
        dto.setUpdatedAt(attachment.getUpdatedAt().toString());
        return dto;
    }

    public static EmployeeDto convertEmployeeToDto(Employee employee,FileStorageService fileStorageService) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(employee.getId());
        dto.setOrganizationId(employee.getOrganization().getId());
        dto.setOrganizationName(employee.getOrganization().getOrganizationName());
        dto.setSalary(employee.getSalary());
        dto.setEmployeeName(employee.getEmployeeName());
        dto.setAddress(employee.getUser().getAddress());
        dto.setDesignation(employee.getDesignation());
        dto.setDepartment(employee.getDepartment());
        dto.setJoiningDate(employee.getJoiningDate());
        if (employee.getManager() != null) {
            dto.setManagerId(employee.getManager().getId());
            dto.setManagerName(employee.getManager().getEmployeeName());
        }
        dto.setShiftTiming(employee.getShiftTiming());
        dto.setStatus(employee.getStatus());
        dto.setSalary(employee.getSalary());
        dto.setGender(employee.getGender());
        dto.setEmailAddress(employee.getEmailAddress());
        dto.setPhoneNumber(employee.getPhoneNumber());
        dto.setDateOfBirth(employee.getDateOfBirth());
        dto.setBloodGroup(employee.getUser().getBloodGroup());
        dto.setQualification(employee.getUser().getQualification());
        dto.setSkills(employee.getUser().getSkills());
        dto.setCertifications(employee.getUser().getCertifications());
        dto.setExperience(employee.getUser().getExperience());
        dto.setCvUrl(employee.getUser().getCvUrl());
        dto.setEmergencyContact(employee.getUser().getEmergencyContact());
        dto.setRole(employee.getUser().getRole());
        dto.setProfilePictureUrl(employee.getUser().getProfilePictureUrl());
        dto.setOrgRoles(employee.getOrgRoles());
        dto.setCreatedAt(employee.getUser().getCreatedAt());
        dto.setUpdatedAt(employee.getUser().getUpdatedAt());
        dto.setAttachments(employee.getUser().getAttachments().stream()
                .map(attachment -> convertAttachmentToDto(attachment, fileStorageService))
                .collect(Collectors.toList()));
        return dto;
    }


}
