package org.tornotron.echno_backend.chat.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.chat.ChatEntityMention;
import org.tornotron.echno_backend.chat.ChatMessage;
import org.tornotron.echno_backend.chat.ChatParticipant;
import org.tornotron.echno_backend.chat.ChatRoom;
import org.tornotron.echno_backend.chat.dto.ChatEntityMentionDto;
import org.tornotron.echno_backend.chat.dto.ChatMessageDto;
import org.tornotron.echno_backend.chat.dto.ChatParticipantDto;
import org.tornotron.echno_backend.chat.dto.ChatRoomDto;

/**
 * Maps the chat entities to their response DTOs. The room's {@code unreadCount} and
 * {@code lastMessage} are computed in the service and set after mapping, so they are
 * ignored here. The message's {@code mentions} and {@code entityMentions} map straight from
 * the entity; its {@code reactions}, {@code attachments} and {@code replyTo} preview are
 * resolved and set in the service, so they are ignored here.
 */
@Mapper(componentModel = "spring")
public interface ChatMapper {

    @Mapping(target = "unreadCount", ignore = true)
    @Mapping(target = "lastMessage", ignore = true)
    ChatRoomDto toDto(ChatRoom room);

    ChatParticipantDto toDto(ChatParticipant participant);

    @Mapping(source = "room.id", target = "roomId")
    @Mapping(target = "replyTo", ignore = true)
    @Mapping(target = "reactions", ignore = true)
    @Mapping(target = "attachments", ignore = true)
    ChatMessageDto toDto(ChatMessage message);

    ChatEntityMentionDto toDto(ChatEntityMention mention);
}
