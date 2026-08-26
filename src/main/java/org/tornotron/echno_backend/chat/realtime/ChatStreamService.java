package org.tornotron.echno_backend.chat.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.tornotron.echno_backend.chat.ChatService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import java.io.IOException;

/**
 * Opens a chat stream for the authenticated caller and hands it to the registry.
 *
 * <p>Kept out of the controller because the interesting decision here is the stream's lifetime,
 * not its HTTP shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    /**
     * How long one stream lives before the server closes it and the browser opens another.
     *
     * <p>This is a security bound, not a resource one. Spring Security authenticates a request
     * once, when it arrives; nothing re-checks a request that then stays open. A stream held for
     * hours would therefore outlive both the access token it was opened with and any revocation
     * of the session behind it. Ending it after ten minutes forces a reconnect, and the
     * reconnect runs the BFF's revocation check and the whole filter chain again, so a revoked
     * session stops receiving within that window.
     */
    static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * How soon the browser should reconnect after the server closes a stream. Sent as the SSE
     * {@code retry} field on the opening frame, replacing the three second default, so the gap
     * created by the deliberate recycle above is not visible to the person typing.
     */
    private static final long RECONNECT_DELAY_MS = 1_000L;

    private final ChatStreamRegistry registry;
    private final ChatService chatService;

    /**
     * Opens a stream for the caller. Runs on the request thread, so the tenant and the
     * authenticated user are still resolvable; both are captured as values because the callbacks
     * below fire later, on a container thread with neither in scope.
     */
    public SseEmitter open() {
        Long orgId = TenantContext.getCurrentOrgId();
        Long employeeId = chatService.getCurrentEmployeeId();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onCompletion(() -> registry.unregister(orgId, employeeId, emitter));
        emitter.onError(e -> registry.unregister(orgId, employeeId, emitter));
        emitter.onTimeout(() -> {
            registry.unregister(orgId, employeeId, emitter);
            emitter.complete();
        });

        registry.register(orgId, employeeId, emitter);

        try {
            // An opening frame does two jobs: it sets the reconnect delay, and it makes the
            // response commit now rather than when the first message happens to arrive, so the
            // client knows it is connected instead of waiting on an idle socket.
            //
            // Not named "open". A named SSE frame is dispatched to the browser as an event of
            // that name, so "open" would be indistinguishable from EventSource's own open
            // event, and the client uses that one to tell a reconnect (refetch everything, the
            // gap may have swallowed a change) from a first connection (nothing to repair).
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .reconnectTime(RECONNECT_DELAY_MS)
                    .data("connected"));
        } catch (IOException e) {
            log.debug("Chat stream for employee {} closed before it opened: {}", employeeId, e.getMessage());
            registry.unregister(orgId, employeeId, emitter);
            emitter.completeWithError(e);
        }

        log.debug("Opened chat stream for employee {} in organization {}", employeeId, orgId);
        return emitter;
    }
}
