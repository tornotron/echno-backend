package org.tornotron.echno_backend.modules.inspections.domain;

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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.inspections.RegionOrigin;
import org.tornotron.echno_backend.modules.inspections.RegionShape;
import org.tornotron.echno_backend.organization.Organization;

/**
 * A box or polygon on one image, with the class it names and the producer's score: the
 * geometry of an {@link Observation}. One observation is one finding, so a model that sees
 * five helmets on a photo posts five observations of one region each, and each is accepted
 * or rejected on its own. An observation may still carry several regions (the same crack in
 * two photos, or a region the reviewer added because the model missed it).
 *
 * <p>Rows are never updated in place. A reviewer's correction inserts a {@link RegionOrigin#REVIEWER}
 * row that {@link #supersedesId supersedes} the producer's and retires the old one, so the
 * prediction and the correction both survive for the training export.
 *
 * <p>Coordinates follow {@link DefectPhotoAnnotation}: fractions of the image's width and height
 * in {@code [0, 1]} at scale 6, origin top left, x to the right, y down, measured on the image as
 * displayed (after EXIF orientation). A polygon's bounding box is held in the same four columns,
 * so filtering and cropping read one shape. {@code attachmentId} is a real FK with on delete
 * restrict: the image under a decision record cannot be deleted from under it.
 * Design: {@code docs/specs/2026-10-26-cv-phase4-design.md}, section 4.1.
 */
@Entity
@Table(name = "inspection_observation_regions",
        indexes = {
                @Index(name = "idx_insp_obs_region_observation", columnList = "observation_id"),
                @Index(name = "idx_insp_obs_region_attachment", columnList = "attachment_id"),
                @Index(name = "idx_insp_obs_region_org_class", columnList = "organization_id, class_key")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ObservationRegion implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "observation_id", nullable = false)
    private UUID observationId;

    // The image the region is drawn on.
    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "shape", nullable = false, length = 10)
    private RegionShape shape;

    @Column(name = "x1", nullable = false, precision = 9, scale = DefectPhotoAnnotation.COORDINATE_SCALE)
    private BigDecimal x1;

    @Column(name = "y1", nullable = false, precision = 9, scale = DefectPhotoAnnotation.COORDINATE_SCALE)
    private BigDecimal y1;

    @Column(name = "x2", nullable = false, precision = 9, scale = DefectPhotoAnnotation.COORDINATE_SCALE)
    private BigDecimal x2;

    @Column(name = "y2", nullable = false, precision = 9, scale = DefectPhotoAnnotation.COORDINATE_SCALE)
    private BigDecimal y2;

    // POLYGON only: [[x, y], ...] as fractions, open (the first vertex is not repeated).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points", columnDefinition = "jsonb")
    private List<List<BigDecimal>> points;

    // Pixel size of the image as displayed, when the producer knows it.
    @Column(name = "image_width")
    private Integer imageWidth;

    @Column(name = "image_height")
    private Integer imageHeight;

    @Column(name = "class_key", nullable = false, length = 50)
    private String classKey;

    // The producer's score in [0, 1]; null for a region a person drew.
    @Column(name = "score", precision = 5, scale = 4)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    private RegionOrigin origin;

    // The row this one corrects, for a reviewer's replace.
    @Column(name = "supersedes_id")
    private UUID supersedesId;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    // Employee who retired it, by replacing or removing it in a review.
    @Column(name = "retired_by_id")
    private Long retiredById;

    /** Draw order on the observation; a replacement takes the place of the row it supersedes. */
    @Column(name = "line_order", nullable = false)
    private int lineOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Employee who drew it; null for a producer's region.
    @Column(name = "created_by_id")
    private Long createdById;

    public boolean isActive() {
        return retiredAt == null;
    }
}
