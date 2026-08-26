package org.tornotron.echno_backend.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fans an event out over Redis pub/sub so it reaches every replica, not just the one that
 * handled the write.
 *
 * <p>This is the whole reason the feature needs a broker. The k8s backend scales to several
 * replicas behind one service, so the pod that accepts a message is very unlikely to be the
 * pod holding the recipient's stream. Publishing to a channel every replica subscribes to
 * turns "the pod that wrote it" into "every pod", and each one then delivers to whichever
 * recipients it happens to hold.
 *
 * <p>Note the publisher does not skip its own replica: it subscribes to the same channel and
 * receives its own message back, which is what keeps the local and remote paths identical
 * rather than having a special case that only runs in production.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "echno.cache.provider", havingValue = "redis")
public class RedisChatEventPublisher implements ChatEventPublisher {

    /**
     * One channel for every tenant, filtered on arrival. Production is deployed as a
     * per-client instance, so a channel per tenant would shard traffic that is not mixed in
     * the first place.
     */
    public static final String CHANNEL = "echno:chat:events";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(ChatEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // A chat event that cannot be serialized is a bug, not a runtime condition, but it
            // must not take down the write that raised it: the message is already committed and
            // the reader's poll will still show it.
            log.error("Could not serialize chat event for room {}: {}", event.roomId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Could not publish chat event for room {} to Redis, falling back to the client poll: {}",
                    event.roomId(), e.getMessage());
        }
    }
}
