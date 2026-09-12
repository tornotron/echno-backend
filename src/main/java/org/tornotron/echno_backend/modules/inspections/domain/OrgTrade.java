package org.tornotron.echno_backend.modules.inspections.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.inspections.InspectionTrade;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An inspection trade as one organization has it: the rows copied from the
 * {@link TradeCatalogueEntry catalogue} plus any the organization defined itself. Tenant
 * scoped through the Hibernate {@code orgFilter}, so a checklist template or an inspection
 * that points at a trade points at a row of its own organization and never at a global one.
 *
 * <p>{@code catalogueCode} is set on a copied row and null on an org-defined one; the code of
 * a copied row cannot change, its name and group can, and any row can be deactivated but
 * never deleted. {@code legacyEnum} carries the {@link InspectionTrade} constant name for the
 * sixteen trades the enum knew, so the compatibility shim can keep the old enum column in
 * step, and goes with the shim.
 */
@Entity
@Table(name = "inspection_trades",
        uniqueConstraints = @UniqueConstraint(name = "uk_inspection_trade_code",
                columnNames = {"organization_id", "code"}),
        indexes = @Index(name = "idx_inspection_trade_active", columnList = "organization_id, active"))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class OrgTrade implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "group_code", nullable = false, length = 50)
    private String groupCode;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "catalogue_code", length = 50)
    private String catalogueCode;

    /** Enum constant name for the sixteen pre-catalogue trades; null otherwise. */
    @Column(name = "legacy_enum", length = 50)
    private String legacyEnum;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** The enum constant this row stands in for, or null for a trade the enum never had. */
    public InspectionTrade legacyTrade() {
        return legacyEnum == null ? null : InspectionTrade.valueOf(legacyEnum);
    }
}
