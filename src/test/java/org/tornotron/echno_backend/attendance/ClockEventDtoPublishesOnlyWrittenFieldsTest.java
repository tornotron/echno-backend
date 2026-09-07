package org.tornotron.echno_backend.attendance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tornotron.echno_backend.attendance.dto.ClockEventDto;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@link ClockEventDto} to fields something actually writes.
 *
 * <p>Three properties were published on every clock-event response while nothing in the
 * application ever filled them, so they were null on every response the server had ever served.
 *
 * <p>{@code photoUrl} was the leftover half of a finished migration: the punch photo moved to
 * {@code Attachment}, the entity's own {@code photo_url} field was deleted, and the mapper was
 * left declaring {@code @Mapping(target = "photoUrl", ignore = true)} to fill the hole. The
 * photos have been returned in {@code attachments} ever since, one per punch, each already
 * carrying a signed download URL, which is what a client should read.
 *
 * <p>{@code verifiedBy} and {@code verifiedAt} were the unstarted half. The columns were created
 * beside {@code MovementRecord}'s identically named ones, but only the movement's were ever
 * wired to a service. A clock event has neither the {@code verified_by_id} nor the
 * {@code is_verified} that make that flow work, and the live "somebody checked this" state for
 * attendance is the day-level {@code approvalStatus} on {@link Attendance}. Anything that wants
 * per-punch verification has to design it, and would not inherit these two.
 *
 * <p>This is a guard against reintroduction, not a style rule: a field here means the contract
 * promises a client something it will never receive.
 */
class ClockEventDtoPublishesOnlyWrittenFieldsTest {

    @ParameterizedTest(name = "ClockEventDto has no {0}")
    @ValueSource(strings = {"photoUrl", "verifiedBy", "verifiedAt"})
    @DisplayName("the fields nothing wrote are off the response contract")
    void unwrittenFieldsAreNotPublished(String property) {
        assertThat(Arrays.stream(ClockEventDto.class.getDeclaredFields()).map(Field::getName))
                .as("%s was null on every response ever served; a client cannot use it", property)
                .doesNotContain(property);
    }

    @Test
    @DisplayName("the punch's photos are still reachable, through attachments")
    void attachmentsCarryThePunchPhotos() {
        assertThat(Arrays.stream(ClockEventDto.class.getDeclaredFields()).map(Field::getName))
                .as("removing photoUrl is only safe while the attachments list remains")
                .contains("attachments");
    }
}
