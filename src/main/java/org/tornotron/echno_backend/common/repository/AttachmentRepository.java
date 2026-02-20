package org.tornotron.echno_backend.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;

import java.util.List;

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
}
