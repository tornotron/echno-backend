package org.tornotron.echno_backend.project.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
public class ProjectCreationDto {

    @NotBlank(message = "projectName is required")
    @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters")
    private String projectName;

    @NotBlank(message = "projectAddress is required")
    @Size(min = 3,max = 50,message = "projectAddress must be between 3 and 50 characters")
    private String projectAddress;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private String status;
}
