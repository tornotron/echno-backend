package org.tornotron.echno_backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    Optional<ChatParticipant> findByRoom_IdAndEmployeeId(Long roomId, Long employeeId);

    boolean existsByRoom_IdAndEmployeeId(Long roomId, Long employeeId);

    /**
     * The employee ids taking part in a room. Used to address a real-time event to the people
     * who should see it, resolved inside the writing transaction so the listener that publishes
     * after commit performs no tenant-scoped read of its own.
     */
    @Query("select p.employeeId from ChatParticipant p where p.room.id = :roomId")
    List<Long> findEmployeeIdsByRoomId(@Param("roomId") Long roomId);
}
