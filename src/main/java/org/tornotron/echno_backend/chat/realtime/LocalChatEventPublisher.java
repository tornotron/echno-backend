package org.tornotron.echno_backend.chat.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Hands events straight to this JVM's registry.
 *
 * <p>Correct whenever the deployment runs a single replica, which is the compose flavor on the
 * IITM staging box: there is no other replica for an event to reach. It is also what keeps the
 * chat feature working with no Redis deployed at all, so a developer running the backend on
 * their laptop gets live delivery without standing anything else up.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "echno.cache.provider", havingValue = "caffeine", matchIfMissing = true)
public class LocalChatEventPublisher implements ChatEventPublisher {

    private final ChatStreamRegistry registry;

    @Override
    public void publish(ChatEvent event) {
        registry.deliver(event);
    }
}
