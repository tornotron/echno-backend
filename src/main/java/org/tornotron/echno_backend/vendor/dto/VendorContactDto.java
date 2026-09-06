package org.tornotron.echno_backend.vendor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * A contact person at a vendor, as it is served. A field without {@code nullable = true} is one
 * the schema behind it makes {@code NOT NULL}; see {@code ReviewedResponseSchemas}.
 */
@Data
public class VendorContactDto {

    private Long id;

    private String contactPerson;

    private String email;

    private String phone;

    @Schema(description = "Second number for this contact. Null where only one was given.",
            nullable = true)
    private String alternatePhone;

    private boolean isPrimary;
}
