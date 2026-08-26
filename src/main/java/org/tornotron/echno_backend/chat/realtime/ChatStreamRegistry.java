package org.tornotron.echno_backend.chat.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the server-sent-event streams this replica is serving and writes events to them.
 *
 * <p>Streams are keyed by tenant <em>and</em> employee. Employee ids are only unique inside an
 * organization, so keying on the employee alone would let an event for one tenant reach a
 * different tenant's stream that happened to share the number.
 *
 * <p>Each key holds a list rather than a single emitter because one person legitimately has
 * several open tabs. The list is a {@link CopyOnWriteArrayList}: it keeps insertion order (so
 * "evict the oldest" is well defined), it is safe to iterate while another thread registers,
 * and the copy-on-write cost is irrelevant at a handful of entries per employee.
 *
 * <p>This is per-replica state and is deliberately not shared. Cross-replica delivery is the
 * publisher's job ({@link ChatEventPublisher}); the registry only ever serves the connections
 * that terminated on this JVM.
 */
@Slf4j
@Component
public class ChatStreamRegistry {

    /**
     * Open streams allowed per employee per tenant. A browser that reconnects faster than it
     * releases (a reload loop, a flapping network) would otherwise pile up emitters until the
     * heap gives out, so the surplus connection costs the oldest its place instead.
     */
    public static final int MAX_STREAMS_PER_EMPLOYEE = 5;

    private final Map<StreamKey, CopyOnWriteArrayList<SseEmitter>> streams = new ConcurrentHashMap<>();

    /** Tenant plus employee: the identity a stream is delivered to. */
    private record StreamKey(Long orgId, Long employeeId) {
    }

    /**
     * Adds a stream for the given employee, evicting their oldest if that puts them over
     * {@link #MAX_STREAMS_PER_EMPLOYEE}.
     */
    public void register(Long orgId, Long employeeId, SseEmitter emitter) {
        List<SseEmitter> evicted = new ArrayList<>();

        streams.compute(new StreamKey(orgId, employeeId), (key, existing) -> {
            CopyOnWriteArrayList<SseEmitter> list =
                    existing == null ? new CopyOnWriteArrayList<>() : existing;
            list.add(emitter);
            while (list.size() > MAX_STREAMS_PER_EMPLOYEE) {
                evicted.add(list.remove(0));
            }
            return list;
        });

        // Completed outside the compute: completing an emitter runs its completion callback,
        // which calls back into unregister, and a recursive update on the same key would
        // deadlock the map.
        for (SseEmitter old : evicted) {
            log.debug("Evicting the oldest chat stream for employee {} in organization {}: over the cap of {}",
                    employeeId, orgId, MAX_STREAMS_PER_EMPLOYEE);
            complete(old);
        }
    }

    /** Removes a stream. Called when it completes, times out, or errors. */
    public void unregister(Long orgId, Long employeeId, SseEmitter emitter) {
        streams.computeIfPresent(new StreamKey(orgId, employeeId), (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    /**
     * Writes an event to every stream on this replica belonging to one of its recipients.
     *
     * <p>A recipient with no stream here is simply not our concern: either they are connected
     * to another replica, which received the same event from the publisher, or they are not
     * connected at all, in which case their next fetch shows them the change.
     */
    public void deliver(ChatEvent event) {
        for (Long recipient : event.recipients()) {
            StreamKey key = new StreamKey(event.orgId(), recipient);
            CopyOnWriteArrayList<SseEmitter> list = streams.get(key);
            if (list == null) {
                continue;
            }
            for (SseEmitter emitter : list) {
                write(key, emitter, SseEmitter.event()
                        .name("chat")
                        .data(event.toClientPayload()));
            }
        }
    }

    /**
     * Writes a comment to every open stream.
     *
     * <p>Not a keep-alive for its own sake: nginx bounds a proxied response by the gap between
     * reads ({@code proxy_read_timeout}, 90s on the backend site) and Cloudflare by an idle
     * ceiling of 100s. A comment well inside the shorter of the two keeps a stream open
     * indefinitely, which is what lets this feature ship without raising a timeout anywhere on
     * the path.
     */
    public void heartbeat() {
        for (Map.Entry<StreamKey, CopyOnWriteArrayList<SseEmitter>> entry : streams.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                write(entry.getKey(), emitter, SseEmitter.event().comment("keep-alive"));
            }
        }
    }

    /** Open streams for one employee. Exposed for tests and for the metrics-minded operator. */
    public int streamCount(Long orgId, Long employeeId) {
        CopyOnWriteArrayList<SseEmitter> list = streams.get(new StreamKey(orgId, employeeId));
        return list == null ? 0 : list.size();
    }

    /** Open streams on this replica, across every tenant. */
    public int totalStreams() {
        return streams.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Writes to one emitter, releasing it if the write fails. A browser that closed without a
     * clean shutdown leaves a dead emitter behind, and the write is the only moment that
     * discovers it, so it does the cleanup rather than leaving the entry to accumulate.
     */
    private void write(StreamKey key, SseEmitter emitter, SseEmitter.SseEventBuilder payload) {
        try {
            emitter.send(payload);
        } catch (Exception e) {
            log.debug("Chat stream for employee {} in organization {} is gone, releasing it: {}",
                    key.employeeId(), key.orgId(), e.getMessage());
            unregister(key.orgId(), key.employeeId(), emitter);
            complete(emitter);
        }
    }

    /** Completes an emitter, tolerating one that the container has already torn down. */
    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.trace("Chat stream was already closed: {}", e.getMessage());
        }
    }
}
