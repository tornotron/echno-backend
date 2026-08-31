package org.tornotron.echno_backend.organization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * The fields a partial organization update may carry, and the type each one is read as.
 *
 * <p>See {@code org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto} for why the endpoint
 * keeps the map at runtime and publishes this as its schema. Nothing deserializes into this class.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code OrganizationService.partialUpdateAnOrganization} actually accepts out of that method's
 * source.
 */
@Schema(description = "Fields a partial organization update may change. "
        + "Every field is optional and an absent field is left untouched. A field this schema "
        + "declares nullable is cleared by sending an explicit null; a field it does not declare "
        + "nullable refuses a null with a 400 rather than clearing. Keys not listed here are "
        + "ignored.")
@Data
public class OrganizationUpdateFieldsDto {

    @Schema(nullable = true, description = "Registered name of the organization.", example = "Asset Homes")
    private String organizationName;

    @Schema(nullable = true, description = "Postal address of the organization.",
            example = "IIT Madras Research Park, Chennai 600113")
    private String organizationAddress;

    @Schema(nullable = true, description = "Contact email for the organization.", example = "info@echno.xyz")
    private String organizationEmail;

    @Schema(nullable = true, description = "Contact phone number for the organization.", example = "+91 44 4000 0000")
    private String organizationPhone;

    @Schema(nullable = true, description = "Public website of the organization.", example = "https://echno.xyz")
    private String organizationWebsite;

    @Schema(nullable = true, description = "Stored key or URL of the organization logo.",
            example = "organizations/2/logo.png")
    private String organizationLogo;
}
