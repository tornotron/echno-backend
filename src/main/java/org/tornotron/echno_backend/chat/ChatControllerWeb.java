package org.tornotron.echno_backend.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.tornotron.echno_backend.chat.realtime.ChatStreamService;
import org.tornotron.echno_backend.chat.dto.ArchiveRoomRequestDto;
import org.tornotron.echno_backend.chat.dto.ChatMessageDto;
import org.tornotron.echno_backend.chat.dto.ChatRoomDto;
import org.tornotron.echno_backend.chat.dto.CreateDirectRoomDto;
import org.tornotron.echno_backend.chat.dto.EditMessageDto;
import org.tornotron.echno_backend.chat.dto.ReactionRequestDto;
import org.tornotron.echno_backend.chat.dto.SendMessageDto;

import java.util.List;

/**
 * Employee-facing chat: rooms an employee belongs to and the messages within them. Every
 * endpoint requires only tenant membership (everyone in the organization can chat), with the
 * finer participant checks enforced in the service.
 */
@RestController
@Validated
@RequestMapping("/api/v1/chat")
@Tag(
        name = "Chat",
        description = "One-to-one and group chat between employees of the organization: rooms the caller "
                + "participates in, their unread counts and last message, and the send/edit message flow. "
                + "Any member of the tenant may chat; access to a specific room is limited to its participants."
)
public class ChatControllerWeb {

    private final ChatService chatService;
    private final JsonPartBinder jsonPartBinder;
    private final ChatStreamService chatStreamService;

    public ChatControllerWeb(ChatService chatService, JsonPartBinder jsonPartBinder,
                             ChatStreamService chatStreamService) {
        this.chatService = chatService;
        this.jsonPartBinder = jsonPartBinder;
        this.chatStreamService = chatStreamService;
    }

    @GetMapping("/rooms/web")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List the caller's chat rooms",
            description = "Returns every room the current employee participates in, each with its participants, "
                    + "latest message and the caller's unread count, newest activity first."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rooms returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<ChatRoomDto>> readRooms() {
        return new ResponseEntity<>(chatService.getRoomsForCurrentEmployee(), HttpStatus.OK);
    }

    @GetMapping("/rooms/web/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get a chat room",
            description = "Returns a single room the caller participates in, with its participants, latest "
                    + "message and unread count."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Room found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a participant of the room"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No room with the given id in this tenant")
    })
    public ResponseEntity<ChatRoomDto> readRoom(@PathVariable Long id) {
        return new ResponseEntity<>(chatService.getRoom(id), HttpStatus.OK);
    }

    @PostMapping("/rooms/web/direct")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Open a direct room",
            description = "Opens the one-to-one direct room with the given employee, reusing the existing one "
                    + "when present. Idempotent."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Direct room opened or reused"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "employeeId is missing or is the caller"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<ChatRoomDto> openDirectRoom(@Valid @RequestBody CreateDirectRoomDto dto) {
        return new ResponseEntity<>(chatService.openDirectRoom(dto.getEmployeeId()), HttpStatus.OK);
    }

    @PostMapping("/rooms/web/{roomId}/read")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Mark a room read",
            description = "Advances the caller's last-read marker in the room to now, clearing the unread count."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Room marked read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a participant of the room"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No room with the given id in this tenant")
    })
    public ResponseEntity<Void> markRoomRead(@PathVariable Long roomId) {
        chatService.markRead(roomId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rooms/web/{roomId}/messages")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List a room's messages, paginated",
            description = "Returns a page of the room's non-deleted messages, newest first."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of messages returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a participant of the room"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No room with the given id in this tenant")
    })
    public ResponseEntity<Page<ChatMessageDto>> readMessages(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "30") int pageSize) {
        return new ResponseEntity<>(chatService.getMessages(roomId, pageNo, pageSize), HttpStatus.OK);
    }

    @PostMapping(value = "/rooms/web/{roomId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Send a message (text only)",
            description = "Posts a text message from the current employee to the room and bumps the room's "
                    + "activity. Employee and entity mentions are parsed from the body. For a message that "
                    + "carries file attachments, use the multipart variant of this endpoint."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Message created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "content is blank"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a participant of the room"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No room with the given id in this tenant")
    })
    public ResponseEntity<ChatMessageDto> sendMessage(@PathVariable Long roomId,
                                                      @Valid @RequestBody SendMessageDto dto) {
        return new ResponseEntity<>(
                chatService.sendMessage(roomId, dto.getContent(), dto.getReplyToId()), HttpStatus.CREATED);
    }

    @PostMapping(value = "/rooms/web/{roomId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Send a message with attachments",
            description = "Posts a message with one or more file attachments. The message fields travel as a "
                    + "JSON 'data' part; the files travel as 'attachments' parts. Attachments are stored and "
                    + "returned with presigned download URLs."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Message created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "content is blank or the data part is malformed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a participant of the room"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No room with the given id in this tenant")
    })
    public ResponseEntity<ChatMessageDto> sendMessageWithAttachments(
            @PathVariable Long roomId,
            @RequestPart("data") String data,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments)
            throws JsonProcessingException {
        SendMessageDto dto = jsonPartBinder.read(data, SendMessageDto.class);
        return new ResponseEntity<>(
                chatService.sendMessage(roomId, dto.getContent(), dto.getReplyToId(), attachments),
                HttpStatus.CREATED);
    }

    @PatchMapping("/messages/web/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Edit a message",
            description = "Edits the body of the caller's own message, marking it edited. Only the sender may edit."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Message updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "content is blank"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not the sender of the message"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No message with the given id in this tenant")
    })
    public ResponseEntity<ChatMessageDto> editMessage(@PathVariable Long id,
                                                      @Valid @RequestBody EditMessageDto dto) {
        return new ResponseEntity<>(chatService.editMessage(id, dto.getContent()), HttpStatus.OK);
    }

    @PostMapping("/messages/web/{id}/reactions")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Toggle a reaction",
            description = "Adds the caller's emoji reaction to the message, or removes it if they already "
                    + "reacted with that emoji. Returns the message with its refreshed reaction list."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reaction toggled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "emoji is blank"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a participant of the room"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No message with the given id in this tenant")
    })
    public ResponseEntity<ChatMessageDto> toggleReaction(@PathVariable Long id,
                                                         @Valid @RequestBody ReactionRequestDto dto) {
        return new ResponseEntity<>(chatService.toggleReaction(id, dto.getEmoji()), HttpStatus.OK);
    }

    @DeleteMapping("/messages/web/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Delete a message",
            description = "Soft-deletes a message, keeping the row so the web can render a tombstone. Only the "
                    + "sender or a room admin may delete it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Message deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the sender nor a room admin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No message with the given id in this tenant")
    })
    public ResponseEntity<Void> deleteMessage(@PathVariable Long id) {
        chatService.deleteMessage(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rooms/web/{roomId}/archive")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Archive or unarchive a room",
            description = "Sets the room's archived state for the whole conversation. Any participant may toggle it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Room archive state updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "archived is missing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a participant of the room"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No room with the given id in this tenant")
    })
    public ResponseEntity<ChatRoomDto> archiveRoom(@PathVariable Long roomId,
                                                   @Valid @RequestBody ArchiveRoomRequestDto dto) {
        return new ResponseEntity<>(chatService.setArchived(roomId, dto.getArchived()), HttpStatus.OK);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Subscribe to chat changes",
            description = "A server-sent event stream of the caller's chat changes: new messages, message "
                    + "updates (edit, delete, reaction) and room updates. Frames carry identifiers only, not "
                    + "content: the client refetches through the ordinary endpoints, so what a caller can read "
                    + "is decided there and not here. The server closes the stream every ten minutes and the "
                    + "browser reconnects, which is what re-checks that the session is still valid."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stream opened"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller has no employee record in this tenant")
    })
    public SseEmitter stream(HttpServletResponse response) {
        // The edge vhost sets proxy_buffering on for every site. Measured against those same
        // directives, nginx forwards a flushing chunked stream promptly with or without this
        // header, so it is a safeguard rather than a fix: it costs one response header and
        // removes any dependence on buffer sizing staying as it is. Both nginx and
        // ingress-nginx honour it to disable buffering for a single response.
        response.setHeader("X-Accel-Buffering", "no");
        return chatStreamService.open();
    }
}
