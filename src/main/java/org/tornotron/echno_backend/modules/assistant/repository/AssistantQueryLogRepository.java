package org.tornotron.echno_backend.modules.assistant.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantQueryLog;

/**
 * Reads and writes of the audit log. Every read is paged, newest first, and runs under the tenant
 * filter when called from a transactional service, so a caller sees only their own organization's
 * rows.
 */
public interface AssistantQueryLogRepository extends JpaRepository<AssistantQueryLog, UUID> {

    Page<AssistantQueryLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<AssistantQueryLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** A row by id, only if the same user asked it: the follow-up window never reads anyone else's. */
    Optional<AssistantQueryLog> findByIdAndUserId(UUID id, Long userId);
}
