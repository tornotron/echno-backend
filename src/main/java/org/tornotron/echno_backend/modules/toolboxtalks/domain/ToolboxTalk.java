package org.tornotron.echno_backend.modules.toolboxtalks.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One toolbox talk: the short safety briefing a supervisor gives a crew before work starts.
 *
 * <p>The aggregate of the module. Tenant scoped behind {@code orgFilter}; the attendees are an
 * owned child, joined here with a non-nullable association, so they inherit the tenant.
 * References into the core domain (the project, the spatial node, the conductor) are plain ids
 * with foreign keys in the changelog, not JPA associations, so the module stays additive and
 * reads nothing of the core's it does not need.
 */
@Entity
@Table(name = "toolbox_talk",
        indexes = {
                @Index(name = "idx_toolbox_talk_organization", columnList = "organization_id"),
                @Index(name = "idx_toolbox_talk_project_date", columnList = "organization_id, project_id, talk_date")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ToolboxTalk implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    // The floor or zone the talk was held for, when the project has a site structure.
    @Column(name = "spatial_node_id")
    private UUID spatialNodeId;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(name = "talk_date", nullable = false)
    private LocalDate talkDate;

    @Column(name = "talk_time")
    private LocalTime talkTime;

    @Column(name = "conductor_employee_id", nullable = false)
    private Long conductorEmployeeId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ToolboxTalkStatus status = ToolboxTalkStatus.DRAFT;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @OneToMany(mappedBy = "talk", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("employeeId ASC")
    private List<ToolboxTalkAttendee> attendees = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    public boolean isDraft() {
        return status == ToolboxTalkStatus.DRAFT;
    }

    public boolean hasAttendee(Long employeeId) {
        return attendees.stream().anyMatch(a -> a.getEmployeeId().equals(employeeId));
    }

    public void addAttendee(Long employeeId) {
        if (hasAttendee(employeeId)) {
            return;
        }
        ToolboxTalkAttendee attendee = new ToolboxTalkAttendee();
        attendee.setTalk(this);
        attendee.setEmployeeId(employeeId);
        attendees.add(attendee);
    }

    public boolean removeAttendee(Long employeeId) {
        return attendees.removeIf(a -> a.getEmployeeId().equals(employeeId));
    }
}
