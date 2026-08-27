package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.inspection.CheckItemStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single check point within an inspection. Owned by {@link Inspection} through
 * the {@code inspection_id} FK; the parent cascades persist and removal. Tenant
 * scoping is inherited from the owning inspection, which carries the org filter.
 */
@Entity
@Table(name = "inspection_check_items",
        indexes = @Index(name = "idx_insp_check_item_inspection", columnList = "inspection_id"))
@Getter
@Setter
@NoArgsConstructor
public class InspectionCheckItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false)
    private Inspection inspection;

    @Column(nullable = false, length = 200)
    private String category;

    @Column(name = "check_point", nullable = false, length = 500)
    private String checkPoint;

    @Column(length = 1000)
    private String specification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CheckItemStatus status = CheckItemStatus.PENDING;

    @Column(length = 1000)
    private String remarks;

    @Column(name = "photos_required", nullable = false)
    private boolean photosRequired;

    @ElementCollection
    @CollectionTable(name = "inspection_check_item_photos",
            joinColumns = @JoinColumn(name = "check_item_id"))
    @Column(name = "photo", length = 500)
    private List<String> photos = new ArrayList<>();

    @Column(length = 200)
    private String measurement;

    @Column(name = "expected_value", length = 200)
    private String expectedValue;

    // The measurable criterion copied from the checklist template this check point
    // came from: what makes it pass, and the band around the expected value.
    @Column(name = "acceptance_criterion", length = 1000)
    private String acceptanceCriterion;

    @Column(name = "tolerance", length = 100)
    private String tolerance;

    // measurement minus expectedValue, computed server-side by MeasurementDeviation
    // whenever both are numeric and carry the same unit. Null when either is absent
    // or non-numeric, which is the normal case for a qualitative check point.
    @Column(name = "deviation", precision = 19, scale = 4)
    private BigDecimal deviation;

    // IFC GlobalId of the BIM element this check point was carried out against.
    // Nullable and unused until the BIM phase; the column exists now so the link is
    // already in place when the viewer lands and needs no migration of live rows.
    @Column(name = "bim_element_guid", length = 100)
    private String bimElementGuid;

    // Free-form priority ('high' | 'medium' | 'low'), kept as text to match the
    // web contract's inline string union.
    @Column(length = 20)
    private String priority = "medium";

    @Column(name = "line_order", nullable = false)
    private int lineOrder;
}
