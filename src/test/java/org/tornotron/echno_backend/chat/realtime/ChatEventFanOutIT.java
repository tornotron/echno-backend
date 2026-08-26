package org.tornotron.echno_backend.chat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for cross-replica chat delivery against a real Redis container.
 *
 * <p>This is the property the whole fan-out design exists for, so it is worth a test that fails
 * if the wiring is wrong. Two {@link ChatStreamRegistry} instances with their own subscribers
 * stand in for two backend pods. A message written on the first must reach a stream held by the
 * second, because in the cluster the pod that accepts a message is very unlikely to be the pod
 * holding the recipient's connection.
 */
@Testcontainers
class ChatEventFanOutIT {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final Long ORG = 1L;
    private static final Long ALICE = 10L;
    private static final Long BOB = 11L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
        closeables.clear();
    }

    /** An emitter that records what was written instead of touching a real response. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<Object> sent = new ArrayList<>();

        private RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sent.add(builder);
        }
    }

    @Test
    void anEventPublishedOnOneReplicaReachesAStreamHeldByAnother() {
        ChatStreamRegistry replicaOne = new ChatStreamRegistry();
        ChatStreamRegistry replicaTwo = new ChatStreamRegistry();
        subscribe(replicaOne);
        subscribe(replicaTwo);

        // Alice is connected to the second replica only, which is the situation the broker exists
        // to solve: her message is written by a pod that is not holding her connection.
        RecordingEmitter aliceOnReplicaTwo = new RecordingEmitter();
        replicaTwo.register(ORG, ALICE, aliceOnReplicaTwo);

        RedisChatEventPublisher publisher = publisherFor(replicaOne);
        ChatEvent event = new ChatEvent(ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, BOB, List.of(ALICE));

        // Republished each poll rather than published once. A listener container subscribes
        // asynchronously, so a single publish can land in the gap before the subscription is
        // live, and Redis pub/sub keeps nothing for a subscriber that was not yet there.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            publisher.publish(event);
            assertThat(aliceOnReplicaTwo.sent).isNotEmpty();
        });
    }

    @Test
    void anEventReachesTheStreamsOnThePublishingReplicaToo() {
        ChatStreamRegistry replica = new ChatStreamRegistry();
        subscribe(replica);

        RecordingEmitter alice = new RecordingEmitter();
        replica.register(ORG, ALICE, alice);

        // The publisher does not shortcut its own replica; it receives its own message back off
        // the channel. One delivery path rather than a local special case that production never
        // exercises.
        RedisChatEventPublisher publisher = publisherFor(replica);
        ChatEvent event = new ChatEvent(ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, BOB, List.of(ALICE));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            publisher.publish(event);
            assertThat(alice.sent).isNotEmpty();
        });
    }

    @Test
    void anEventIsNotDeliveredToARecipientItDoesNotName() {
        ChatStreamRegistry replica = new ChatStreamRegistry();
        subscribe(replica);

        RecordingEmitter alice = new RecordingEmitter();
        RecordingEmitter bob = new RecordingEmitter();
        replica.register(ORG, ALICE, alice);
        replica.register(ORG, BOB, bob);

        RedisChatEventPublisher publisher = publisherFor(replica);
        ChatEvent event = new ChatEvent(ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, ALICE, List.of(ALICE));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            publisher.publish(event);
            assertThat(alice.sent).isNotEmpty();
        });
        assertThat(bob.sent).isEmpty();
    }

    /** Points a registry at the shared channel, the way one pod's listener container does. */
    private void subscribe(ChatStreamRegistry registry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory());
        container.addMessageListener(new ChatEventSubscriber(registry, objectMapper),
                new ChannelTopic(RedisChatEventPublisher.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        closeables.add(container::destroy);
    }

    private RedisChatEventPublisher publisherFor(ChatStreamRegistry ignored) {
        return new RedisChatEventPublisher(new StringRedisTemplate(connectionFactory()), objectMapper);
    }

    private RedisConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        closeables.add(factory::destroy);
        return factory;
    }
}
