package org.tornotron.echno_backend.inspection.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.inspection.domain.DefectPhotoAnnotation;

import java.util.Collection;
import java.util.UUID;

/**
 * Reads and writes of the marks drawn over defect photos.
 *
 * <p>Every read is by inspection and takes a {@code Pageable}. There is no
 * whole-table read here and there must not be one: the table grows with every
 * inspection a tenant ever marks up.
 *
 * <p>The two deletes name the organization explicitly instead of leaning on the
 * Hibernate {@code orgFilter}. The filter is applied when Hibernate loads an
 * entity or a collection; a bulk {@code DELETE} statement is not a load and the
 * filter does not reach it. Leaving tenancy to the caller's earlier lookup would
 * work today and would be one refactor away from a cross-tenant delete, so the
 * predicate is in the statement.
 */
@Repository
public interface DefectPhotoAnnotationRepository extends JpaRepository<DefectPhotoAnnotation, UUID> {

    /**
     * The marks on one inspection, grouped by photo and in draw order within a
     * photo, so the report prints them in a stable sequence.
     */
    @Query("SELECT a FROM DefectPhotoAnnotation a WHERE a.inspectionId = :inspectionId "
            + "ORDER BY a.photo ASC, a.lineOrder ASC")
    Page<DefectPhotoAnnotation> findByInspection(@Param("inspectionId") UUID inspectionId,
                                                 Pageable pageable);

    /** How many marks an inspection carries, for the report's truncation note. */
    @Query("SELECT COUNT(a) FROM DefectPhotoAnnotation a WHERE a.inspectionId = :inspectionId")
    long countByInspection(@Param("inspectionId") UUID inspectionId);

    /**
     * Drops every mark on one inspection.
     *
     * <p>Written as a bulk delete rather than a read-then-delete so replacing the
     * set does not load what it is about to discard.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DefectPhotoAnnotation a WHERE a.organization.id = :organizationId "
            + "AND a.inspectionId = :inspectionId")
    int deleteByInspection(@Param("organizationId") Long organizationId,
                           @Param("inspectionId") UUID inspectionId);

    /**
     * Drops the marks on an inspection whose photo is no longer attached to any of
     * its defects.
     *
     * <p>This is what runs when a photo is replaced. See
     * {@link DefectPhotoAnnotation} for why an annotation must not outlive the image
     * it was drawn on.
     *
     * @param organizationId The tenant the inspection belongs to.
     * @param inspectionId   The inspection whose marks are being swept.
     * @param keptPhotos     The photos still attached to the inspection's defects.
     *                       Must not be empty; the caller uses
     *                       {@link #deleteByInspection} when nothing is left.
     * @return How many marks were dropped.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DefectPhotoAnnotation a WHERE a.organization.id = :organizationId "
            + "AND a.inspectionId = :inspectionId AND a.photo NOT IN :keptPhotos")
    int deleteOrphansByInspection(@Param("organizationId") Long organizationId,
                                  @Param("inspectionId") UUID inspectionId,
                                  @Param("keptPhotos") Collection<String> keptPhotos);
}
