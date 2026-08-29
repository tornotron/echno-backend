package org.tornotron.echno_backend.common.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing Attachment entities.
 */
@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByEntityTypeAndEntityId(String entityType, Long entityId);
    /**
     * Deletes all attachments for a specific entity.
     *
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     */
    void deleteByEntityTypeAndEntityId(String entityType, Long entityId);

    /**
     * Checks if an entity has any attachments.
     *
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     * @return true if attachments exist
     */
    boolean existsByEntityTypeAndEntityId(String entityType, Long entityId);

    /**
     * Counts attachments for a specific entity.
     *
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     * @return Number of attachments
     */
    long countByEntityTypeAndEntityId(String entityType, Long entityId);

    boolean existsByEntityTypeAndEntityIdAndOriginalFilenameAndFileSize(
            String entityType, Long entityId, String originalFilename, Long fileSize);

    /**
     * Every file filed against a UUID-keyed record, oldest first.
     *
     * @param entityType The attachment entity type, for example INSPECTION_EVIDENCE
     * @param entityUuid The record's UUID
     * @return The attachments, in the order they were recorded
     */
    List<Attachment> findByEntityTypeAndEntityUuidOrderByIdAsc(String entityType, UUID entityUuid);

    /**
     * Whether a UUID-keyed record already carries a file of this name and size. The same
     * duplicate guard the numeric path applies, keyed on the other column.
     *
     * @param entityType       The attachment entity type
     * @param entityUuid       The record's UUID
     * @param originalFilename The uploaded filename
     * @param fileSize         The uploaded size in bytes
     * @return true when an identical file is already filed against that record
     */
    boolean existsByEntityTypeAndEntityUuidAndOriginalFilenameAndFileSize(
            String entityType, UUID entityUuid, String originalFilename, Long fileSize);

    /**
     * One attachment by id, refusing to cross tenants. The unscoped {@code findById} lets a
     * member of one organization reach another's attachment by numeric id, so every path this
     * change adds uses this one.
     *
     * @param id           The attachment id
     * @param organizationId The organization the caller is acting in
     * @return The attachment, if it belongs to that organization
     */
    Optional<Attachment> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Documents of one entity type whose expiry falls on or before a cutoff, soonest first.
     * Already-expired documents come back too, since a policy that lapsed last week is exactly
     * what the caller is looking for.
     *
     * @param entityType     The attachment entity type, for example ASSET_DOCUMENTS
     * @param organizationId The organization the caller is acting in
     * @param cutoff         The latest expiry date to include
     * @param pageable       Bound on the rows returned
     * @return The matching attachments, ordered by expiry
     */
    List<Attachment> findByEntityTypeAndOrganization_IdAndExpiresOnLessThanEqualOrderByExpiresOnAscIdAsc(
            String entityType, Long organizationId, LocalDate cutoff, Pageable pageable);
}
