package org.tornotron.echno_backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The fields a partial user update may carry, and the type each one is read as.
 *
 * <p>See {@code org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto} for why the endpoint
 * keeps the map at runtime and publishes this as its schema. Nothing deserializes into this class.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code UserService.applyUpdates} actually accepts out of that method's source.
 */
@Schema(description = "Fields a partial user update may change. Every field is optional; only the "
        + "fields present in the request are applied. Keys not listed here are ignored.")
@Data
public class UserUpdateFieldsDto {

    @Schema(description = "Full name of the user.", example = "Hrishikesh R")
    private String name;

    @Schema(description = "Id of the organization the user lands in after signing in. Must be sent "
            + "as a number.", example = "2")
    private Long defaultOrganizationId;

    @Schema(description = "Gender recorded for the user.", example = "MALE")
    private String gender;

    @Schema(description = "Blood group recorded for the user.", example = "O+")
    private String bloodGroup;

    @Schema(description = "Contact email of the user.", example = "hrishi@echno.xyz")
    private String email;

    @Schema(description = "Contact phone number of the user.", example = "+91 99400 00000")
    private String phone;

    @Schema(description = "Date of birth, sent as an ISO date-time string.",
            example = "1998-04-12T00:00:00")
    private LocalDateTime dateOfBirth;

    @Schema(description = "Highest qualification held.", example = "B.Tech Civil Engineering")
    private String qualification;

    @Schema(description = "Postal address of the user.", example = "18 Anna Nagar, Chennai 600040")
    private String address;

    @Schema(description = "Years of experience. Must be sent as a whole number.", example = "6")
    private Integer experience;

    @Schema(description = "Stored key or URL of the uploaded CV.", example = "users/31/cv.pdf")
    private String cvUrl;

    @Schema(description = "Emergency contact for the user.", example = "Anand, +91 88485 42511")
    private String emergencyContact;

    @Schema(description = "Platform role held by the user.")
    private UserRole role;

    @Schema(description = "Stored key or URL of the profile picture.",
            example = "users/31/profile.jpg")
    private String profilePictureUrl;

    @Schema(description = "Skills recorded for the user. Replaces the existing list rather than "
            + "adding to it.", example = "[\"AutoCAD\", \"Site supervision\"]")
    private List<String> skills;

    @Schema(description = "Certifications recorded for the user. Replaces the existing list rather "
            + "than adding to it.", example = "[\"NEBOSH IGC\"]")
    private List<String> certifications;
}
