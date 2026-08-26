package org.tornotron.echno_backend.chat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Redis wiring for cross-replica chat delivery, active only when
 * {@code echno.cache.provider=redis}.
 *
 * <p>Spring Boot's Redis auto-configuration is excluded application-wide (see
 * {@code EchnoBackendApplication}), so the template and the listener container are declared
 * here rather than inherited. The connection factory itself comes from {@code RedisConfig},
 * which owns it for the cache and the rate limiter; this class reuses that one connection
 * factory rather than opening a second.
 *
 * <p>The template is named {@code stringRedisTemplate} deliberately. A bean named
 * {@code redisTemplate} would re-enter the territory of the Spring Data Redis repository
 * auto-configuration that {@code spring.data.redis.repositories.enabled=false} exists to keep
 * out of the context.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "echno.cache.provider", havingValue = "redis")
public class ChatRealtimeRedisConfig {

    /** How long the container waits before re-establishing a subscription it has lost. */
    private static final long RECOVERY_INTERVAL_MS = 10_000L;

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Subscribes this replica to the chat channel. Every replica runs one, which is what makes
     * an event published by any of them reach the streams held by all of them.
     */
    @Bean
    public RedisMessageListenerContainer chatEventListenerContainer(
            RedisConnectionFactory connectionFactory, ChatEventSubscriber subscriber) {
        // isAutoStartup is overridden rather than set: RedisMessageListenerContainer exposes no
        // setter for it, and this is the only hook that keeps the lifecycle processor from
        // starting the subscription during context refresh.
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RedisChatEventPublisher.CHANNEL));
        // Do NOT start with the context. The container opens its subscription during startup, and
        // an unreachable Redis makes that throw out of the lifecycle processor and take the whole
        // context with it. That is a real deployment state rather than a hypothetical: the app
        // deploy renders SPRING_DATA_REDIS_HOST while only an infra run creates the container, so
        // the two can legitimately be out of step. The cache and rate limiter never noticed
        // because Lettuce connects lazily. Chat must not be the reason the API fails to boot, so
        // ChatStreamSubscription starts this after the context is up and keeps retrying.
        container.setRecoveryBackoff(new FixedBackOff(RECOVERY_INTERVAL_MS, Long.MAX_VALUE));
        return container;
    }

    /**
     * Receives an event off the channel and hands it to this replica's registry.
     *
     * <p>Declared as a bean here rather than as a component so it exists only alongside the
     * container that drives it.
     */
    @Bean
    public ChatEventSubscriber chatEventSubscriber(ChatStreamRegistry registry, ObjectMapper objectMapper) {
        return new ChatEventSubscriber(registry, objectMapper);
    }
}
