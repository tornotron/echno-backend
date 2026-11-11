package org.tornotron.echno_backend.modules.assistant.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.Subject;

/**
 * What the attendance provider says about itself, and what it does with what the attendance service
 * returns: the units on every field, the split between "nothing recorded" and "could not be read",
 * every page of a big day, and a refusal staying a refusal.
 */
class AttendanceProviderTest {

    private static final Long PROJECT = 42L;
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);
    private static final Question QUESTION = new Question("Who was on site this week?");

    private final AttendanceService service = mock(AttendanceService.class);
    private final AttendanceProvider provider = new AttendanceProvider(service);

    // ---------------------------------------------------------- the descriptor

    @Test
    void describesItselfWithFieldNamesAndUnits() {
        ProviderDescriptor descriptor = provider.describe();

        assertThat(descriptor.id()).isEqualTo("attendance");
        assertThat(descriptor.subjects()).containsExactly(Subject.ATTENDANCE);
        assertThat(descriptor.needsPeriod()).isTrue();
        assertThat(descriptor.carriesPersonalData()).isTrue();
        assertThat(descriptor.answers()).isNotBlank();
        assertThat(descriptor.fields()).extracting(FieldSpec::name)
                .containsExactly("date", "projectId", "recordCount", "onSiteCount", "statusCounts",
                        "totalWorkMinutes", "overtimeMinutes");
        // The point of a field spec: no bare numbers.
        assertThat(descriptor.fields()).allSatisfy(field -> {
            assertThat(field.unit()).isNotBlank();
            assertThat(field.meaning()).isNotBlank();
        });
        assertThat(descriptor.fields()).filteredOn(f -> f.name().endsWith("Minutes"))
                .extracting(FieldSpec::unit).containsOnly("minutes");
    }

    @Test
    void theStatusFieldNamesEveryStatusTheAttendanceModuleHasSoItCannotDrift() {
        FieldSpec statusCounts = provider.describe().fields().stream()
                .filter(f -> f.name().equals("statusCounts")).findFirst().orElseThrow();

        for (AttendanceStatus status : AttendanceStatus.values()) {
            assertThat(statusCounts.meaning()).contains(status.name());
        }
    }

    @Test
    void theOnSiteFieldNamesEveryIncludedStatusInItsMeaningSoTheDefinitionTravelsWithTheValue() {
        FieldSpec onSiteCount = provider.describe().fields().stream()
                .filter(f -> f.name().equals("onSiteCount")).findFirst().orElseThrow();

        assertThat(AttendanceProvider.ON_SITE_STATUSES).containsExactlyInAnyOrder(
                AttendanceStatus.PRESENT,
                AttendanceStatus.LATE,
                AttendanceStatus.HALF_DAY,
                AttendanceStatus.EARLY_CHECKOUT,
                AttendanceStatus.OVERTIME);
        for (AttendanceStatus status : AttendanceProvider.ON_SITE_STATUSES) {
            assertThat(onSiteCount.meaning()).contains(status.name());
        }
    }

    // ---------------------------------------------------------- evidence

    @Test
    void summarisesEachDayThatHasRecordsWithTheSourcesOwnStatusNamesAndOnSiteCount() {
        LocalDate tuesday = MONDAY.plusDays(1);
        when(day(MONDAY)).thenReturn(List.of(
                record(AttendanceStatus.PRESENT, 480, 30),
                record(AttendanceStatus.PRESENT, 450, 0),
                record(AttendanceStatus.LATE, 400, null),
                record(AttendanceStatus.HALF_DAY, 240, 0),
                record(AttendanceStatus.EARLY_CHECKOUT, 360, 0),
                record(AttendanceStatus.OVERTIME, 540, 60),
                record(AttendanceStatus.ABSENT, null, null),
                record(AttendanceStatus.LEAVE, 0, 0),
                record(AttendanceStatus.WEEKLY_OFF, 0, 0),
                record(AttendanceStatus.PENDING_REGULARIZATION, 0, 0)));
        when(day(tuesday)).thenReturn(List.of(record(AttendanceStatus.HOLIDAY, 0, 0)));

        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, tuesday, null));

        List<EvidenceUnit> units = ((ProviderResult.Evidence) result).units();
        assertThat(units).hasSize(2);
        EvidenceUnit monday = units.get(0);
        assertThat(monday.id()).isEqualTo("attendance:2026-08-10:project-42");
        assertThat(monday.providerId()).isEqualTo("attendance");
        assertThat(monday.kind()).isEqualTo("attendance-day-summary");
        assertThat(monday.relevance()).isEqualTo(1.0);
        assertThat(monday.source().type()).isEqualTo("project-attendance");
        assertThat(monday.source().id()).isEqualTo("42/2026-08-10");
        assertThat(monday.values()).containsEntry("date", "2026-08-10")
                .containsEntry("projectId", 42L)
                .containsEntry("recordCount", 10)
                // Only PRESENT (2), LATE (1), HALF_DAY (1), EARLY_CHECKOUT (1), OVERTIME (1) = 6;
                // ABSENT, LEAVE, WEEKLY_OFF, HOLIDAY, PENDING_REGULARIZATION do not count as on site.
                .containsEntry("onSiteCount", 6)
                // 480 + 450 + 400 + 240 + 360 + 540; the ABSENT record with no figure adds nothing.
                .containsEntry("totalWorkMinutes", 2470L)
                .containsEntry("overtimeMinutes", 90L);
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) monday.values().get("statusCounts");
        assertThat(counts).containsExactly(
                Map.entry("PRESENT", 2),
                Map.entry("HALF_DAY", 1),
                Map.entry("ABSENT", 1),
                Map.entry("LEAVE", 1),
                Map.entry("WEEKLY_OFF", 1),
                Map.entry("LATE", 1),
                Map.entry("EARLY_CHECKOUT", 1),
                Map.entry("OVERTIME", 1),
                Map.entry("PENDING_REGULARIZATION", 1));
        assertThat(units.get(1).values())
                .containsEntry("recordCount", 1)
                .containsEntry("onSiteCount", 0);
    }

    @Test
    void everyFieldItReturnsIsOneItDeclaredAndNothingElse() {
        when(day(MONDAY)).thenReturn(List.of(record(AttendanceStatus.PRESENT, 480, 0)));

        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY, null));

        EvidenceUnit unit = ((ProviderResult.Evidence) result).units().get(0);
        assertThat(unit.values().keySet()).containsExactlyInAnyOrderElementsOf(
                provider.describe().fields().stream().map(FieldSpec::name).toList());
    }

    @Test
    void skipsADayWithNoRecordsButStillAnswersForTheOthers() {
        when(day(MONDAY)).thenReturn(List.of());
        when(day(MONDAY.plusDays(1))).thenReturn(List.of(record(AttendanceStatus.PRESENT, 480, 0)));

        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY.plusDays(1), null));

        assertThat(((ProviderResult.Evidence) result).units())
                .extracting(EvidenceUnit::id).containsExactly("attendance:2026-08-11:project-42");
    }

    // ---------------------------------------------------------- Empty versus Unavailable

    @Test
    void nothingRecordedIsEmptyAndSaysWhatWasLookedFor() {
        when(day(MONDAY)).thenReturn(List.of());

        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY, null));

        assertThat(result).isInstanceOf(ProviderResult.Empty.class);
        assertThat(((ProviderResult.Empty) result).lookedFor())
                .contains("project 42").contains("2026-08-10");
    }

    @Test
    void aFailureToReadIsUnavailableNotEmptyAndLeaksNoInternals() {
        when(day(MONDAY)).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host/db exploded"));

        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY, null));

        assertThat(result).isInstanceOf(ProviderResult.Unavailable.class);
        assertThat(((ProviderResult.Unavailable) result).reason())
                .contains("could not be read").doesNotContain("secret-host").doesNotContain("exploded");
    }

    @Test
    void aRefusalStaysARefusalInsteadOfBecomingACouldNotBeRead() {
        when(day(MONDAY)).thenThrow(new AccessDeniedException("no"));

        assertThatThrownBy(() -> provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noProjectNoPeriodOrTooLongAPeriodIsUnavailableAndNeverReachesTheService() {
        assertThat(provider.retrieve(QUESTION, new Scope(null, MONDAY, MONDAY, null)))
                .isInstanceOf(ProviderResult.Unavailable.class);
        assertThat(provider.retrieve(QUESTION, new Scope(PROJECT, null, null, null)))
                .isInstanceOf(ProviderResult.Unavailable.class);
        ProviderResult tooLong = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY.plusDays(31), null));
        assertThat(tooLong).isInstanceOf(ProviderResult.Unavailable.class);
        assertThat(((ProviderResult.Unavailable) tooLong).reason()).contains("31").contains("32");

        verify(service, never()).getAttendanceByProject(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPeriodOfExactlyThirtyOneDaysIsAllowed() {
        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY.plusDays(30), null));

        assertThat(result).isInstanceOf(ProviderResult.Empty.class);
        verify(service, times(31)).getAttendanceByProject(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------- paging

    @Test
    void readsEveryPageOfABigDayInsteadOfTruncatingAtTheFirst() {
        List<AttendanceResponseDto> fullPage = Collections.nCopies(AttendanceProvider.PAGE_SIZE,
                record(AttendanceStatus.PRESENT, 480, 0));
        List<AttendanceResponseDto> lastPage = Collections.nCopies(3, record(AttendanceStatus.ABSENT, null, null));
        when(service.getAttendanceByProject(eq(PROJECT), eq(MONDAY), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class))).thenReturn(fullPage, lastPage);

        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY, null));

        EvidenceUnit unit = ((ProviderResult.Evidence) result).units().get(0);
        assertThat(unit.values())
                .containsEntry("recordCount", AttendanceProvider.PAGE_SIZE + 3)
                .containsEntry("onSiteCount", AttendanceProvider.PAGE_SIZE);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(service, times(2)).getAttendanceByProject(any(), any(), any(), any(), any(), any(), pages.capture());
        assertThat(pages.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(0, 1);
        // A stable order, or a row could fall between two pages.
        assertThat(pages.getValue().getSort()).isEqualTo(Sort.by("employeeName", "id"));
    }

    @Test
    void aRunawayDayIsUnavailableNotAnEndlessLoop() {
        List<AttendanceResponseDto> fullPage = Collections.nCopies(AttendanceProvider.PAGE_SIZE,
                record(AttendanceStatus.PRESENT, 480, 0));
        when(service.getAttendanceByProject(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(fullPage);

        ProviderResult result = provider.retrieve(QUESTION, new Scope(PROJECT, MONDAY, MONDAY, null));

        assertThat(result).isInstanceOf(ProviderResult.Unavailable.class);
        verify(service, times(AttendanceProvider.MAX_PAGES_PER_DAY))
                .getAttendanceByProject(any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    // ---------------------------------------------------------- helpers

    private List<AttendanceResponseDto> day(LocalDate date) {
        return service.getAttendanceByProject(eq(PROJECT), eq(date), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class));
    }

    private static AttendanceResponseDto record(AttendanceStatus status, Integer workMinutes, Integer overtime) {
        return AttendanceResponseDto.builder().status(status).totalWorkMinutes(workMinutes)
                .overtimeMinutes(overtime).build();
    }
}
