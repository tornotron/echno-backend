package org.tornotron.echno_backend.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

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
        @Index(name = "idx_attachment_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_attachment_entity_uuid", columnList = "organization_id, entity_type, entity_uuid"),
        @Index(name = "idx_attachment_expiry", columnList = "organization_id, entity_type, expires_on")
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Attachment implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The type of entity this attachment belongs to (e.g., "PROJECT", "TASK", "DOCUMENT").
     */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * The numeric id of the entity this attachment belongs to, for the modules keyed that way.
     *
     * <p>Null on a file filed against a UUID-keyed record; {@link #entityUuid} carries the key
     * instead. A database check constraint requires exactly one of the two, so a file cannot
     * arrive naming neither and become invisible to every read while still occupying storage.
     */
    @Column(name = "entity_id")
    private Long entityId;

    /**
     * The UUID of the entity this attachment belongs to, for the modules keyed that way.
     *
     * <p>The inspection module is keyed by UUID throughout, and a UUID does not fit the BIGINT
     * {@link #entityId}, so before this column an inspection had nowhere to file evidence at all.
     * It sits on the shared table rather than inspections getting an evidence table of their own,
     * for the reason the document columns do: the upload, presign, register, list and delete
     * plumbing is already generic, so every UUID-keyed module inherits the fix rather than
     * reimplementing it.
     */
    @Column(name = "entity_uuid")
    private UUID entityUuid;

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
     * What kind of document this file is, where the file is a document rather than a photo:
     * {@code insurance}, {@code warranty}, {@code registration}, {@code certification},
     * {@code service-record}, {@code purchase-invoice}. Free-form for the same reason
     * {@link #entityType} is, and for the same reason the asset module stores its type and
     * category as strings: the web client sends kebab-case values and validates them.
     */
    @Column(name = "document_type", length = 50)
    private String documentType;

    /** The date the document was issued, where that is worth recording. */
    @Column(name = "issued_on")
    private LocalDate issuedOn;

    /**
     * The date the document stops being valid. Null for a file that does not expire, which is
     * most of them. This is the column that makes an insurance policy or a certification
     * trackable rather than merely stored.
     */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /**
     * Timestamp when the attachment was created.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id")
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id")
    private Attendance attendance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clock_event_id")
    private ClockEvent clockEvent;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
