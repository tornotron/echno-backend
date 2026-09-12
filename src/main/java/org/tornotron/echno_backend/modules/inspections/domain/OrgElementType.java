package org.tornotron.echno_backend.modules.inspections.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An element type as one organization has it: the catalogue copies plus any the organization
 * defined. Tenant scoped through the {@code orgFilter}. Codes are immutable; rows are
 * deactivated, never deleted, so an element already typed by one stays valid.
 */
@Entity
@Table(name = "org_element_types",
        uniqueConstraints = @UniqueConstraint(name = "uk_org_element_type_code",
                columnNames = {"organization_id", "code"}),
        indexes = @Index(name = "idx_org_element_type_active", columnList = "organization_id, active"))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class OrgElementType implements TenantScopedEntity {

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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
