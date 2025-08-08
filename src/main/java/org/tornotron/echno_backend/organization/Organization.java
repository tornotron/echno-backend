package org.tornotron.echno_backend.organization;


import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.employee.Employee;
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

    @Column(name = "organization_name", unique = true,nullable = false)
    private String organizationName;

    @Column(name = "organization_address", nullable = false)
    private String organizationAddress;

    @Column(name = "organization_email",nullable = false)
    private String organizationEmail;

    @Column(name = "organization_phone", nullable = false)
    private String organizationPhone;

    @Column(name = "organization_website", nullable = true)
    private String organizationWebsite;

    @Column(name = "organization_logo", nullable = true)
    private String organizationLogo;

    @Column(name = "created_at", nullable = true)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "organization")
    @Column(nullable = true)
    private List<Project> projects;

    @OneToMany(mappedBy = "organization")
    @Column(nullable = true)
    private List<Employee> employees;

    @Column(name = "creator_id", nullable = true)
    private Integer creatorId;

    @Column(name = "is_active", nullable = true)
    private Boolean isActive;
}
