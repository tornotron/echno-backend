package org.tornotron.echno_backend.site.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.site.enums.ProjectCreationStatus;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "projectName is required")
    @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters")
    private String projectName;

    @NotBlank(message = "projectAddress is required")
    @Size(min = 3,max = 50,message = "projectAddress must be between 3 and 50 characters")
    private String projectAddress;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private ProjectCreationStatus status;

    @OneToMany(mappedBy = "project")
    private List<TeamMember> teamMembers;
}
