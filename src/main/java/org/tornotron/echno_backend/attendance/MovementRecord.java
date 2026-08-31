package org.tornotron.echno_backend.attendance;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.attendance.enums.MovementType;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

@Entity
@Table(name = "movement_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MovementRecord implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id", nullable = false)
    private Attendance attendance;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false)
    private MovementType movementType;

    @Column(name = "from_location", nullable = false)
    private String fromLocation;

    @Column(name = "to_location")
    private String toLocation;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "purpose", nullable = false)
    private String purpose;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "start_latitude")
    private Double startLatitude;

    @Column(name = "start_longitude")
    private Double startLongitude;

    @Column(name = "end_latitude")
    private Double endLatitude;

    @Column(name = "end_longitude")
    private Double endLongitude;

    @Column(name = "attachments", columnDefinition = "TEXT")
    private String attachments;

    @Column(name = "verified_by")
    private String verifiedBy;

    /**
     * The verifier's employee id, next to the name in {@code verified_by}. Stamped from the
     * session, and null for a verifier who holds a record-management role without an employee
     * record in this tenant, the same way {@code Attendance.approvedById} is. Names are not
     * identities: two people can share one and a person can change theirs.
     */
    @Column(name = "verified_by_id")
    private Long verifiedById;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Builder.Default
    @Column(name = "is_verified", nullable = false)
    private Boolean isVerified = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
