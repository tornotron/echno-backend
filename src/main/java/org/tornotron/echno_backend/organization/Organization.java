package org.tornotron.echno_backend.organization;


import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.project.Project;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@Table(name = "Organization",indexes = {
        @Index(name = "idx_organization_name", columnList = "organization_name")
})
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id",nullable = false)
    private Long id;

    @Column(name = "organization_name", unique = true,nullable = true)
    private String organizationName;

    @Column(name = "organization_address", nullable = true)
    private String organizationAddress;

    @Column(name = "created_at", nullable = true)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "organization")
    @Column(nullable = true)
    private List<Project> projects;
}
