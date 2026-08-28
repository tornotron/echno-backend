package org.tornotron.echno_backend.asset;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;

import java.time.LocalDateTime;

/**
 * One entry in an asset's movement ledger: what moved, from where to where, when, by whom
 * and why.
 *
 * <p><strong>Append-only.</strong> The entity is {@link Immutable}, so a row that has been
 * written is never updated, and {@link AssetMovementRepository} exposes no delete. An entry
 * made in error is superseded by a {@link AssetMovementType#CORRECTION} that names it in
 * {@code correctsMovementId}, exactly as a stock correction is a further adjustment rather
 * than an edit of the one that was wrong.
 *
 * <p><strong>Names are snapshotted alongside the foreign keys.</strong> {@code toProject}
 * and {@code toLocation} can be set to null by a later delete of the row they point at, and
 * a project can be renamed. History that evaporates when a project is tidied away is not
 * history, so the name as it read at the time is stored with the entry. The snapshot is also
 * what carries the free text of an asset whose old {@code assigned_project} string matched no
 * project when the reference migration ran: the string survives in the ledger rather than
 * being dropped.
 */
@Entity
@Table(name = "asset_movement", indexes = {
        @Index(name = "idx_asset_movement_asset", columnList = "asset_id, moved_at"),
        @Index(name = "idx_asset_movement_organization", columnList = "organization_id")
})
@Immutable
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class AssetMovement implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private AssetMovementType movementType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_project_id")
    private Project fromProject;

    @Column(name = "from_project_name", length = 255)
    private String fromProjectName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_project_id")
    private Project toProject;

    @Column(name = "to_project_name", length = 255)
    private String toProjectName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_location_id")
    private StorageLocation fromLocation;

    @Column(name = "from_location_name", length = 255)
    private String fromLocationName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_location_id")
    private StorageLocation toLocation;

    @Column(name = "to_location_name", length = 255)
    private String toLocationName;

    @Column(name = "from_assigned_to_id")
    private Long fromAssignedToId;

    @Column(name = "from_assigned_to", length = 200)
    private String fromAssignedTo;

    @Column(name = "to_assigned_to_id")
    private Long toAssignedToId;

    @Column(name = "to_assigned_to", length = 200)
    private String toAssignedTo;

    /** When the asset actually moved. Supplied by the caller, so it can be backdated. */
    @Column(name = "moved_at", nullable = false)
    private LocalDateTime movedAt;

    /** When the entry was written. Set by the database clock and never supplied by a caller. */
    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    /** The user who recorded the movement, null only where no user context was available. */
    @Column(name = "moved_by")
    private Long movedBy;

    /** Why the asset moved. Required: a movement with no stated reason is what makes a ledger unexplainable. */
    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** An external document this movement came from, for example a site transfer number. */
    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    /** The entry this one restates, set only on a {@link AssetMovementType#CORRECTION}. */
    @Column(name = "corrects_movement_id")
    private Long correctsMovementId;
}
