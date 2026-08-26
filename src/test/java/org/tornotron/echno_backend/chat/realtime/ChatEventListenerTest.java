package org.tornotron.echno_backend.chat.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the transaction phase {@link ChatEventListener} runs in.
 *
 * <p>What is being pinned down is that a chat change is announced when, and only when, it is
 * durable. A rolled back write must tell nobody, or a recipient is shown a message that does not
 * exist and keeps showing it until their next refetch disagrees.
 *
 * <p>Deliberately not a {@code @DataJpaTest}. Transaction phase semantics are a property of the
 * transaction manager and the event multicaster, not of persistence, so a database here would
 * add a second heavyweight application context to the suite's context cache (and a
 * CockroachDB-backed one at that) to observe something no database takes part in. The minimal
 * transaction manager below drives the synchronization callbacks that {@code AFTER_COMMIT}
 * depends on, and the context is small enough to cost nothing.
 */
@SpringJUnitConfig(ChatEventListenerTest.TestConfig.class)
class ChatEventListenerTest {

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

    @Test
    void anEventRaisedWithNoTransactionIsNotSwallowed() {
        // Belt and braces on the listener's own default: without a transaction there is nothing
        // to wait for, and Spring drops such an event unless the listener opts in. This asserts
        // the current behaviour so a later change to fallbackExecution is a deliberate one.
        events.publishEvent(event);

        assertThat(publisher.published).isEmpty();
    }

    /** Stands in for the Redis or local publisher and records what it was asked to send. */
    static class RecordingPublisher implements ChatEventPublisher {
        private final List<ChatEvent> published = new ArrayList<>();

        @Override
        public void publish(ChatEvent event) {
            published.add(event);
        }
    }

    /**
     * A transaction manager with no resource behind it.
     * {@link AbstractPlatformTransactionManager} supplies the synchronization machinery that
     * {@code AFTER_COMMIT} listeners hang off, which is the whole of what these tests observe.
     */
    static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // Nothing to open.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // Nothing to commit; the synchronizations fire around this call.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to undo.
        }
    }

    // Registers the TransactionalEventListenerFactory. Without it the listener's method is
    // adapted by the default factory instead and fires immediately on publish, which is exactly
    // the behaviour these tests exist to rule out. Production gets it from the application class.
    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoResourceTransactionManager();
        }

        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        @Bean
        ChatEventListener chatEventListener(ChatEventPublisher publisher) {
            return new ChatEventListener(publisher);
        }
    }
}
