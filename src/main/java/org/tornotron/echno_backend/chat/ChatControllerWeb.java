package org.tornotron.echno_backend.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.chat.dto.ChatMessageDto;
import org.tornotron.echno_backend.chat.dto.ChatRoomDto;
import org.tornotron.echno_backend.chat.dto.CreateDirectRoomDto;
import org.tornotron.echno_backend.chat.dto.EditMessageDto;
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

    public ChatControllerWeb(ChatService chatService) {
        this.chatService = chatService;
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

    @PostMapping("/rooms/web/{roomId}/messages")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Send a message",
            description = "Posts a message from the current employee to the room and bumps the room's activity."
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
}
