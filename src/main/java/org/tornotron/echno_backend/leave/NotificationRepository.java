package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.leave.enums.NotificationType;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId, Pageable pageable);

    List<Notification> findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(Long recipientId);

    Optional<Notification> findByIdAndOrganization_Id(Long id, Long organizationId);

    Page<Notification> findByRecipientIdAndIsReadFalse(Long recipientId, Pageable pageable);

    long countByRecipientIdAndIsReadFalse(Long recipientId);

    // Bulk DML does not honor the Hibernate orgFilter, so scope by organization
    // explicitly for defence in depth (the recipient predicate already limits it
    // to the caller's own notifications).
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP " +
           "WHERE n.recipient.id = :recipientId AND n.organization.id = :organizationId AND n.isRead = false")
    int markAllAsReadByRecipientId(@Param("recipientId") Long recipientId,
                                   @Param("organizationId") Long organizationId);

    List<Notification> findByEntityTypeAndEntityId(String entityType, Long entityId);

    @Query("SELECT n FROM Notification n " +
           "WHERE n.recipient.id = :recipientId " +
           "AND n.notificationType IN :types " +
           "ORDER BY n.createdAt DESC")
    List<Notification> findByRecipientIdAndTypes(
            @Param("recipientId") Long recipientId,
            @Param("types") List<NotificationType> types);

    void deleteByEntityTypeAndEntityId(String entityType, Long entityId);
}
