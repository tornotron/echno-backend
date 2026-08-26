package org.tornotron.echno_backend.chat;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.chat.dto.ChatMessageDto;
import org.tornotron.echno_backend.chat.dto.ChatMessageReplyDto;
import org.tornotron.echno_backend.chat.dto.ChatReactionDto;
import org.tornotron.echno_backend.chat.dto.ChatRoomDto;
import org.tornotron.echno_backend.chat.mapper.ChatMapper;
import org.tornotron.echno_backend.chat.realtime.ChatEvent;
import org.tornotron.echno_backend.chat.realtime.ChatEventType;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chat core flow: rooms an employee belongs to, direct-room open-or-create, marking a
 * room read, and the send/edit message path. Everything is scoped to the current tenant and
 * to the caller's participation; the caller is resolved to their {@link Employee} because
 * chat is employee-centric (senders and participants are employee ids, not user ids).
 *
 * <p>Rich content is handled here: emoji reaction toggles, employee and entity mentions
 * parsed from the body on send, file attachments stored through {@link AttachmentService},
 * reply-preview resolution, message soft-delete and the room archive toggle.
 *
 * <p>Every change raises a {@link ChatEvent} so connected clients are told about it. The event
 * is raised inside the transaction and released by {@code ChatEventListener} only after commit,
 * so a write that rolls back tells nobody. It carries identifiers only: the client reacts by
 * refetching through these same authorized methods, which keeps every authorization decision
 * here rather than on the delivery path.
 */
@Service
public class ChatService {

    private static final String ROOM_DIRECT = "direct";
    private static final String ROLE_MEMBER = "member";
    private static final String ROLE_ADMIN = "admin";

    /** Polymorphic attachment owner type and S3 folder for chat message files. */
    private static final String ATTACHMENT_ENTITY_TYPE = "CHAT_MESSAGE";
    private static final String ATTACHMENT_FOLDER = "chat";

    /** How much of a quoted message the reply preview carries. */
    private static final int REPLY_SNIPPET_LENGTH = 200;

    private final ChatRoomRepository roomRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatReactionRepository reactionRepository;
    private final ChatMapper chatMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final EmployeeRepository employeeRepository;
    private final AttachmentService attachmentService;
    private final ApplicationEventPublisher eventPublisher;

    public ChatService(ChatRoomRepository roomRepository,
                       ChatParticipantRepository participantRepository,
                       ChatMessageRepository messageRepository,
                       ChatReactionRepository reactionRepository,
                       ChatMapper chatMapper,
                       TenantEntityHelper tenantEntityHelper,
                       UserContextService userContextService,
                       EmployeeRepository employeeRepository,
                       AttachmentService attachmentService,
                       ApplicationEventPublisher eventPublisher) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.reactionRepository = reactionRepository;
        this.chatMapper = chatMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
        this.employeeRepository = employeeRepository;
        this.attachmentService = attachmentService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<ChatRoomDto> getRoomsForCurrentEmployee() {
        Long employeeId = resolveCurrentEmployeeId();
        return roomRepository.findRoomsForEmployee(employeeId).stream()
                .map(room -> toRoomDto(room, employeeId))
                .toList();
    }

    /**
     * The employee id of the authenticated caller in the current tenant. Exposed because the
     * real-time stream is addressed by employee, and resolving the caller is the one piece of
     * that the stream needs from this service.
     */
    @Transactional(readOnly = true)
    public Long getCurrentEmployeeId() {
        return resolveCurrentEmployeeId();
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

        // Both sides, not just the opener: the point of the event is that the room appears in
        // the other person's sidebar without them reloading.
        raise(ChatEventType.ROOM_UPDATED, saved.getId(), null, employeeId,
                List.of(employeeId, otherEmployeeId));
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

        // Only the reader. An unread count is per viewer, so nobody else's room list changes
        // when someone catches up on their own.
        raise(ChatEventType.ROOM_UPDATED, roomId, null, employeeId, List.of(employeeId));
    }

    @Transactional(readOnly = true)
    public Page<ChatMessageDto> getMessages(Long roomId, int pageNo, int pageSize) {
        Long employeeId = resolveCurrentEmployeeId();
        requireRoom(roomId);
        requireParticipant(roomId, employeeId);
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return messageRepository.findByRoom_IdAndDeletedFalse(roomId, pageable)
                .map(this::toMessageDto);
    }

    /**
     * Posts a text (optionally reply) message with no attachments. Kept as the JSON path the
     * web uses today; the multipart overload below adds files.
     */
    @Transactional
    public ChatMessageDto sendMessage(Long roomId, String content, Long replyToId) {
        return sendMessage(roomId, content, replyToId, null);
    }

    /**
     * Posts a message to the room from the current employee. The body is parsed for employee
     * mentions ({@code @[Name](id)}) and entity mentions ({@code #[label](type:id)}), which
     * are persisted on the message. Any {@code files} are stored through the shared
     * attachment service and linked to the saved message.
     */
    @Transactional
    public ChatMessageDto sendMessage(Long roomId, String content, Long replyToId, List<MultipartFile> files) {
        Long employeeId = resolveCurrentEmployeeId();
        ChatRoom room = requireRoom(roomId);
        requireParticipant(roomId, employeeId);

        ChatMessage message = new ChatMessage();
        message.setRoom(room);
        message.setOrganization(room.getOrganization());
        message.setSenderId(employeeId);
        message.setContent(content);
        message.setReplyToId(replyToId);
        message.setMentions(ChatMentionParser.parseMentions(content));
        message.setEntityMentions(ChatMentionParser.parseEntityMentions(content));

        // Bump the room so it sorts to the top of the caller's list and updatedAt reflects
        // the new activity.
        room.setUpdatedAt(LocalDateTime.now());
        roomRepository.save(room);

        // saveAndFlush before mapping so the generated id and @CreationTimestamp land on the DTO.
        ChatMessage saved = messageRepository.saveAndFlush(message);

        if (files != null && !files.isEmpty()) {
            attachmentService.uploadAttachments(files, ATTACHMENT_ENTITY_TYPE, saved.getId(), ATTACHMENT_FOLDER);
        }

        raise(ChatEventType.MESSAGE_CREATED, roomId, saved.getId(), employeeId, participantsOf(roomId));
        return toMessageDto(saved);
    }

    /**
     * Toggles the caller's emoji reaction on a message: adds the reaction if absent, removes
     * it if already present. Returns the message with its refreshed reaction list.
     */
    @Transactional
    public ChatMessageDto toggleReaction(Long messageId, String emoji) {
        Long employeeId = resolveCurrentEmployeeId();
        ChatMessage message = requireMessage(messageId);
        requireParticipant(message.getRoom().getId(), employeeId);

        reactionRepository.findByMessage_IdAndEmployeeIdAndEmoji(messageId, employeeId, emoji)
                .ifPresentOrElse(
                        reactionRepository::delete,
                        () -> {
                            ChatReaction reaction = new ChatReaction();
                            reaction.setMessage(message);
                            reaction.setEmployeeId(employeeId);
                            reaction.setEmoji(emoji);
                            reaction.setOrganization(message.getOrganization());
                            reactionRepository.save(reaction);
                        });
        reactionRepository.flush();

        Long roomId = message.getRoom().getId();
        raise(ChatEventType.MESSAGE_UPDATED, roomId, messageId, employeeId, participantsOf(roomId));
        return toMessageDto(message);
    }

    /**
     * Soft-deletes a message: only its sender or a room admin may delete it, and the row is
     * kept with {@code isDeleted} set so the web renders a tombstone.
     */
    @Transactional
    public void deleteMessage(Long messageId) {
        Long employeeId = resolveCurrentEmployeeId();
        ChatMessage message = requireMessage(messageId);
        ChatParticipant participant = requireParticipant(message.getRoom().getId(), employeeId);

        boolean isSender = employeeId.equals(message.getSenderId());
        boolean isRoomAdmin = ROLE_ADMIN.equalsIgnoreCase(participant.getRole());
        if (!isSender && !isRoomAdmin) {
            throw new AccessDeniedException("Only the sender or a room admin can delete this message");
        }

        message.setDeleted(true);
        messageRepository.save(message);

        Long roomId = message.getRoom().getId();
        raise(ChatEventType.MESSAGE_UPDATED, roomId, messageId, employeeId, participantsOf(roomId));
    }

    /**
     * Archives or unarchives a room for the whole conversation. Any participant may toggle it.
     */
    @Transactional
    public ChatRoomDto setArchived(Long roomId, boolean archived) {
        Long employeeId = resolveCurrentEmployeeId();
        ChatRoom room = requireRoom(roomId);
        requireParticipant(roomId, employeeId);

        room.setArchived(archived);
        ChatRoom saved = roomRepository.saveAndFlush(room);

        raise(ChatEventType.ROOM_UPDATED, roomId, null, employeeId, participantsOf(roomId));
        return toRoomDto(saved, employeeId);
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
        message.setMentions(ChatMentionParser.parseMentions(content));
        message.setEntityMentions(ChatMentionParser.parseEntityMentions(content));

        ChatMessage saved = messageRepository.saveAndFlush(message);

        Long roomId = message.getRoom().getId();
        raise(ChatEventType.MESSAGE_UPDATED, roomId, messageId, employeeId, participantsOf(roomId));
        return toMessageDto(saved);
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * Raises a real-time event for a change just made. It is published while the transaction is
     * still open; {@code ChatEventListener} holds it until commit, so a rolled back write never
     * reaches a client.
     */
    private void raise(ChatEventType type, Long roomId, Long messageId, Long actorEmployeeId,
                       List<Long> recipients) {
        eventPublisher.publishEvent(new ChatEvent(
                type, TenantContext.getCurrentOrgId(), roomId, messageId, actorEmployeeId, recipients));
    }

    /**
     * The employees who should hear about a change to this room. Resolved here, inside the
     * transaction and the tenant context, so the after-commit listener carries the answer with
     * it and performs no tenant-scoped read of its own.
     */
    private List<Long> participantsOf(Long roomId) {
        return participantRepository.findEmployeeIdsByRoomId(roomId);
    }

    /**
     * Builds a room DTO for the given viewer, filling in the fields the mapper cannot: the
     * latest non-deleted message and the viewer's unread count.
     */
    private ChatRoomDto toRoomDto(ChatRoom room, Long viewerEmployeeId) {
        ChatRoomDto dto = chatMapper.toDto(room);

        messageRepository.findFirstByRoom_IdAndDeletedFalseOrderByCreatedAtDesc(room.getId())
                .ifPresent(last -> dto.setLastMessage(toMessageDto(last)));

        LocalDateTime lastReadAt = participantRepository
                .findByRoom_IdAndEmployeeId(room.getId(), viewerEmployeeId)
                .map(ChatParticipant::getLastReadAt)
                .orElse(null);
        long unread = messageRepository.countUnread(room.getId(), viewerEmployeeId, lastReadAt);
        dto.setUnreadCount((int) unread);

        return dto;
    }

    /**
     * Maps a message to its DTO and fills the fields the mapper cannot: the grouped emoji
     * reactions, the stored attachments (each freshly presigned), and the reply preview
     * resolved from {@code replyToId}.
     */
    private ChatMessageDto toMessageDto(ChatMessage message) {
        ChatMessageDto dto = chatMapper.toDto(message);
        dto.setReactions(groupReactions(message.getId()));

        List<AttachmentDto> attachments =
                attachmentService.getAttachments(ATTACHMENT_ENTITY_TYPE, message.getId());
        dto.setAttachments(attachments);

        if (message.getReplyToId() != null) {
            dto.setReplyTo(buildReplyPreview(message.getReplyToId()));
        }
        return dto;
    }

    /**
     * Collapses a message's reaction rows into one entry per emoji, each carrying the count
     * and the employee ids who reacted. Insertion order of first appearance is preserved.
     */
    private List<ChatReactionDto> groupReactions(Long messageId) {
        Map<String, ChatReactionDto> byEmoji = new LinkedHashMap<>();
        for (ChatReaction reaction : reactionRepository.findByMessage_Id(messageId)) {
            ChatReactionDto group = byEmoji.computeIfAbsent(reaction.getEmoji(), emoji -> {
                ChatReactionDto d = new ChatReactionDto();
                d.setEmoji(emoji);
                d.setEmployeeIds(new ArrayList<>());
                return d;
            });
            group.getEmployeeIds().add(reaction.getEmployeeId());
            group.setCount(group.getEmployeeIds().size());
        }
        return new ArrayList<>(byEmoji.values());
    }

    /**
     * Resolves the compact preview of a replied-to message. A reply to a message that is gone
     * or belongs to another tenant simply yields no preview.
     */
    private ChatMessageReplyDto buildReplyPreview(Long replyToId) {
        return messageRepository
                .findByIdAndOrganization_Id(replyToId, TenantContext.getCurrentOrgId())
                .map(replied -> {
                    ChatMessageReplyDto preview = new ChatMessageReplyDto();
                    preview.setId(replied.getId());
                    preview.setSenderId(replied.getSenderId());
                    preview.setContent(snippet(replied.getContent()));
                    return preview;
                })
                .orElse(null);
    }

    private String snippet(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= REPLY_SNIPPET_LENGTH
                ? content
                : content.substring(0, REPLY_SNIPPET_LENGTH);
    }

    private ChatMessage requireMessage(Long messageId) {
        return messageRepository
                .findByIdAndOrganization_Id(messageId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message with ID " + messageId + " was not found in this organization"));
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
