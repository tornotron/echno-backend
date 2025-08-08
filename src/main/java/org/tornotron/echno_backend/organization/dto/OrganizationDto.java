package org.tornotron.echno_backend.organization.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrganizationDto {
    private Long id;
    private String organizationName;
    private String organizationAddress;
    private String organizationEmail;
    private String organizationPhone;
    private String organizationWebsite;
    private String organizationLogo ;
    private LocalDateTime createdAt;
    private List<ProjectDto> projects;
    private List<EmployeeDto> employees;
    private Boolean isActive;
}
