package org.tornotron.echno_backend.issue.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class IssueCreationDto {

    @NotBlank
    @Size(min = 3,max = 50,message = "Title must be between 3 and 50 characters")
    private String title;

    private Long taskId;

    @NotBlank
    @Size(min = 5, max = 500, message = "Description must be between 10 and 500 characters")
    private String description;

    // The web client (echno-core) sends this field as "issueType"; accept both
    // that and the canonical "type" so a create from the web populates it.
    @NotNull
    @Enumerated(EnumType.STRING)
    @JsonAlias("issueType")
    private String type;

    @NotNull
    @Enumerated(EnumType.STRING)
    private String status;

    private Long createdById;

    private Long assignedToId;
}
