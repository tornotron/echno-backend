package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A reusable checklist an organization's QA engineer defines once for a trade and
 * an inspection of that trade is then created from. Tenant-scoped through the
 * Hibernate {@code orgFilter} exactly as {@link Inspection} is: the criteria a
 * client accepts work against are their own, so a template belongs to one org and
 * is invisible to every other.
 *
 * <p>One template per trade per org, enforced by the unique constraint on
 * ({@code organization_id}, {@code trade}). {@code version} is therefore a
 * revision counter on that one row rather than a key: it starts at 1 and the
 * service bumps it on every edit, so an inspection's copied items can be traced
 * back to the revision they came from. {@code active} decides whether the
 * template is instantiated into new inspections; deactivating it retires the
 * checklist without deleting the definition or the history that references it.
 *
 * <p>The starter content each org begins from is {@link StarterChecklistTemplate},
 * which is global reference data. Adopting a starter copies it into a row of this
 * table, after which the two are independent.
 */
@Entity
@Table(name = "checklist_templates",
        uniqueConstraints = @UniqueConstraint(name = "uk_checklist_template_trade",
                columnNames = {"organization_id", "trade"}),
        indexes = {
                @Index(name = "idx_checklist_template_trade", columnList = "trade"),
                @Index(name = "idx_checklist_template_active", columnList = "active")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class ChecklistTemplate implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade", nullable = false, length = 50)
    private InspectionTrade trade;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Revision counter, bumped by the service on every edit. Never a key. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("lineOrder ASC")
    private List<ChecklistTemplateItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void addItem(ChecklistTemplateItem item) {
        item.setTemplate(this);
        item.setLineOrder(this.items.size());
        this.items.add(item);
    }
}
