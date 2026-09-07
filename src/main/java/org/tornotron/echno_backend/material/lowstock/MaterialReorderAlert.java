package org.tornotron.echno_backend.material.lowstock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;

import java.time.LocalDateTime;

/**
 * A record that one material on one project has already been reported as low, and has not
 * recovered since.
 *
 * <h2>Why a row exists at all</h2>
 *
 * <p>What makes a low-stock notification worth sending is the material <em>crossing</em> its
 * reorder level, not sitting below it. Being below it is a standing fact that
 * {@code GET /api/v1/materials/low-stock} answers at any hour for free, and a nightly pass that
 * re-sent it would tell the same person the same forty things every morning until they muted the
 * category, at which point the one crossing that mattered is lost with the rest. Nothing in the
 * notification subsystem could express that on its own: {@code Notification} has no idempotency
 * key, {@code (entity_type, entity_id)} is indexed but not unique, and the key here is a pair
 * (this material, on this project) that a single {@code entity_id} column cannot hold.
 *
 * <p>So the state is held here instead. A row means "already reported, and not recovered". The
 * sweep writes one when it sends, leaves it alone while the material stays down, and deletes it
 * once the material has recovered far enough to be worth reporting again. No row and low means
 * this is news.
 *
 * <h2>The unique constraint is the idempotency key</h2>
 *
 * <p>{@code @Scheduled} elects no leader, so on N replicas the pass runs N times and two replicas
 * can find the same material low in the same second. The uniqueness of
 * {@code (organization_id, project_id, material_id)} is what makes the second one lose: the
 * insert fails and that replica sends nothing, rather than both sending and the storekeeper
 * getting the same notification twice.
 *
 * <h2>What the recorded level is for</h2>
 *
 * <p>{@code notifiedLevel} is the reorder level that was in force when the notification went out,
 * and it is compared against the level in force now. A material that was reported at a level of
 * 30 and is now measured against a level of 100 is below a different line than the one anybody
 * was told about, and that is a new event rather than the same one continuing. Without the
 * comparison, raising a level would put a material into a permanently-latched state where it is
 * newly and badly short and nobody is ever told.
 *
 * <p>{@code notifiedQuantity} is what it held at that moment. Nothing reads it: it is there so
 * that somebody looking at why a notification went out can see the number it went out for.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "material_reorder_alert",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_material_reorder_alert_scope",
                columnNames = {"organization_id", "project_id", "material_id"}),
        indexes = {
                @Index(name = "idx_material_reorder_alert_scope",
                        columnList = "organization_id, project_id"),
                @Index(name = "idx_material_reorder_alert_notified_at",
                        columnList = "notified_at")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MaterialReorderAlert implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** The tenant this belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** The project the shortfall was measured on. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** The material that reached its level. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    /** The reorder level that was in force when the notification was sent. */
    @Column(name = "notified_level", nullable = false)
    private Double notifiedLevel;

    /** What the project held at that moment. */
    @Column(name = "notified_quantity", nullable = false)
    private Double notifiedQuantity;

    /** How many people were told. Zero is never written: nobody to tell means nothing is sent. */
    @Column(name = "recipient_count", nullable = false)
    private Integer recipientCount = 0;

    /** When the notification went out. */
    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;
}
