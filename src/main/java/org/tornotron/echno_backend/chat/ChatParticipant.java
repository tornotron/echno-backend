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
 * Membership of an employee in a {@link ChatRoom}. {@code employeeId} is the employee's id
 * (chat is employee-centric, not user-centric); {@code lastReadAt} marks how far the
 * employee has read the room, and drives the unread count. The role is a plain string
 * ({@code member}/{@code admin}) matching the web vocabulary.
 */
@Entity
@Table(
        name = "chat_participants",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_participant_room_employee",
                columnNames = {"room_id", "employee_id"})
)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ChatParticipant implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "role", nullable = false, length = 20)
    private String role = "member";

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;
}
