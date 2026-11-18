package org.tornotron.echno_backend.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.chat.dto.ArchiveRoomRequestDto;
import org.tornotron.echno_backend.chat.dto.ChatMessageDto;
import org.tornotron.echno_backend.chat.dto.ChatRoomDto;
import org.tornotron.echno_backend.chat.dto.CreateDirectRoomDto;
import org.tornotron.echno_backend.chat.dto.EditMessageDto;
import org.tornotron.echno_backend.chat.dto.ReactionRequestDto;
import org.tornotron.echno_backend.chat.dto.SendMessageDto;
import org.tornotron.echno_backend.common.pagination.PageQuery30;

import java.util.List;

/**
 * Chat on the tenant API paths the mobile client calls (#998): each endpoint of
 * {@link ChatControllerWeb} without the {@code /web} in its path.
 *
 * <p>The other web-only modules get their mobile paths from a subclass of the web controller.
 * Chat cannot: its {@code /web} sits inside each handler's path, so every handler would need
 * overriding, and {@code /stream}, which has no {@code /web} and already serves both clients,
 * would be mapped a second time by the subclass and fail at startup. So this class calls the web
 * controller instead of extending it, under the same guard on each handler.
 * {@code MobilePathGuardParityTest} pins the paths and guards equal to the web ones.
 */
@RestController
@Validated
@RequestMapping("/api/v1/chat")
@Tag(
        name = "Chat (Mobile)",
        description = "The tenant API path of the chat endpoints under /api/v1/chat/rooms/web and "
                + "/api/v1/chat/messages/web, for the mobile client. Same handlers, request and response "
                + "shapes and guards as the web endpoints; send X-Organization-Id to pick the "
                + "organization. The change stream, /api/v1/chat/stream, serves both clients as it is."
)
public class ChatController {

    private final ChatControllerWeb web;

    public ChatController(ChatControllerWeb web) {
        this.web = web;
    }

    @GetMapping("/rooms")
    @PreAuthorize("@access.can('chat', 'read')")
    @Operation(summary = "List the caller's chat rooms",
            description = "Same as GET /api/v1/chat/rooms/web.")
    public ResponseEntity<List<ChatRoomDto>> readRooms() {
        return web.readRooms();
    }

    @GetMapping("/rooms/{id}")
    @PreAuthorize("@access.can('chat', 'read')")
    @Operation(summary = "Get a chat room",
            description = "Same as GET /api/v1/chat/rooms/web/{id}.")
    public ResponseEntity<ChatRoomDto> readRoom(
            @PathVariable Long id) {
        return web.readRoom(id);
    }

    @PostMapping("/rooms/direct")
    @PreAuthorize("@access.can('chat', 'create')")
    @Operation(summary = "Open a direct room",
            description = "Same as POST /api/v1/chat/rooms/web/direct.")
    public ResponseEntity<ChatRoomDto> openDirectRoom(
            @Valid @RequestBody CreateDirectRoomDto dto) {
        return web.openDirectRoom(dto);
    }

    @PostMapping("/rooms/{roomId}/read")
    @PreAuthorize("@access.can('chat', 'read')")
    @Operation(summary = "Mark a room read",
            description = "Same as POST /api/v1/chat/rooms/web/{roomId}/read.")
    public ResponseEntity<Void> markRoomRead(
            @PathVariable Long roomId) {
        return web.markRoomRead(roomId);
    }

    @GetMapping("/rooms/{roomId}/messages")
    @PreAuthorize("@access.can('chat', 'read')")
    @Operation(summary = "List a room's messages, paginated",
            description = "Same as GET /api/v1/chat/rooms/web/{roomId}/messages.")
    public ResponseEntity<Page<ChatMessageDto>> readMessages(
            @PathVariable Long roomId,
            @Valid @ParameterObject PageQuery30 pageQuery) {
        return web.readMessages(roomId, pageQuery);
    }

    @PostMapping(value = "/rooms/{roomId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@access.can('chat', 'create')")
    @Operation(summary = "Send a message (text only)",
            description = "Same as POST /api/v1/chat/rooms/web/{roomId}/messages.")
    public ResponseEntity<ChatMessageDto> sendMessage(
            @PathVariable Long roomId,
            @Valid @RequestBody SendMessageDto dto) {
        return web.sendMessage(roomId, dto);
    }

    @PostMapping(value = "/rooms/{roomId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.can('chat', 'create')")
    @Operation(summary = "Send a message with attachments",
            description = "Same as POST /api/v1/chat/rooms/web/{roomId}/messages.")
    public ResponseEntity<ChatMessageDto> sendMessageWithAttachments(
            @PathVariable Long roomId,
            @Parameter(schema = @Schema(implementation = SendMessageDto.class)) @RequestPart("data") String data,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        return web.sendMessageWithAttachments(roomId, data, attachments);
    }

    @PatchMapping("/messages/{id}")
    @PreAuthorize("@access.can('chat', 'edit')")
    @Operation(summary = "Edit a message",
            description = "Same as PATCH /api/v1/chat/messages/web/{id}.")
    public ResponseEntity<ChatMessageDto> editMessage(
            @PathVariable Long id,
            @Valid @RequestBody EditMessageDto dto) {
        return web.editMessage(id, dto);
    }

    @PostMapping("/messages/{id}/reactions")
    @PreAuthorize("@access.can('chat', 'create')")
    @Operation(summary = "Toggle a reaction",
            description = "Same as POST /api/v1/chat/messages/web/{id}/reactions.")
    public ResponseEntity<ChatMessageDto> toggleReaction(
            @PathVariable Long id,
            @Valid @RequestBody ReactionRequestDto dto) {
        return web.toggleReaction(id, dto);
    }

    @DeleteMapping("/messages/{id}")
    @PreAuthorize("@access.can('chat', 'delete')")
    @Operation(summary = "Delete a message",
            description = "Same as DELETE /api/v1/chat/messages/web/{id}.")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable Long id) {
        return web.deleteMessage(id);
    }

    @PostMapping("/rooms/{roomId}/archive")
    @PreAuthorize("@access.can('chat', 'create')")
    @Operation(summary = "Archive or unarchive a room",
            description = "Same as POST /api/v1/chat/rooms/web/{roomId}/archive.")
    public ResponseEntity<ChatRoomDto> archiveRoom(
            @PathVariable Long roomId,
            @Valid @RequestBody ArchiveRoomRequestDto dto) {
        return web.archiveRoom(roomId, dto);
    }
}
