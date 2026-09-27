package org.tornotron.echno_backend.modules.workprogress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.modules.workprogress.domain.DelayReason;
import org.tornotron.echno_backend.modules.workprogress.domain.ProgressOutcome;
import org.tornotron.echno_backend.modules.workprogress.dto.ProgressInspectionDto;
import org.tornotron.echno_backend.modules.workprogress.dto.RecordProgressInspectionRequest;
import org.tornotron.echno_backend.modules.workprogress.service.ProgressInspectionActivityRecords;
import org.tornotron.echno_backend.modules.workprogress.service.ProgressInspectionService;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.wbs.ScheduleIntegrationSupport;
import org.tornotron.echno_backend.wbs.WbsElementService;
import org.tornotron.echno_backend.wbs.dto.WbsDependencyCreationDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementCreationDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementDto;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;
import org.tornotron.echno_backend.wbs.mapper.WbsElementMapperImpl;

/**
 * Progress inspections on the real migration: what a record does to its activity (and only to
 * it), the outcome rules, the delay rule, tenant isolation, and that an inspected activity stays
 * part of the record.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProgressInspectionService.class, ProgressInspectionActivityRecords.class, WbsElementService.class,
        WbsElementMapperImpl.class, EmployeeMapperImpl.class, ShiftTimingMapperImpl.class, UserContextService.class,
        TenantEntityHelper.class, ScheduleIntegrationSupport.FixedClocks.class})
class ProgressInspectionServiceIT extends ScheduleIntegrationSupport {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

    @Autowired
    private ProgressInspectionService service;

    @Test
    void aPartlyDoneRecordUpdatesTheActivityAndRollsUpWithoutTouchingThePlan() {
        Long parent = activity(projectAId, "1", null, SEP_1, SEP_30).getId();
        Long footings = activity(projectAId, "1.1", parent, SEP_1, SEP_30).getId();
        activity(projectAId, "1.2", parent, SEP_1, SEP_30);

        ProgressInspectionDto recorded = service.record(new RecordProgressInspectionRequest(footings, TODAY,
                ProgressOutcome.PARTIAL, BigDecimal.valueOf(50), SEP_1.plusDays(4), null, null, null, null, null,
                "Grid A done"));

        assertThat(recorded.delayDays()).isZero();
        assertThat(recorded.plannedFinishDate()).isEqualTo(SEP_30);
        assertThat(recorded.wbsCode()).isEqualTo("1.1");
        WbsElementDto after = wbs.getWbsElementById(footings);
        assertThat(after.getProgress()).isEqualTo(50.0);
        assertThat(after.getStatus()).isEqualTo(WbsStatus.IN_PROGRESS);
        assertThat(after.getActualStartDate()).isEqualTo(SEP_1.plusDays(4));
        assertThat(after.getEndDate()).isEqualTo(SEP_30);
        assertThat(wbs.getWbsElementById(parent).getProgress()).isEqualTo(25.0);
        assertThat(service.list(projectAId, footings, 0, 10).getContent()).extracting(ProgressInspectionDto::id)
                .containsExactly(recorded.id());
    }

    @Test
    void aLateActivityNeedsAReasonAndGetsAForecastWhileItsSuccessorKeepsItsDates() {
        Long footings = activity(projectAId, "1", null, SEP_1, SEP_10).getId();
        Long columns = activity(projectAId, "2", null, SEP_10.plusDays(1), SEP_30).getId();
        wbs.addDependency(projectAId, new WbsDependencyCreationDto(footings, columns, null, 0));

        assertThatThrownBy(() -> service.record(new RecordProgressInspectionRequest(footings, TODAY,
                ProgressOutcome.NOT_DONE, null, null, null, SEP_10.plusDays(15), null, null, null, null)))
                .as("nine days past the planned finish, no reason given")
                .isInstanceOf(InvalidRequestException.class);

        ProgressInspectionDto recorded = service.record(new RecordProgressInspectionRequest(footings, TODAY,
                ProgressOutcome.NOT_DONE, null, null, null, SEP_10.plusDays(15), DelayReason.MATERIAL,
                "Steel not delivered", null, null));

        assertThat(recorded.delayDays()).isEqualTo(15);
        assertThat(recorded.percentComplete()).isEqualByComparingTo(BigDecimal.ZERO);
        WbsElementDto late = wbs.getWbsElementById(footings);
        assertThat(late.getForecastEndDate()).isEqualTo(SEP_10.plusDays(15));
        assertThat(late.getEndDate()).isEqualTo(SEP_10);
        assertThat(late.getStatus()).isEqualTo(WbsStatus.NOT_STARTED);
        WbsElementDto successor = wbs.getWbsElementById(columns);
        assertThat(successor.getStartDate()).isEqualTo(SEP_10.plusDays(1));
        assertThat(successor.getEndDate()).isEqualTo(SEP_30);
    }

    @Test
    void aDoneRecordCompletesTheActivityAndClosesItToFurtherRecords() {
        Long footings = activity(projectAId, "1", null, SEP_1, SEP_10).getId();

        ProgressInspectionDto done = service.record(new RecordProgressInspectionRequest(footings, TODAY,
                ProgressOutcome.DONE, null, SEP_1, SEP_10.plusDays(2), null, DelayReason.WEATHER, null, null, null));

        assertThat(done.delayDays()).isEqualTo(2);
        assertThat(done.percentComplete()).isEqualByComparingTo("100");
        WbsElementDto after = wbs.getWbsElementById(footings);
        assertThat(after.getStatus()).isEqualTo(WbsStatus.COMPLETED);
        assertThat(after.getProgress()).isEqualTo(100.0);
        assertThat(after.getActualEndDate()).isEqualTo(SEP_10.plusDays(2));
        assertThatThrownBy(() -> service.record(new RecordProgressInspectionRequest(footings, TODAY,
                ProgressOutcome.DONE, null, null, SEP_10.plusDays(2), null, DelayReason.WEATHER, null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theOutcomeFixesThePercentAndTheDates() {
        Long parent = activity(projectAId, "1", null, SEP_1, SEP_30).getId();
        Long leaf = activity(projectAId, "1.1", parent, SEP_1, SEP_30).getId();
        WbsElementCreationDto point = new WbsElementCreationDto();
        point.setWbsCode("M1");
        point.setTitle("Slab cast");
        point.setIsMilestone(true);
        point.setEndDate(SEP_30);
        Long milestone = wbs.createWbsElement(projectAId, point).getId();

        assertThatThrownBy(() -> record(leaf, ProgressOutcome.PARTIAL, null, SEP_1, null))
                .as("partly done needs a percent").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> record(leaf, ProgressOutcome.PARTIAL, BigDecimal.valueOf(100), SEP_1, null))
                .as("100 percent is done").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> record(leaf, ProgressOutcome.PARTIAL, BigDecimal.TEN, null, null))
                .as("work has a start date").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> record(leaf, ProgressOutcome.DONE, null, SEP_1, null))
                .as("done needs a finish date").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> record(leaf, ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_1, SEP_10))
                .as("only done has a finish date").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> record(milestone, ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_1, null))
                .as("a milestone is reached or not").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> record(parent, ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_1, null))
                .as("a parent is rolled up, not inspected").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.record(new RecordProgressInspectionRequest(leaf, TODAY.plusDays(1),
                ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_1, null, null, null, null, null, null)))
                .as("the 20th is tomorrow at the sites").isInstanceOf(InvalidRequestException.class);

        record(leaf, ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_1, null);
        assertThatThrownBy(() -> record(leaf, ProgressOutcome.NOT_DONE, null, null, null))
                .as("progress already recorded").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> record(leaf, ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_10, null))
                .as("the start is already on record").isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aRecordCannotBeDatedBeforeTheActivitysLatestInspection() {
        Long leaf = activity(projectAId, "1", null, SEP_1, SEP_30).getId();
        record(leaf, ProgressOutcome.PARTIAL, BigDecimal.valueOf(30), SEP_1, null);

        assertThatThrownBy(() -> service.record(new RecordProgressInspectionRequest(leaf, TODAY.minusDays(1),
                ProgressOutcome.PARTIAL, BigDecimal.valueOf(20), null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void anotherTenantsActivityAndRecordsReadAsAbsent() {
        UUID foreignRecord = asTenant(orgBId, () -> {
            Long foreignActivity = activity(projectBId, "1", null, SEP_1, SEP_30).getId();
            return service.record(new RecordProgressInspectionRequest(foreignActivity, TODAY, ProgressOutcome.PARTIAL,
                    BigDecimal.TEN, SEP_1, null, null, null, null, null, null)).id();
        });
        Long foreignActivity = asTenant(orgBId, () -> wbs.getSchedule(projectBId).activities().get(0).getId());

        assertThatThrownBy(() -> service.get(foreignRecord)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listEvidence(foreignRecord)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.list(null, null, 0, 10).getContent()).isEmpty();
        assertThatThrownBy(() -> record(foreignActivity, ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_1, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anInspectedActivityIsPartOfTheRecordAndCannotBeDeleted() {
        Long parent = activity(projectAId, "1", null, SEP_1, SEP_30).getId();
        Long footings = activity(projectAId, "1.1", parent, SEP_1, SEP_30).getId();
        Long untouched = activity(projectAId, "2", null, SEP_1, SEP_30).getId();
        record(footings, ProgressOutcome.PARTIAL, BigDecimal.TEN, SEP_1, null);

        assertThatThrownBy(() -> wbs.deleteWbsElement(footings)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> wbs.deleteWbsElement(parent)).isInstanceOf(InvalidRequestException.class);
        wbs.deleteWbsElement(untouched);
    }

    private ProgressInspectionDto record(Long activityId, ProgressOutcome outcome, BigDecimal percent,
                                         LocalDate start, LocalDate finish) {
        return service.record(new RecordProgressInspectionRequest(activityId, TODAY, outcome, percent, start, finish,
                null, null, null, null, null));
    }
}
