package org.tornotron.echno_backend.project;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.teamMember.TeamMember;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@Table(name = "Project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id",nullable = false)
    private Long id;

    @Column(name = "project_name", unique = true,nullable = true)
    private String projectName;

    @Column(name = "project_address", nullable = true)
    private String projectAddress;

    @Column(name = "created_at", nullable = true)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = true)
    private ProjectCreationStatus status;

    @OneToMany(mappedBy = "project")
    @Column(nullable = true)
    private List<TeamMember> teamMembers;
}
