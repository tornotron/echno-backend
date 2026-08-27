package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One check point in a {@link ChecklistTemplate}. Owned by the template through
 * the {@code template_id} FK; the parent cascades persist and removal. Tenant
 * scoping is inherited from the owning template, which carries the org filter,
 * the same way {@link InspectionCheckItem} inherits it from its inspection.
 *
 * <p>{@code acceptanceCriterion} and {@code tolerance} are the measurable form of
 * a criterion: the criterion states what makes the check point pass ("rebar
 * spacing matches the bar bending schedule"), {@code expectedValue} is the target
 * ("150 mm"), and {@code tolerance} is the band around it ("+/- 10 mm"). They are
 * copied onto the inspection's check items unchanged, where the recorded
 * measurement is judged against them.
 */
@Entity
@Table(name = "checklist_template_items",
        indexes = @Index(name = "idx_checklist_template_item_template", columnList = "template_id"))
@Getter
@Setter
@NoArgsConstructor
public class ChecklistTemplateItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ChecklistTemplate template;

    @Column(nullable = false, length = 200)
    private String category;

    @Column(name = "check_point", nullable = false, length = 500)
    private String checkPoint;

    @Column(length = 1000)
    private String specification;

    @Column(name = "expected_value", length = 200)
    private String expectedValue;

    @Column(name = "acceptance_criterion", length = 1000)
    private String acceptanceCriterion;

    @Column(name = "tolerance", length = 100)
    private String tolerance;

    @Column(name = "photos_required", nullable = false)
    private boolean photosRequired;

    // Free-form priority, matching the same field on InspectionCheckItem so
    // instantiation is a straight copy. That means the same three tokens and no
    // others: 'high' | 'medium' | 'low' is what the web contract renders, so a
    // fourth value here would reach the UI as an unrecognised label. How serious a
    // failure of the check point is belongs in the acceptance criterion, not here.
    @Column(length = 20)
    private String priority = "medium";

    @Column(name = "line_order", nullable = false)
    private int lineOrder;
}
