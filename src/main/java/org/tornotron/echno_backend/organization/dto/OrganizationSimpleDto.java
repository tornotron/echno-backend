package org.tornotron.echno_backend.organization.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrganizationSimpleDto {
    private Long id;
    private String organizationName;
    private String organizationAddress;
    private String organizationEmail;
    private String organizationPhone;
    private String organizationWebsite;
    private String organizationLogo ;
    private LocalDateTime createdAt;
    private Boolean isActive;
    private Integer creatorId;
}
