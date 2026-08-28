package org.tornotron.echno_backend.inspection.domain;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.inspection.DefectAnnotationShape;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A mark drawn over one defect photo: the shape, where it sits on the image and
 * what it points out.
 *
 * <h2>Why the annotation is keyed by the photo and not by the defect row</h2>
 *
 * <p>The obvious model is a child collection on {@link InspectionDefect}. It does
 * not work here, and not for a stylistic reason. {@code InspectionService.update}
 * clears the inspection's defects and rebuilds them from the payload on every
 * save, so with {@code orphanRemoval} every defect row is deleted and reinserted
 * under a new id each time an inspector saves. Anything hanging off the defect row
 * is destroyed with it. The {@code ncrs} changelog already records this ("an
 * inspection's defects are rebuilt wholesale on every save") and is why an NCR
 * keeps a scalar {@code defectId} rather than a foreign key.
 *
 * <p>So the annotation hangs off the thing that is actually stable and that it
 * actually describes: the image. {@link #photo} is the stored photo reference,
 * byte for byte the string that appears in the defect's {@code photos} list, and
 * {@link #inspectionId} is the scalar link that scopes and authorizes it, matching
 * how {@link Ncr} refers to its inspection. A defect rebuild carries the same photo
 * strings through unchanged, so the annotations survive it.
 *
 * <h2>What happens when the image is replaced</h2>
 *
 * <p>They do not survive that, deliberately. A replacement photo is a new object
 * with a new key, so it arrives as a different {@link #photo} value and the old
 * annotations no longer match anything. Carrying them over would be worse than
 * losing them: the geometry describes a region of specific pixels, and an arrow
 * that pointed at a crack now points at bare wall while still claiming to be
 * evidence. {@code DefectAnnotationService} therefore deletes the annotations
 * whose photo is no longer attached to any defect on the inspection.
 *
 * <h2>Coordinates</h2>
 *
 * <p>The four numbers are fractions of the image's width and height, in
 * {@code [0, 1]}, never pixels. The image can then be rendered at any size (a
 * thumbnail in the web app, a half-page plate in the PDF) and the mark stays where
 * it was drawn, with no stored dependence on the resolution of the file or on the
 * device that took it. See {@link DefectAnnotationShape} for how the two points
 * are read per shape.
 */
@Entity
@Table(name = "inspection_defect_annotations",
        indexes = {
                @Index(name = "idx_defect_annotation_inspection",
                        columnList = "organization_id, inspection_id"),
                @Index(name = "idx_defect_annotation_photo",
                        columnList = "organization_id, inspection_id, photo")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class DefectPhotoAnnotation implements TenantScopedEntity {

    /** Scale the normalized coordinates are stored at: enough for sub-pixel placement on a 4K image. */
    public static final int COORDINATE_SCALE = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "inspection_id", nullable = false)
    private UUID inspectionId;

    /**
     * The photo this mark is drawn on, as the defect stores it. Not a foreign key:
     * {@code inspection_defect_photos} is an element collection with no stable row
     * identity of its own.
     */
    @Column(name = "photo", nullable = false, length = 500)
    private String photo;

    @Enumerated(EnumType.STRING)
    @Column(name = "shape", nullable = false, length = 20)
    private DefectAnnotationShape shape;

    @Column(name = "x1", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal x1;

    @Column(name = "y1", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal y1;

    @Column(name = "x2", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal x2;

    @Column(name = "y2", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal y2;

    /** What the mark points out, printed beside it on the report. */
    @Column(name = "label", length = 200)
    private String label;

    /** Draw and print order within one photo. */
    @Column(name = "line_order", nullable = false)
    private int lineOrder;

    /** Scalar employee reference, as elsewhere in this module. */
    @Column(name = "created_by_id")
    private Long createdById;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
