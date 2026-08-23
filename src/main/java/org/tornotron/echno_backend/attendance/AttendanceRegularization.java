package org.tornotron.echno_backend.attendance;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

/**
 * A request to correct an incomplete or incorrect {@link Attendance} record.
 *
 * <p>Raised when clock events are missing or wrong (the {@code missingEvents} field lists which),
 * it carries the employee's reason and moves through its own approve/reject workflow tracked by
 * {@code status}. Approval lets the attendance be recomputed as if the corrected events were present.
 */
@Entity
@Table(name = "attendance_regularization")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class AttendanceRegularization implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id", nullable = false)
    private Attendance attendance;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "requested_by_id")
    private Long requestedById;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_by_id")
    private Long approvedById;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RegularizationStatus status = RegularizationStatus.PENDING;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "missing_events", columnDefinition = "TEXT")
    private String missingEvents;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @PrePersist
    void prePersist() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }
}
