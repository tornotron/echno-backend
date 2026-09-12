package org.tornotron.echno_backend.modules.inspections.domain;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventActorType;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubject;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubjectType;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry in the inspection module's audit log: who did what, to which record, when, and
 * the before and after of the change.
 *
 * <p><strong>Append-only.</strong> The entity is {@link Immutable}, exposes no setter beyond the
 * tenant one the {@link TenantScopedEntity} contract requires, and its repository offers no
 * delete and no modifying query. A written entry is never corrected: it records what was
 * observed at the time, and a later change is a further entry.
 *
 * <p><strong>No foreign key to the record it describes.</strong> The subject is a polymorphic
 * {@code (subject_type, subject_id)} pair, so the trail outlives the row it describes, and an
 * inspection deleted with its items still leaves the record of who passed which check point.
 * Isolation therefore rests on {@link #organization}, and every read is organization-explicit.
 *
 * <p>{@link #before} and {@link #after} hold only the fields that changed, under the names the
 * web contract already uses, so the timeline renders "status: assigned to
 * corrective-action-complete" with no mapping table.
 */
@Entity
@Table(name = "inspection_events", indexes = {
        @Index(name = "idx_insp_event_inspection", columnList = "inspection_id, occurred_at"),
        @Index(name = "idx_insp_event_subject", columnList = "subject_type, subject_id, occurred_at"),
        @Index(name = "idx_insp_event_project", columnList = "project_id, occurred_at"),
        @Index(name = "idx_insp_event_organization", columnList = "organization_id")
})
@Immutable
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionEvent implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "inspection_id")
    private UUID inspectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private InspectionEventSubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private InspectionEventActorType actorType;

    @Column(name = "actor_id", length = 100)
    private String actorId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> after;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "request_id", length = 64)
    private String requestId;

    /** The only way to build an event: everything is fixed at construction. */
    public static InspectionEvent of(Organization organization,
                                     InspectionEventSubject subject,
                                     String eventType,
                                     InspectionEventActorType actorType,
                                     String actorId,
                                     LocalDateTime occurredAt,
                                     Map<String, Object> before,
                                     Map<String, Object> after,
                                     String note,
                                     String requestId) {
        InspectionEvent event = new InspectionEvent();
        event.organization = Objects.requireNonNull(organization, "organization");
        event.projectId = subject.projectId();
        event.inspectionId = subject.inspectionId();
        event.subjectType = subject.type();
        event.subjectId = subject.id();
        event.eventType = Objects.requireNonNull(eventType, "event type");
        event.actorType = Objects.requireNonNull(actorType, "actor type");
        event.actorId = actorId;
        event.occurredAt = Objects.requireNonNull(occurredAt, "occurred at");
        event.before = before == null || before.isEmpty() ? null : Map.copyOf(before);
        event.after = after == null || after.isEmpty() ? null : Map.copyOf(after);
        event.note = note;
        event.requestId = requestId;
        return event;
    }

    /**
     * Required by {@link TenantScopedEntity}. The tenant is fixed at construction, so this
     * accepts only the value already held.
     */
    @Override
    public void setOrganization(Organization organization) {
        if (this.organization != null && this.organization != organization) {
            throw new IllegalStateException("An inspection event cannot change tenant");
        }
        this.organization = organization;
    }
}
