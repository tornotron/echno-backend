package org.tornotron.echno_backend.chat.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.chat.ChatMessage;
import org.tornotron.echno_backend.chat.ChatParticipant;
import org.tornotron.echno_backend.chat.ChatRoom;
import org.tornotron.echno_backend.chat.dto.ChatMessageDto;
import org.tornotron.echno_backend.chat.dto.ChatParticipantDto;
import org.tornotron.echno_backend.chat.dto.ChatRoomDto;

/**
 * Maps the chat entities to their response DTOs. The room's {@code unreadCount} and
 * {@code lastMessage} are computed in the service and set after mapping, so they are
 * ignored here. The message's deferred rich fields (mentions, reactions, attachments and
 * entity mentions) keep the DTO's empty-array defaults.
 */
@Mapper(componentModel = "spring")
public interface ChatMapper {

    @Mapping(target = "unreadCount", ignore = true)
    @Mapping(target = "lastMessage", ignore = true)
    ChatRoomDto toDto(ChatRoom room);

    ChatParticipantDto toDto(ChatParticipant participant);

    @Mapping(source = "room.id", target = "roomId")
    @Mapping(target = "mentions", ignore = true)
    @Mapping(target = "entityMentions", ignore = true)
    @Mapping(target = "reactions", ignore = true)
    @Mapping(target = "attachments", ignore = true)
    ChatMessageDto toDto(ChatMessage message);
}
