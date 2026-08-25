package org.tornotron.echno_backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatReactionRepository extends JpaRepository<ChatReaction, Long> {

    /** The reaction row for a given message, employee and emoji, if one exists (drives toggle). */
    Optional<ChatReaction> findByMessage_IdAndEmployeeIdAndEmoji(Long messageId, Long employeeId, String emoji);

    /** All reactions on a message, used to build the grouped reaction list on the DTO. */
    List<ChatReaction> findByMessage_Id(Long messageId);

    /** All reactions across a set of messages, so a page of messages resolves in one query. */
    List<ChatReaction> findByMessage_IdIn(List<Long> messageIds);
}
