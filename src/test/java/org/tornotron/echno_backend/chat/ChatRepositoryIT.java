package org.tornotron.echno_backend.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the chat repositories against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Covers tenant isolation on rooms, the
 * participant-scoped room query, and the unread-count computation over sent messages.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatRepositoryIT extends AbstractIntegrationTest {

    private static final Long ALICE = 100L;
    private static final Long BOB = 200L;
    private static final Long CAROL = 300L;

    @Autowired
    private ChatRoomRepository roomRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByIdAndOrganization_doesNotReturnAcrossTenants() {
        Organization orgA = persistOrganization("Chat Org A");
        Organization orgB = persistOrganization("Chat Org B");
        ChatRoom room = persistDirectRoom(orgA, ALICE, BOB);
        em.flush();
        em.clear();

        Optional<ChatRoom> sameTenant = roomRepository.findByIdAndOrganization_Id(room.getId(), orgA.getId());
        Optional<ChatRoom> otherTenant = roomRepository.findByIdAndOrganization_Id(room.getId(), orgB.getId());

        assertThat(sameTenant).isPresent();
        assertThat(otherTenant).isEmpty();
    }

    @Test
    void findRoomsForEmployee_returnsOnlyRoomsTheEmployeeParticipatesIn() {
        Organization org = persistOrganization("Chat Org C");
        ChatRoom aliceBob = persistDirectRoom(org, ALICE, BOB);
        ChatRoom bobCarol = persistDirectRoom(org, BOB, CAROL);
        em.flush();
        em.clear();

        List<ChatRoom> aliceRooms = roomRepository.findRoomsForEmployee(ALICE);

        assertThat(aliceRooms).extracting(ChatRoom::getId).contains(aliceBob.getId());
        assertThat(aliceRooms).extracting(ChatRoom::getId).doesNotContain(bobCarol.getId());
    }

    @Test
    void findDirectRoomBetween_findsTheExistingOneToOneRoom() {
        Organization org = persistOrganization("Chat Org D");
        ChatRoom aliceBob = persistDirectRoom(org, ALICE, BOB);
        em.flush();
        em.clear();

        List<ChatRoom> between = roomRepository.findDirectRoomBetween(ALICE, BOB);

        assertThat(between).extracting(ChatRoom::getId).containsExactly(aliceBob.getId());
    }

    @Test
    void countUnread_excludesOwnMessagesAndRespectsLastReadAt() {
        Organization org = persistOrganization("Chat Org E");
        ChatRoom room = persistDirectRoom(org, ALICE, BOB);
        persistMessage(org, room, BOB, "hi Alice");
        persistMessage(org, room, BOB, "you there?");
        persistMessage(org, room, ALICE, "yes, here");
        em.flush();
        em.clear();

        // Alice has never read: every message from Bob counts, her own does not.
        long neverRead = messageRepository.countUnread(room.getId(), ALICE, null);
        assertThat(neverRead).isEqualTo(2);

        // With a last-read marker in the distant past, the same two still count.
        long readLongAgo = messageRepository.countUnread(room.getId(), ALICE, LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThat(readLongAgo).isEqualTo(2);

        // With a last-read marker in the future, nothing is unread.
        long readInFuture = messageRepository.countUnread(room.getId(), ALICE, LocalDateTime.of(2999, 1, 1, 0, 0));
        assertThat(readInFuture).isZero();
    }

    @Test
    void findFirstByRoom_returnsLatestNonDeletedMessage() {
        Organization org = persistOrganization("Chat Org F");
        ChatRoom room = persistDirectRoom(org, ALICE, BOB);
        persistMessage(org, room, BOB, "first");
        ChatMessage deleted = persistMessage(org, room, ALICE, "was deleted");
        deleted.setDeleted(true);
        em.persist(deleted);
        em.flush();
        em.clear();

        Optional<ChatMessage> last =
                messageRepository.findFirstByRoom_IdAndDeletedFalseOrderByCreatedAtDesc(room.getId());

        assertThat(last).isPresent();
        assertThat(last.get().isDeleted()).isFalse();
    }

    // --- helpers -----------------------------------------------------------------

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private ChatRoom persistDirectRoom(Organization org, Long employeeA, Long employeeB) {
        ChatRoom room = new ChatRoom();
        room.setType("direct");
        room.setOrganization(org);
        room.addParticipant(newParticipant(org, employeeA));
        room.addParticipant(newParticipant(org, employeeB));
        em.persist(room);
        return room;
    }

    private ChatParticipant newParticipant(Organization org, Long employeeId) {
        ChatParticipant participant = new ChatParticipant();
        participant.setEmployeeId(employeeId);
        participant.setRole("member");
        participant.setOrganization(org);
        return participant;
    }

    private ChatMessage persistMessage(Organization org, ChatRoom room, Long senderId, String content) {
        ChatMessage message = new ChatMessage();
        message.setRoom(room);
        message.setOrganization(org);
        message.setSenderId(senderId);
        message.setContent(content);
        em.persist(message);
        return message;
    }
}
