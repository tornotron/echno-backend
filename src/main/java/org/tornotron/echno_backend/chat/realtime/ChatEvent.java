package org.tornotron.echno_backend.chat.realtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A chat change, carried from the writing replica to every replica holding a recipient's
 * stream.
 *
 * <p>It carries identifiers only, never rendered state. Two reasons. The chat DTOs are
 * viewer dependent ({@code ChatService.toRoomDto} computes the viewer's own unread count and
 * {@code toMessageDto} attaches freshly presigned attachment URLs), so no single payload is
 * correct for every recipient. And a payload travelling this path would bypass
 * {@code TenantFilter} and the {@code @orgSecurity} checks, which is the surface that the
 * fail-closed tenant isolation work exists to keep narrow. The client reacts by invalidating
 * a query and refetching through the ordinary authenticated endpoint, so every authorization
 * decision stays on the REST path.
 *
 * @param type            what happened
 * @param orgId           tenant the event belongs to; part of the delivery key so two tenants
 *                        that happen to share an employee id can never cross
 * @param roomId          room the event concerns
 * @param messageId       message concerned, or {@code null} for a room level event
 * @param actorEmployeeId employee whose action caused it
 * @param recipients      employee ids that should receive it, resolved from the room's
 *                        participants inside the writing transaction
 */
public record ChatEvent(
        @JsonProperty("type") ChatEventType type,
        @JsonProperty("orgId") Long orgId,
        @JsonProperty("roomId") Long roomId,
        @JsonProperty("messageId") Long messageId,
        @JsonProperty("actorEmployeeId") Long actorEmployeeId,
        @JsonProperty("recipients") List<Long> recipients) {

    @JsonCreator
    public ChatEvent {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }

    /**
     * The projection actually written to a browser. {@code orgId} and {@code recipients} are
     * routing metadata for the server side and are left out: the client already knows its own
     * tenant, and the participant list is not something a stream frame needs to restate.
     */
    public ClientPayload toClientPayload() {
        return new ClientPayload(type, roomId, messageId, actorEmployeeId);
    }

    /** The client-facing shape of an event. */
    public record ClientPayload(
            @JsonProperty("type") ChatEventType type,
            @JsonProperty("roomId") Long roomId,
            @JsonProperty("messageId") Long messageId,
            @JsonProperty("actorEmployeeId") Long actorEmployeeId) {
    }
}
