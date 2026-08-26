package org.tornotron.echno_backend.chat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

/**
 * Turns a message off the Redis chat channel back into a {@link ChatEvent} and delivers it to
 * the streams this replica holds.
 *
 * <p>Implements {@link MessageListener} directly rather than going through a
 * {@code MessageListenerAdapter}. The adapter deserializes bodies with JDK serialization unless
 * told otherwise, and only builds its method invoker in {@code afterPropertiesSet}, so an
 * adapter constructed by hand silently delivers nothing. Reading the bytes here is both shorter
 * and harder to misconfigure.
 *
 * <p>Every replica runs one of these, including the replica that published the event. Nothing
 * downstream needs to know which side of the channel a message came from, so there is one
 * delivery path rather than a local shortcut plus a remote path that only runs in production.
 */
@Slf4j
@RequiredArgsConstructor
public class ChatEventSubscriber implements MessageListener {

    private final ChatStreamRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            registry.deliver(objectMapper.readValue(payload, ChatEvent.class));
        } catch (Exception e) {
            // One malformed message must not stop the subscription; the recipients fall back to
            // their poll for this one change.
            log.warn("Discarding an unreadable chat event from Redis: {}", e.getMessage());
        }
    }
}
