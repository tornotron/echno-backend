package org.tornotron.echno_backend.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrganizationCreationDto {

    /**
     * The minimum is two rather than three: the form has never had one, and two-letter company
     * names are ordinary.
     */
    @NotBlank(message = "organization is required")
    @Size(min = 2, max = 50, message = "organization name must be between 2 and 50 characters")
    private String organizationName;

    /**
     * The column is TEXT and the form offers a three-row textarea, so the cap is the column's
     * rather than the fifty characters that were written here and never ran. A postal address
     * with a street, an area, a city and a PIN code does not fit in fifty.
     */
    @NotBlank(message = "organizationAddress is required")
    @Size(min = 3, max = 255, message = "organizationAddress must be between 3 and 255 characters")
    private String organizationAddress;

    /**
     * The top-level domain runs to 24 characters rather than six. Six refuses .construction,
     * .engineering, .contractors and .consulting, which are exactly the domains this product's
     * customers register.
     */
    @NotBlank(message = "organizationEmail is required")
    @Pattern(
            regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,24}$",
            message = "Invalid email address format"
    )
    @Size(max = 255, message = "organizationEmail must be at most 255 characters")
    private String organizationEmail;

    /**
     * The form sends E.164, which is a leading plus and eight to fifteen digits, so the string is
     * nine to sixteen characters. The old window of ten to fifteen counted the digits and forgot
     * the plus, refusing a valid fifteen-digit international number, and its message named a
     * field that does not exist on this payload.
     */
    @NotBlank(message = "organizationPhone is required")
    @Size(min = 9, max = 16, message = "organizationPhone must be between 9 and 16 characters")
    private String organizationPhone;

    @Size(max = 255, message = "organizationWebsite must be at most 255 characters")
    private String organizationWebsite;

    private String organizationLogo;

    private LocalDateTime createdAt;

}
