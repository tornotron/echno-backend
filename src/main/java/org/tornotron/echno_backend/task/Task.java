package org.tornotron.echno_backend.task;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.task.enums.TaskStatus;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Represents a task entity in the system.
 * This class is mapped to the "Task" table in the database.
 */
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Task implements TenantScopedEntity {

    /** The unique identifier for the task. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The title of the task. */
    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    /** The scheduled start date and time of the task. */
    @Column(name = "start_date", nullable = true)
    private LocalDateTime startDate;

    /** The scheduled end date and time of the task. */
    @Column(name = "end_date", nullable = true)
    private LocalDateTime endDate;

    /** The employee who created the task. */
    @ManyToOne
    @JoinColumn(name = "creator_id", nullable = false)
    private Employee creator;

    /** The project to which this task belongs. */
    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /** The set of employees assigned to this task. */
    @ManyToMany
    @JoinTable(name = "task_assignees",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "creator_id"))
    private Set<Employee> assignees;

    /** The category of the task. */
    @ManyToOne
    private Category category;

    /** The progress of the task, typically represented as a percentage (e.g., 0.0 to 100.0). */
    private Double progress;

    /** A list of tags associated with the task for categorization and searching. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_tags", joinColumns = @JoinColumn(name = "id"))
    @Column(name = "tags")
    private List<String> tags;

    /** The timestamp when the task was created. Automatically generated. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** The timestamp when the task was last updated. Automatically generated. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** The current status of the task. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TaskStatus status;

    @OneToMany(mappedBy = "task")
    private List<Issue> issues;

    /** The list of attachments associated with this task. */
    @OneToMany(mappedBy = "task")
    private List<Attachment> attachments = new ArrayList<>();

    public void addAttachment(Attachment attachment) {
        attachments.add(attachment);
        attachment.setTask(this);
    }
}