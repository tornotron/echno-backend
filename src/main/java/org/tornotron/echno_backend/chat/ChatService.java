package org.tornotron.echno_backend.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.chat.dto.ChatMessageDto;
import org.tornotron.echno_backend.chat.dto.ChatRoomDto;
import org.tornotron.echno_backend.chat.mapper.ChatMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The chat core flow: rooms an employee belongs to, direct-room open-or-create, marking a
 * room read, and the send/edit message path. Everything is scoped to the current tenant and
 * to the caller's participation; the caller is resolved to their {@link Employee} because
 * chat is employee-centric (senders and participants are employee ids, not user ids).
 *
 * <p>Deferred and intentionally absent here: reactions, mentions, entity mentions,
 * attachments, reply-preview resolution, message delete, archive toggle and any real-time
 * transport. The web client polls.
 */
@Service
public class ChatService {

    private static final String ROOM_DIRECT = "direct";
    private static final String ROLE_MEMBER = "member";

    private final ChatRoomRepository roomRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMapper chatMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final EmployeeRepository employeeRepository;

    public ChatService(ChatRoomRepository roomRepository,
                       ChatParticipantRepository participantRepository,
                       ChatMessageRepository messageRepository,
                       ChatMapper chatMapper,
                       TenantEntityHelper tenantEntityHelper,
                       UserContextService userContextService,
                       EmployeeRepository employeeRepository) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.chatMapper = chatMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
        this.employeeRepository = employeeRepository;
    }

    @Transactional(readOnly = true)
    public List<ChatRoomDto> getRoomsForCurrentEmployee() {
        Long employeeId = resolveCurrentEmployeeId();
        return roomRepository.findRoomsForEmployee(employeeId).stream()
                .map(room -> toRoomDto(room, employeeId))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatRoomDto getRoom(Long roomId) {
        Long employeeId = resolveCurrentEmployeeId();
        ChatRoom room = requireRoom(roomId);
        requireParticipant(roomId, employeeId);
        return toRoomDto(room, employeeId);
    }

    /**
     * Opens the one-to-one direct room with {@code otherEmployeeId}, reusing the existing one
     * when present. Idempotent: calling it twice returns the same room.
     */
    @Transactional
    public ChatRoomDto openDirectRoom(Long otherEmployeeId) {
        Long employeeId = resolveCurrentEmployeeId();
        if (otherEmployeeId == null || otherEmployeeId.equals(employeeId)) {
            throw new IllegalArgumentException("A direct room needs a different employee");
        }

        List<ChatRoom> existing = roomRepository.findDirectRoomBetween(employeeId, otherEmployeeId);
        if (!existing.isEmpty()) {
            return toRoomDto(existing.get(0), employeeId);
        }

        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        ChatRoom room = new ChatRoom();
        room.setType(ROOM_DIRECT);
        room.setOrganization(organization);
        room.addParticipant(newParticipant(employeeId, organization));
        room.addParticipant(newParticipant(otherEmployeeId, organization));

        ChatRoom saved = roomRepository.saveAndFlush(room);
        return toRoomDto(saved, employeeId);
    }

    /** Marks the room read for the caller by advancing their {@code lastReadAt} to now. */
    @Transactional
    public void markRead(Long roomId) {
        Long employeeId = resolveCurrentEmployeeId();
        requireRoom(roomId);
        ChatParticipant participant = requireParticipant(roomId, employeeId);
        participant.setLastReadAt(LocalDateTime.now());
        participantRepository.save(participant);
    }

    @Transactional(readOnly = true)
    public Page<ChatMessageDto> getMessages(Long roomId, int pageNo, int pageSize) {
        Long employeeId = resolveCurrentEmployeeId();
        requireRoom(roomId);
        requireParticipant(roomId, employeeId);
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return messageRepository.findByRoom_IdAndDeletedFalse(roomId, pageable)
                .map(chatMapper::toDto);
    }

    @Transactional
    public ChatMessageDto sendMessage(Long roomId, String content, Long replyToId) {
        Long employeeId = resolveCurrentEmployeeId();
        ChatRoom room = requireRoom(roomId);
        requireParticipant(roomId, employeeId);

        ChatMessage message = new ChatMessage();
        message.setRoom(room);
        message.setOrganization(room.getOrganization());
        message.setSenderId(employeeId);
        message.setContent(content);
        message.setReplyToId(replyToId);

        // Bump the room so it sorts to the top of the caller's list and updatedAt reflects
        // the new activity.
        room.setUpdatedAt(LocalDateTime.now());
        roomRepository.save(room);

        // saveAndFlush before mapping so the generated id and @CreationTimestamp land on the DTO.
        ChatMessage saved = messageRepository.saveAndFlush(message);
        return chatMapper.toDto(saved);
    }

    /** Edits the caller's own message. Only the sender may edit; others get a 403. */
    @Transactional
    public ChatMessageDto editMessage(Long messageId, String content) {
        Long employeeId = resolveCurrentEmployeeId();
        ChatMessage message = messageRepository
                .findByIdAndOrganization_Id(messageId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message with ID " + messageId + " was not found in this organization"));

        if (!employeeId.equals(message.getSenderId())) {
            throw new AccessDeniedException("Only the sender can edit this message");
        }

        message.setContent(content);
        message.setEdited(true);
        message.setEditedAt(LocalDateTime.now());

        ChatMessage saved = messageRepository.saveAndFlush(message);
        return chatMapper.toDto(saved);
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * Builds a room DTO for the given viewer, filling in the fields the mapper cannot: the
     * latest non-deleted message and the viewer's unread count.
     */
    private ChatRoomDto toRoomDto(ChatRoom room, Long viewerEmployeeId) {
        ChatRoomDto dto = chatMapper.toDto(room);

        messageRepository.findFirstByRoom_IdAndDeletedFalseOrderByCreatedAtDesc(room.getId())
                .ifPresent(last -> dto.setLastMessage(chatMapper.toDto(last)));

        LocalDateTime lastReadAt = participantRepository
                .findByRoom_IdAndEmployeeId(room.getId(), viewerEmployeeId)
                .map(ChatParticipant::getLastReadAt)
                .orElse(null);
        long unread = messageRepository.countUnread(room.getId(), viewerEmployeeId, lastReadAt);
        dto.setUnreadCount((int) unread);

        return dto;
    }

    private ChatParticipant newParticipant(Long employeeId, Organization organization) {
        ChatParticipant participant = new ChatParticipant();
        participant.setEmployeeId(employeeId);
        participant.setRole(ROLE_MEMBER);
        participant.setOrganization(organization);
        return participant;
    }

    private ChatRoom requireRoom(Long roomId) {
        return roomRepository
                .findByIdAndOrganization_Id(roomId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Chat room with ID " + roomId + " was not found in this organization"));
    }

    private ChatParticipant requireParticipant(Long roomId, Long employeeId) {
        return participantRepository.findByRoom_IdAndEmployeeId(roomId, employeeId)
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not a participant of chat room " + roomId));
    }

    /**
     * Resolves the authenticated caller to their {@link Employee} id in the current tenant,
     * the same user-to-employee lookup the attendance and leave flows use. Chat is
     * employee-centric, so a caller with no employee record cannot participate.
     */
    private Long resolveCurrentEmployeeId() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            throw new AccessDeniedException("No authenticated user");
        }
        return employeeRepository
                .findByUserIdAndOrganizationId(userId, TenantContext.getCurrentOrgId())
                .map(Employee::getId)
                .orElseThrow(() -> new AccessDeniedException(
                        "No employee profile for the current user in this organization"));
    }
}
