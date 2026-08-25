package org.tornotron.echno_backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * All rooms the given employee participates in, newest activity first. The tenant
     * orgFilter still applies to the root {@link ChatRoom}, so this never crosses tenants.
     */
    @Query("""
            SELECT r FROM ChatRoom r
            WHERE r.id IN (SELECT p.room.id FROM ChatParticipant p WHERE p.employeeId = :employeeId)
            ORDER BY r.updatedAt DESC
            """)
    List<ChatRoom> findRoomsForEmployee(@Param("employeeId") Long employeeId);

    /**
     * The existing one-to-one direct room whose two participants are exactly {@code a} and
     * {@code b}, if any. Guards on the participant count being two so a group room that
     * happens to contain both is never returned.
     */
    @Query("""
            SELECT r FROM ChatRoom r
            WHERE r.type = 'direct'
              AND (SELECT COUNT(p) FROM ChatParticipant p WHERE p.room = r) = 2
              AND EXISTS (SELECT 1 FROM ChatParticipant pa WHERE pa.room = r AND pa.employeeId = :a)
              AND EXISTS (SELECT 1 FROM ChatParticipant pb WHERE pb.room = r AND pb.employeeId = :b)
            """)
    List<ChatRoom> findDirectRoomBetween(@Param("a") Long a, @Param("b") Long b);
}
