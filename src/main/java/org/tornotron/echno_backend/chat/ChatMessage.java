package org.tornotron.echno_backend.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A message posted to a {@link ChatRoom}. {@code senderId} is the author's employee id.
 * {@code replyToId} is stored as a plain nullable id; the reply preview is resolved in the
 * service from the referenced message. Editing sets {@code isEdited} and {@code editedAt};
 * soft-delete sets {@code isDeleted} and keeps the row so the client renders a tombstone.
 *
 * <p>Rich content is parsed from the body on send: employee mentions ({@code @[Name](id)})
 * are kept as a list of employee ids, and entity mentions ({@code #[label](type:id)}) as an
 * element collection. Emoji reactions and file attachments are separate rows referencing
 * this message.
 */
@Entity
@Table(name = "chat_messages")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "reply_to_id")
    private Long replyToId;

    @Column(name = "is_edited", nullable = false)
    private boolean edited = false;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "chat_message_mentions", joinColumns = @JoinColumn(name = "message_id"))
    @Column(name = "employee_id", nullable = false)
    private List<Long> mentions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "chat_message_entity_mentions", joinColumns = @JoinColumn(name = "message_id"))
    private List<ChatEntityMention> entityMentions = new ArrayList<>();

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
