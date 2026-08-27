package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One check point in a {@link StarterChecklistTemplate}. Global reference data,
 * read-only to the application: the rows are shipped by a Liquibase seed, exactly
 * as the curated compliance rules are, and no endpoint writes them. The parent
 * therefore declares no cascade, and this side carries no tenancy of its own.
 */
@Entity
@Table(name = "starter_checklist_template_items",
        indexes = @Index(name = "idx_starter_checklist_item_template", columnList = "template_id"))
@Getter
@Setter
@NoArgsConstructor
public class StarterChecklistTemplateItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private StarterChecklistTemplate template;

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

    @Column(length = 20)
    private String priority = "medium";

    @Column(name = "line_order", nullable = false)
    private int lineOrder;
}
