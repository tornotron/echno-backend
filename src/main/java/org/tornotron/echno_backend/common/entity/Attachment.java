package org.tornotron.echno_backend.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Generic attachment entity for storing file references across different modules.
 * Uses a polymorphic design with entityType and entityId to link to any parent entity.
 * 
 * Example usage:
 * - entityType = "PROJECT", entityId = 123 -> links to Project with id 123
 * - entityType = "TASK", entityId = 456 -> links to Task with id 456
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "attachment", indexes = {
        @Index(name = "idx_attachment_entity", columnList = "entity_type, entity_id")
})
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The type of entity this attachment belongs to (e.g., "PROJECT", "TASK", "DOCUMENT").
     */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * The ID of the entity this attachment belongs to.
     */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /**
     * The unique key/path of the file in the storage bucket.
     */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /**
     * The public URL to access the file.
     */
    @Column(name = "url", nullable = true, length = 1024)
    private String url;

    /**
     * The MIME type of the file (e.g., "image/png", "application/pdf").
     */
    @Column(name = "content_type", length = 100)
    private String contentType;

    /**
     * The size of the file in bytes.
     */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * The original filename as uploaded by the user.
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * Timestamp when the attachment was created.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
