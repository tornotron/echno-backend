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
 * A chat room: either a one-to-one {@code direct} conversation or a {@code group} room.
 * The kind is stored as a plain string ({@code direct}/{@code group}) because the web
 * client sends and reads its own lowercase vocabulary rather than a Java enum identifier.
 *
 * <p>The optional {@code projectId} links a group room to a project; it is kept as a plain
 * nullable column rather than a JPA relationship, keeping the module self-contained.
 * Participants are the employees who can see and post to the room.
 */
@Entity
@Table(name = "chat_rooms")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ChatRoom implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "name")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "is_archived", nullable = false)
    private boolean archived = false;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ChatParticipant> participants = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void addParticipant(ChatParticipant participant) {
        participant.setRoom(this);
        this.participants.add(participant);
    }
}
