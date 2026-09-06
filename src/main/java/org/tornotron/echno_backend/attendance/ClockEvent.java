package org.tornotron.echno_backend.attendance;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A single clock punch within a day's {@link Attendance}: clock-in, lunch start/end, or clock-out.
 *
 * <p>Records when the punch happened and the device and geolocation it came from (latitude,
 * longitude, accuracy, distance from the project, geofence flag), which the attendance calculation
 * reads to derive worked hours. May be flagged {@code isRegularized} when created or corrected
 * through a regularization rather than a live punch.
 *
 * <p>The geofence fields are nullable together: either a verdict was reached, in which case
 * {@code isWithinGeofence}, {@code distanceFromProject} and {@code geofenceRadiusMeters} are all
 * set, or none of them are.
 */
@Entity
@Table(name = "clock_event")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ClockEvent implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id", nullable = false)
    private Attendance attendance;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private ClockEventType eventType;

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "gps_accuracy")
    private Double gpsAccuracy;

    @Column(name = "altitude")
    private Double altitude;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Column(name = "device_platform")
    private String devicePlatform;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "ip_address")
    private String ipAddress;

    /**
     * Whether the punch fell inside the project's geofence, or null when no verdict was reached.
     *
     * <p>Null is a real state and the common one: a project with no coordinates, an organization
     * with no radius, a punch with no location, and an event written by a regularization rather
     * than taken on a device all leave it unevaluated. It carried a {@code false} default until
     * the evaluation was wired up, which made "nobody looked" read as "outside the site".
     */
    @Column(name = "is_within_geofence")
    private Boolean isWithinGeofence;

    /** How far the punch was from the project's marker, in metres, or null when not measured. */
    @Column(name = "distance_from_project")
    private Double distanceFromProject;

    /**
     * The geofence radius the verdict was reached against, in metres, or null when not evaluated.
     *
     * <p>Stamped here rather than read back from the settings because both the radius and the
     * project's coordinates can change afterwards, and a verdict has to stay readable against the
     * numbers that produced it.
     */
    @Column(name = "geofence_radius_meters")
    private Integer geofenceRadiusMeters;

    /**
     * Why the employee marked their own attendance from outside the fence, or null when they did
     * not. Set only on the self-marking path, which is the only one that asks for it.
     */
    @Column(name = "geofence_exception_reason", length = 500)
    private String geofenceExceptionReason;

    /**
     * The employee who submitted this punch, which is not always the employee it belongs to: a
     * supervisor can record attendance for their team.
     *
     * <p>It is what makes the geofence fields readable. A punch entered for somebody else carries
     * the submitting device's position, so it is left unevaluated, and without this column a reader
     * could not tell that absent verdict from one caused by a project with no coordinates. Null
     * only when the caller resolves to no employee record in this tenant.
     */
    @Column(name = "recorded_by_id")
    private Long recordedById;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Builder.Default
    @Column(name = "is_regularized", nullable = false)
    private Boolean isRegularized = false;

    @Column(name = "regularization_reason")
    private String regularizationReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @OneToMany(mappedBy = "clockEvent")
    private List<Attachment> attachments = new ArrayList<>();

    public void addAttachment(Attachment attachment) {
        attachments.add(attachment);
        attachment.setClockEvent(this);
    }
}

