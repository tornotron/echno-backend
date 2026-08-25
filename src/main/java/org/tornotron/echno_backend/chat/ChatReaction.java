package org.tornotron.echno_backend.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

/**
 * A single emoji reaction placed by one employee on one {@link ChatMessage}. A reaction is a
 * row rather than a counter so the DTO can list which employees reacted; the unique
 * constraint on {@code (message_id, employee_id, emoji)} keeps a toggle idempotent, one row
 * per employee per emoji per message.
 */
@Entity
@Table(
        name = "chat_reactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_reaction_message_employee_emoji",
                columnNames = {"message_id", "employee_id", "emoji"})
)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ChatReaction implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "emoji", nullable = false, length = 32)
    private String emoji;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
