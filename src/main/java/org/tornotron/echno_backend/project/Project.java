package org.tornotron.echno_backend.project;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.wbs.WbsElement;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a project entity in the system.
 * This class is mapped to the "Project" table in the database.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "Project",indexes = {
        @Index(name = "idx_project_name", columnList = "project_name")
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Project implements TenantScopedEntity {

    /** The unique identifier for the project. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id",nullable = false)
    private Long id;

    /** The name of the project. */
    @Column(name = "project_name",nullable = true)
    private String projectName;

    /** The physical address where the project is located. */
    @Column(name = "project_address", nullable = true)
    private String projectAddress;

    /** The timestamp when the project was created. */
    @Column(name = "created_at", nullable = true)
    private LocalDateTime createdAt;

    /** The current status of the project. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = true)
    private ProjectCreationStatus status;

    /** The broad construction category, used to match statutory compliances. */
    @Enumerated(EnumType.STRING)
    @Column(name = "project_type", nullable = true)
    private ProjectType projectType;

    /** The list of employees assigned to this project. */
    @ManyToMany
    @JoinTable(
            name = "project_employees",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "employee_id")
    )
    private List<Employee> employees = new ArrayList<>();

    /** The organization to which this project belongs. */
    @ManyToOne
    private Organization organization;

    /** The list of tasks associated with this project. */
    @OneToMany(mappedBy = "project")
    private List<Task> tasks;

    @OneToMany(mappedBy = "project")
    private List<WbsElement> wbsElements = new ArrayList<>();

    /** The latitude coordinate of the project location. */
    @Column(name = "latitude")
    private Float projectLatitude;

    /** The longitude coordinate of the project location. */
    @Column(name = "longitude")
    private Float projectLongitude;

    /** The scheduled start date and time of the project. */
    @Column(name = "start_date")
    private LocalDateTime startDate;

    /** The scheduled end date and time of the project. */
    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "progress")
    private Double progress;

    /** The list of attachments associated with this project. */
    @OneToMany(mappedBy = "project")
    private List<Attachment> attachments = new ArrayList<>();

    public void addAttachment(Attachment attachment) {
        attachments.add(attachment);
        attachment.setProject(this);
    }
}