package org.tornotron.echno_backend.chat.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Releases a chat event to the publisher once the transaction that raised it has committed.
 *
 * <p>The phase is the point. {@code ChatService} raises the event while it still holds the
 * transaction, so a write that later rolls back would otherwise have already told every
 * recipient about a message that does not exist. {@code AFTER_COMMIT} means an event is only
 * ever seen for a change that is durable, and it matches how {@code InventoryEventListener}
 * and {@code ComplianceGenerationListener} already handle the same problem.
 *
 * <p>Unlike those two this listener is synchronous. Publishing is a single non-blocking write
 * to a Redis channel, or a direct hand-off to the local registry, so moving it to another
 * thread would add a hop and a second failure mode to save nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventListener {

    private final ChatEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChatEvent(ChatEvent event) {
        log.debug("Publishing {} for room {} to {} recipient(s)",
                event.type(), event.roomId(), event.recipients().size());
        publisher.publish(event);
    }
}
