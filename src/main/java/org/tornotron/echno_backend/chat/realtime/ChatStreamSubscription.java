package org.tornotron.echno_backend.chat.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts and keeps alive the Redis subscription that feeds cross-replica chat delivery.
 *
 * <p>The container is configured not to start with the application context, because opening a
 * subscription against an unreachable Redis throws out of the lifecycle processor and fails the
 * whole context. A chat feature must not be able to stop the API from booting, so the subscription
 * is established after startup instead, and a failure to establish it is a degraded feature rather
 * than a dead service: writes still succeed, and the web client still has the polling it kept.
 *
 * <p>Retrying on a schedule also means a backend that started before its Redis picks the
 * subscription up on its own, rather than needing a restart to notice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "echno.cache.provider", havingValue = "redis")
public class ChatStreamSubscription {

    private final RedisMessageListenerContainer container;

    /** Keeps the failure log to one line per outage instead of one per attempt. */
    private final AtomicBoolean reportedFailure = new AtomicBoolean();

    @Scheduled(initialDelay = 0, fixedDelay = 30_000L)
    public void ensureSubscribed() {
        if (container.isRunning()) {
            return;
        }
        try {
            container.start();
            log.info("Chat real-time delivery subscribed to Redis channel {}",
                    RedisChatEventPublisher.CHANNEL);
            reportedFailure.set(false);
        } catch (Exception e) {
            if (reportedFailure.compareAndSet(false, true)) {
                log.error("Chat real-time delivery could not subscribe to Redis ({}). Messages will "
                        + "still send and the web client falls back to polling; cross-replica live "
                        + "delivery is off until Redis is reachable.", e.getMessage());
            }
        }
    }
}
