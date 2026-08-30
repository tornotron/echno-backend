package org.tornotron.echno_backend.common.history;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

/**
 * One entry in a record's status trail: what it moved from, what it moved to, when, by whom,
 * and whether the status was set at creation or changed afterwards.
 *
 * <p><strong>Shared storage, per-module exposure.</strong> The table is keyed by
 * {@link #entityType} and {@link #entityId} rather than by a foreign key to one owner, the way
 * {@code Attachment} already is, so every record with a gated status transition can use it
 * without a table of its own. What is deliberately not shared is the API: each module reads its
 * own trail through its own endpoint under its own authorization, because who may read a site
 * transfer's history is not who may read a project's, and a single generic history endpoint
 * would need a registry of per-type read rules to say so.
 *
 * <p><strong>Append-only.</strong> The entity is {@link Immutable} and
 * {@link StatusTransitionRepository} offers no delete, so a written entry is never edited or
 * removed. There is nothing to correct: an entry records what was observed at the time, and a
 * later change is a further entry.
 *
 * <p><strong>No foreign key to the record it describes.</strong> This is the one departure from
 * {@code AssetMovement}, which refuses to let an asset be deleted while its ledger holds
 * entries. A movement is a fact about a live asset; a status trail has to outlive the record it
 * describes, or deleting a project erases the evidence of who approved it. The polymorphic key
 * gives that, at the cost of the database not constraining {@link #entityId}, which is why
 * isolation rests on {@link #organization} and every read is organization-explicit.
 *
 * <p><strong>The actor's name is snapshotted beside the id.</strong> A user can be renamed or
 * removed, and history that evaporates when that happens is not history.
 */
@Entity
@Table(name = "status_transition", indexes = {
        @Index(name = "idx_status_transition_entity", columnList = "entity_type, entity_id, occurred_at"),
        @Index(name = "idx_status_transition_organization", columnList = "organization_id")
})
@Immutable
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class StatusTransition implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The kind of record this entry belongs to, for example {@code PROJECT}. */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /** The id of the record within its kind. Not a foreign key, deliberately. */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /**
     * The status held before this entry, null when there was none: a creation, or the baseline
     * written when the trail began.
     */
    @Column(name = "from_status", length = 50)
    private String fromStatus;

    /** The status held after this entry. */
    @Column(name = "to_status", nullable = false, length = 50)
    private String toStatus;

    /** Whether the status was set at creation, changed later, or merely observed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private StatusTransitionSource source;

    /** When the status came to be held. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** The user who made the change, null where no user context was available. */
    @Column(name = "changed_by")
    private Long changedBy;

    /** That user's name as it read at the time. */
    @Column(name = "changed_by_name", length = 200)
    private String changedByName;

    /**
     * Anything worth saying about the change beyond the two statuses.
     *
     * <p>Optional, unlike {@code AssetMovement.reason}, which is required. A movement's reason
     * is not derivable from the row, whereas an entry here already names what it moved from and
     * what it moved to; requiring prose on every one buys a column full of "changed status".
     */
    @Column(name = "note", length = 500)
    private String note;
}
