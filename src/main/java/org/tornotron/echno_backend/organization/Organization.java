package org.tornotron.echno_backend.organization;


import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.project.Project;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents an organization entity in the system.
 * This class is mapped to the "Organization" table in the database.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "Organization",indexes = {
        @Index(name = "idx_organization_name", columnList = "organization_name")
})
public class Organization {

    /** The unique identifier for the organization. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id",nullable = false)
    private Long id;

    /** The name of the organization. It must be unique. */
    @Column(name = "organization_name", nullable = false)
    private String organizationName;

    /** The physical address of the organization. */
    @Column(name = "organization_address", nullable = false)
    private String organizationAddress;

    /** The contact email of the organization. */
    @Column(name = "organization_email",nullable = false)
    private String organizationEmail;

    /** The contact phone number of the organization. */
    @Column(name = "organization_phone", nullable = false)
    private String organizationPhone;

    /** The official website of the organization. */
    @Column(name = "organization_website", nullable = true)
    private String organizationWebsite;

    /** The URL or path to the organization's logo. */
    @Column(name = "organization_logo", nullable = true)
    private String organizationLogo;

    /** The timestamp when the organization was created. */
    @Column(name = "created_at", nullable = true)
    private LocalDateTime createdAt;

    /** The list of projects associated with this organization. */
    @OneToMany(mappedBy = "organization")
    private List<Project> projects;

    /** The list of employees belonging to this organization. */
    @OneToMany(mappedBy = "organization")
    private List<Employee> employees;

    /** The ID of the user who created this organization record. */
    @Column(name = "creator_id", nullable = true)
    private Integer creatorId;

    /** A flag indicating whether the organization is active. */
    @Column(name = "is_active", nullable = true)
    private Boolean isActive;

    /** The list of attachments associated with this organization. */
    @OneToMany(mappedBy = "organization")
    private List<Attachment> attachments;
}