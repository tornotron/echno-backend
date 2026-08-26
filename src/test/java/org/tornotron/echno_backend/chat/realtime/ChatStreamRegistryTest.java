package org.tornotron.echno_backend.chat.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatStreamRegistry}: which emitters an event reaches, how emitters
 * are released, and the per-employee connection cap. No Spring context and no database; the
 * registry is a plain in-memory structure.
 */
class ChatStreamRegistryTest {

    private static final Long ORG = 1L;
    private static final Long OTHER_ORG = 2L;
    private static final Long ALICE = 10L;
    private static final Long BOB = 11L;
    private static final Long CAROL = 12L;

    private ChatStreamRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChatStreamRegistry();
    }

    /** An emitter that records what was written to it instead of touching a real response. */
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
    void deliver_reachesOnlyTheListedRecipients() {
        RecordingEmitter alice = new RecordingEmitter();
        RecordingEmitter bob = new RecordingEmitter();
        RecordingEmitter carol = new RecordingEmitter();
        registry.register(ORG, ALICE, alice);
        registry.register(ORG, BOB, bob);
        registry.register(ORG, CAROL, carol);

        registry.deliver(new ChatEvent(ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, ALICE,
                List.of(ALICE, BOB)));

        assertThat(alice.sent).hasSize(1);
        assertThat(bob.sent).hasSize(1);
        assertThat(carol.sent).isEmpty();
    }

    @Test
    void deliver_doesNotCrossTenantsForTheSameEmployeeId() {
        RecordingEmitter inOrg = new RecordingEmitter();
        RecordingEmitter inOtherOrg = new RecordingEmitter();
        registry.register(ORG, ALICE, inOrg);
        registry.register(OTHER_ORG, ALICE, inOtherOrg);

        registry.deliver(new ChatEvent(ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, ALICE,
                List.of(ALICE)));

        assertThat(inOrg.sent).hasSize(1);
        assertThat(inOtherOrg.sent).isEmpty();
    }

    @Test
    void deliver_reachesEveryOpenTabOfOneEmployee() {
        RecordingEmitter tabOne = new RecordingEmitter();
        RecordingEmitter tabTwo = new RecordingEmitter();
        registry.register(ORG, ALICE, tabOne);
        registry.register(ORG, ALICE, tabTwo);

        registry.deliver(new ChatEvent(ChatEventType.ROOM_UPDATED, ORG, 5L, null, ALICE,
                List.of(ALICE)));

        assertThat(tabOne.sent).hasSize(1);
        assertThat(tabTwo.sent).hasSize(1);
    }

    @Test
    void register_evictsTheOldestBeyondTheConnectionCap() {
        List<RecordingEmitter> emitters = new ArrayList<>();
        for (int i = 0; i < ChatStreamRegistry.MAX_STREAMS_PER_EMPLOYEE + 1; i++) {
            RecordingEmitter emitter = new RecordingEmitter();
            emitters.add(emitter);
            registry.register(ORG, ALICE, emitter);
        }

        // The cap holds, so a client that reconnects faster than it releases cannot accumulate
        // emitters until the heap gives out.
        assertThat(registry.streamCount(ORG, ALICE))
                .isEqualTo(ChatStreamRegistry.MAX_STREAMS_PER_EMPLOYEE);

        registry.deliver(new ChatEvent(ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, BOB,
                List.of(ALICE)));

        // It is the oldest that lost its place, not the newest: the surviving connections are
        // the ones the client most recently opened.
        assertThat(emitters.get(0).sent).isEmpty();
        assertThat(emitters.subList(1, emitters.size())).allSatisfy(e -> assertThat(e.sent).hasSize(1));
    }

    @Test
    void unregister_removesTheEmitterAndDropsTheEmptyKey() {
        RecordingEmitter alice = new RecordingEmitter();
        registry.register(ORG, ALICE, alice);

        registry.unregister(ORG, ALICE, alice);

        assertThat(registry.streamCount(ORG, ALICE)).isZero();
        assertThat(registry.totalStreams()).isZero();
    }

    @Test
    void deliver_releasesAnEmitterThatFailsToWrite() {
        SseEmitter broken = new SseEmitter(60_000L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("client went away");
            }
        };
        registry.register(ORG, ALICE, broken);

        registry.deliver(new ChatEvent(ChatEventType.MESSAGE_CREATED, ORG, 5L, 99L, BOB,
                List.of(ALICE)));

        // A browser that closed mid-write leaves a dead emitter behind. Delivery is the only
        // moment that discovers it, so it has to do the cleanup itself.
        assertThat(registry.streamCount(ORG, ALICE)).isZero();
    }

    @Test
    void heartbeat_writesToEveryLiveStream() {
        RecordingEmitter alice = new RecordingEmitter();
        RecordingEmitter bob = new RecordingEmitter();
        registry.register(ORG, ALICE, alice);
        registry.register(OTHER_ORG, BOB, bob);

        registry.heartbeat();

        assertThat(alice.sent).hasSize(1);
        assertThat(bob.sent).hasSize(1);
    }
}
