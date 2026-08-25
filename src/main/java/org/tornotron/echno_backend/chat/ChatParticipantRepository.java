package org.tornotron.echno_backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    Optional<ChatParticipant> findByRoom_IdAndEmployeeId(Long roomId, Long employeeId);

    boolean existsByRoom_IdAndEmployeeId(Long roomId, Long employeeId);
}
