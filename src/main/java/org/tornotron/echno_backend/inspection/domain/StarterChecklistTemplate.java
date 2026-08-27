package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.inspection.InspectionTrade;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The default per-trade checklist a new organization starts from. This is GLOBAL
 * reference data shipped with the product and is deliberately NOT tenant-scoped
 * (no organization_id, no {@code orgFilter}), following the
 * {@code ComplianceRule} precedent: the check points for a rebar or waterproofing
 * inspection are the same trade practice whichever org is building.
 *
 * <p>It is a starting point, never the live definition. An org adopts a starter,
 * which copies it into a tenant-scoped {@link ChecklistTemplate} the org's QA
 * engineer then edits. The two are independent from that moment: a later revision
 * of the starter does not reach into any org's template, and an org's edits are
 * never pushed back here.
 *
 * <p>One starter per trade, enforced by the unique constraint on {@code trade}.
 */
@Entity
@Table(name = "starter_checklist_templates",
        uniqueConstraints = @UniqueConstraint(name = "uk_starter_checklist_template_trade",
                columnNames = {"trade"}),
        indexes = @Index(name = "idx_starter_checklist_template_active", columnList = "active"))
@Getter @Setter
@NoArgsConstructor
public class StarterChecklistTemplate {

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

    @OneToMany(mappedBy = "template", fetch = FetchType.LAZY)
    @OrderBy("lineOrder ASC")
    private List<StarterChecklistTemplateItem> items = new ArrayList<>();
}
