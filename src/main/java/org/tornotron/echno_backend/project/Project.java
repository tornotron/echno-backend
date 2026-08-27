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

    /** The street address of the site, as one line. */
    @Column(name = "project_address", nullable = true)
    private String projectAddress;

    /** Town or city the site is in. Optional, and display only. */
    @Column(name = "project_city", length = 100)
    private String projectCity;

    /**
     * Indian state or union territory the site is in, in its canonical spelling. Read by
     * compliance generation, which keys its rules by state: stated here it is exact, whereas
     * the fallback of scraping the free-text address only works when the address happens to
     * name the state. Optional, so projects created before this field keep working.
     */
    @Column(name = "project_state", length = 100)
    private String projectState;

    /** Postal (PIN) code of the site. Optional, and display only. */
    @Column(name = "project_postal_code", length = 16)
    private String projectPostalCode;

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

    /**
     * Per-project override of the finance auto-approval threshold. When set, a construction invoice
     * on this project below this amount is auto-approved on submit; null falls back to the
     * organization-level finance setting.
     */
    @Column(name = "approval_threshold", precision = 19, scale = 4)
    private java.math.BigDecimal approvalThreshold;

    /**
     * The finance customer this project is billed to, its client. Kept as a plain id rather than
     * an association so the core project stays decoupled from the finance module. When set, a
     * sales or service construction invoice on the project is billed by materializing a real AR
     * invoice against this customer; when null, that invoice posts its receivable entry directly.
     */
    @Column(name = "customer_id")
    private java.util.UUID customerId;

    /** The list of attachments associated with this project. */
    @OneToMany(mappedBy = "project")
    private List<Attachment> attachments = new ArrayList<>();

    public void addAttachment(Attachment attachment) {
        attachments.add(attachment);
        attachment.setProject(this);
    }
}