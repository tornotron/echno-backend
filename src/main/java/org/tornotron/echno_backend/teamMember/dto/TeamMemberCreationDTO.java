package org.tornotron.echno_backend.teamMember.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TeamMemberCreationDTO {

    @NotBlank(message = "memberName is required")
    @Size(min = 3,max = 50,message = "memberName must be between 3 and 50 characters")
    private String memberName;

    @NotBlank(message = "memberEmail is required")
    @Size(max = 255)
    @Pattern(
            regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$",
            message = "Invalid email address format"
    )
    private String memberEmail;

    @NotBlank(message = "memberPhone is required")
    @Size(min = 10,max = 15,message = "memberPhone must be between 10 and 15 characters")
    private String memberPhone;

    @NotBlank(message = "memberRole is required")
    private String memberRole;

    private String memberImage;

    @NotBlank(message = "projectName is required")
    @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters")
    private String projectName;


}
