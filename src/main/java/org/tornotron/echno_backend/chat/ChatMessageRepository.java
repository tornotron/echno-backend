package org.tornotron.echno_backend.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findByIdAndOrganization_Id(Long id, Long organizationId);

    /** The latest non-deleted message in a room, used as the room's {@code lastMessage}. */
    Optional<ChatMessage> findFirstByRoom_IdAndDeletedFalseOrderByCreatedAtDesc(Long roomId);

    /** A page of non-deleted messages in a room, ordered by the {@link Pageable}'s sort. */
    Page<ChatMessage> findByRoom_IdAndDeletedFalse(Long roomId, Pageable pageable);

    /**
     * Unread count for a caller who has never read the room: every non-deleted message not
     * authored by them.
     */
    @Query("""
            SELECT COUNT(m) FROM ChatMessage m
            WHERE m.room.id = :roomId
              AND m.deleted = false
              AND m.senderId <> :employeeId
            """)
    long countUnreadAll(@Param("roomId") Long roomId,
                        @Param("employeeId") Long employeeId);

    /**
     * Unread count since a known last-read instant: non-deleted messages created after
     * {@code lastReadAt} and not authored by the caller.
     */
    @Query("""
            SELECT COUNT(m) FROM ChatMessage m
            WHERE m.room.id = :roomId
              AND m.deleted = false
              AND m.senderId <> :employeeId
              AND m.createdAt > :lastReadAt
            """)
    long countUnreadSince(@Param("roomId") Long roomId,
                          @Param("employeeId") Long employeeId,
                          @Param("lastReadAt") LocalDateTime lastReadAt);

    /**
     * Messages in the room the caller has not read, not authored by them and not deleted.
     * Dispatches on {@code lastReadAt} rather than binding a nullable timestamp inside a
     * {@code :x IS NULL} clause, which CockroachDB rejects because it cannot type the null
     * placeholder.
     */
    default long countUnread(Long roomId, Long employeeId, LocalDateTime lastReadAt) {
        return lastReadAt == null
                ? countUnreadAll(roomId, employeeId)
                : countUnreadSince(roomId, employeeId, lastReadAt);
    }
}
