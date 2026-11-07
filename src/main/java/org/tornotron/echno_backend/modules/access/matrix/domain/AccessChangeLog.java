package org.tornotron.echno_backend.modules.access.matrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One change to who holds what. Append-only: once written, a row is never updated or deleted, so
 * the entity is immutable and {@link #of} is the only way to make one. The table carries no
 * foreign key, so a row outlives the people and roles it talks about.
 */
@Entity
@Immutable
@Table(name = "access_change_log")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessChangeLog implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Set once by of(); the setter exists because every tenant scoped entity has one.
    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Organization organization;

    // The login that made the change; null when the system made it.
    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(nullable = false, updatable = false, length = 60)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false, length = 40)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false, length = 80)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    public static AccessChangeLog of(Organization organization, Long actorUserId, String action,
                                     String targetType, String targetId,
                                     Map<String, Object> beforeState, Map<String, Object> afterState) {
        AccessChangeLog row = new AccessChangeLog();
        row.organization = organization;
        row.actorUserId = actorUserId;
        row.action = action;
        row.targetType = targetType;
        row.targetId = targetId;
        row.beforeState = beforeState;
        row.afterState = afterState;
        return row;
    }
}
