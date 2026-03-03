package org.tornotron.echno_backend.attendance;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

@Entity
@Table(name = "attendance_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class AttendanceSettings implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "setting_name", nullable = false)
    private String settingName;

    @Column(name = "check_in_out_cycles", nullable = false)
    @Builder.Default
    private Integer checkInOutCycles = 2;

    @Column(name = "photo_required_on_check_in", nullable = false)
    @Builder.Default
    private Boolean photoRequiredOnCheckIn = true;

    @Column(name = "photo_required_on_check_out", nullable = false)
    @Builder.Default
    private Boolean photoRequiredOnCheckOut = false;

    @Column(name = "geolocation_required", nullable = false)
    @Builder.Default
    private Boolean geolocationRequired = true;

    @Column(name = "geofence_radius_meters", nullable = false)
    @Builder.Default
    private Integer geofenceRadiusMeters = 100;

    @Column(name = "movement_tracking_enabled", nullable = false)
    @Builder.Default
    private Boolean movementTrackingEnabled = true;

    @Column(name = "movement_photo_required", nullable = false)
    @Builder.Default
    private Boolean movementPhotoRequired = false;

    @Column(name = "movement_geolocation_required", nullable = false)
    @Builder.Default
    private Boolean movementGeolocationRequired = false;

    @Column(name = "auto_mark_absent_after_hours", nullable = false)
    @Builder.Default
    private Integer autoMarkAbsentAfterHours = 4;

    @Column(name = "allow_self_regularization", nullable = false)
    @Builder.Default
    private Boolean allowSelfRegularization = true;

    @Column(name = "regularization_approval_required", nullable = false)
    @Builder.Default
    private Boolean regularizationApprovalRequired = true;

    @Column(name = "max_regularization_days_per_month", nullable = false)
    @Builder.Default
    private Integer maxRegularizationDaysPerMonth = 3;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_shift_timing_id")
    private ShiftTiming defaultShiftTiming;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
