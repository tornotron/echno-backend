package org.tornotron.echno_backend.organization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "An organization without its contents: the scalar columns, and on the "
        + "summary list the employee and project counts and the resolved logo URL. A create or an "
        + "update replies with the same shape but leaves those three null, because it does not "
        + "read them.")
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

    @Schema(description = "How many employees the organization has. Read for the whole list in "
            + "one aggregate on the summary endpoint; null on a create or update reply.",
            example = "42", nullable = true)
    private Long employeeCount;

    @Schema(description = "How many projects the organization has. Read for the whole list in "
            + "one aggregate on the summary endpoint; null on a create or update reply.",
            example = "7", nullable = true)
    private Long projectCount;

    @Schema(description = "Download URL of the organization's current logo, resolved from its "
            + "latest ORGANIZATION_LOGO attachment the way the full view's attachment list is. "
            + "Null where there is no logo, and on a create or update reply.",
            example = "https://cdn.example/echno/organizations/12/logo.png?X-Amz-Signature=...",
            nullable = true)
    private String logoUrl;
}
