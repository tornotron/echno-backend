package org.tornotron.echno_backend.chat.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the transaction phase {@link ChatEventListener} runs in.
 *
 * <p>The phase is the only interesting thing about that class, and it is not observable without
 * a real transaction manager, so it gets a real one. What is being pinned down is that a chat
 * change is announced when, and only when, it is durable: a rolled back write must tell nobody,
 * or a recipient would be shown a message that does not exist and would keep showing it until
 * their next refetch disagreed.
 *
 * <p>{@code NOT_SUPPORTED} on the class matters: the test slice would otherwise wrap each test
 * in a transaction that is rolled back at the end, and an after-commit listener would never fire
 * at all.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatEventListener.class, ChatEventListenerIT.RecordingPublisher.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatEventListenerIT extends AbstractIntegrationTest {

    /** Stands in for the Redis or local publisher and records what it was asked to send. */
    @Component
    static class RecordingPublisher implements ChatEventPublisher {
        private final List<ChatEvent> published = new ArrayList<>();

        @Override
        public void publish(ChatEvent event) {
            published.add(event);
        }
    }

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private RecordingPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    private final ChatEvent event = new ChatEvent(
            ChatEventType.MESSAGE_CREATED, 1L, 5L, 99L, 10L, List.of(10L, 11L));

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        publisher.published.clear();
    }

    @Test
    void nothingIsPublishedUntilTheTransactionCommits() {
        transaction.executeWithoutResult(status -> {
            events.publishEvent(event);
            assertThat(publisher.published)
                    .as("the event must still be held while the write could still roll back")
                    .isEmpty();
        });

        assertThat(publisher.published).containsExactly(event);
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            events.publishEvent(event);
            throw new IllegalStateException("the write failed after raising its event");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(publisher.published).isEmpty();
    }
}
