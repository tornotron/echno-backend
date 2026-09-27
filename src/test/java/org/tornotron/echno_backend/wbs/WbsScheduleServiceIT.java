package org.tornotron.echno_backend.wbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.wbs.dto.WbsDependencyCreationDto;
import org.tornotron.echno_backend.wbs.dto.WbsDependencyDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementCreationDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementUpdateDto;
import org.tornotron.echno_backend.wbs.dto.WbsScheduleDto;
import org.tornotron.echno_backend.wbs.enums.WbsDependencyType;
import org.tornotron.echno_backend.wbs.mapper.WbsElementMapperImpl;

/**
 * The schedule side of work progress inspection on the real migration: dependencies (no loops, no
 * crossing projects or tenants, nothing rescheduled), milestones, the responsible party and the
 * one-read schedule with its delay.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WbsElementService.class, WbsElementMapperImpl.class, EmployeeMapperImpl.class, ShiftTimingMapperImpl.class,
        ScheduleIntegrationSupport.FixedClocks.class})
class WbsScheduleServiceIT extends ScheduleIntegrationSupport {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);

    @Test
    void aDependencyIsRecordedAndMovesNoDate() {
        WbsElementDto footings = activity(projectAId, "1", null, SEP_1, SEP_10);
        WbsElementDto columns = activity(projectAId, "2", null, SEP_10.plusDays(1), SEP_10.plusDays(20));

        WbsDependencyDto link = wbs.addDependency(projectAId,
                new WbsDependencyCreationDto(footings.getId(), columns.getId(), null, 2));

        assertThat(link.type()).isEqualTo(WbsDependencyType.FS);
        assertThat(link.lagDays()).isEqualTo(2);
        WbsScheduleDto schedule = wbs.getSchedule(projectAId);
        assertThat(schedule.dependencies()).extracting(WbsDependencyDto::predecessorWbsCode, WbsDependencyDto::successorWbsCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "2"));
        assertThat(schedule.activities()).filteredOn(a -> a.getWbsCode().equals("2"))
                .singleElement().satisfies(a -> assertThat(a.getStartDate()).isEqualTo(SEP_10.plusDays(1)));

        wbs.removeDependency(projectAId, link.id());
        assertThat(wbs.getSchedule(projectAId).dependencies()).isEmpty();
    }

    @Test
    void aLinkThatWouldCloseALoopOrRepeatIsRefused() {
        Long a = activity(projectAId, "1", null, SEP_1, SEP_10).getId();
        Long b = activity(projectAId, "2", null, SEP_1, SEP_10).getId();
        Long c = activity(projectAId, "3", null, SEP_1, SEP_10).getId();
        wbs.addDependency(projectAId, new WbsDependencyCreationDto(a, b, WbsDependencyType.FS, 0));
        wbs.addDependency(projectAId, new WbsDependencyCreationDto(b, c, WbsDependencyType.SS, 0));

        assertThatThrownBy(() -> wbs.addDependency(projectAId, new WbsDependencyCreationDto(c, a, null, null)))
                .as("c before a closes a -> b -> c -> a")
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> wbs.addDependency(projectAId, new WbsDependencyCreationDto(a, a, null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> wbs.addDependency(projectAId, new WbsDependencyCreationDto(a, b, null, null)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void aLinkStaysInsideOneProjectAndOneTenant() {
        Long a = activity(projectAId, "1", null, SEP_1, SEP_10).getId();
        Long otherProject = activity(projectA2Id, "1", null, SEP_1, SEP_10).getId();
        Long foreign = asTenant(orgBId, () -> activity(projectBId, "1", null, SEP_1, SEP_10).getId());

        assertThatThrownBy(() -> wbs.addDependency(projectAId, new WbsDependencyCreationDto(a, otherProject, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> wbs.addDependency(projectAId, new WbsDependencyCreationDto(a, foreign, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> wbs.getSchedule(projectBId)).isInstanceOf(ResourceNotFoundException.class);
        Long foreignLink = asTenant(orgBId, () -> {
            Long b2 = activity(projectBId, "2", null, SEP_1, SEP_10).getId();
            return wbs.addDependency(projectBId, new WbsDependencyCreationDto(foreign, b2, null, null)).id();
        });
        assertThatThrownBy(() -> wbs.removeDependency(projectBId, foreignLink)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aMilestoneIsAPointInTime() {
        WbsElementCreationDto slab = new WbsElementCreationDto();
        slab.setWbsCode("M1");
        slab.setTitle("Roof slab cast");
        slab.setIsMilestone(true);
        slab.setEndDate(SEP_10);
        WbsElementDto milestone = wbs.createWbsElement(projectAId, slab);

        assertThat(milestone.getIsMilestone()).isTrue();
        assertThat(milestone.getStartDate()).isEqualTo(SEP_10);

        WbsElementCreationDto stretched = new WbsElementCreationDto();
        stretched.setWbsCode("M2");
        stretched.setTitle("Handover");
        stretched.setIsMilestone(true);
        stretched.setStartDate(SEP_1);
        stretched.setEndDate(SEP_10);
        assertThatThrownBy(() -> wbs.createWbsElement(projectAId, stretched)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theResponsiblePartyBelongsToTheCallersOrganizationAndIsNamedOnTheSchedule() {
        WbsElementDto footings = activity(projectAId, "1", null, SEP_1, SEP_10);

        WbsElementUpdateDto foreignEmployee = new WbsElementUpdateDto();
        foreignEmployee.setResponsibleEmployeeId(employeeBId);
        assertThatThrownBy(() -> wbs.updateWbsElement(footings.getId(), foreignEmployee))
                .isInstanceOf(InvalidRequestException.class);
        WbsElementUpdateDto foreignContract = new WbsElementUpdateDto();
        foreignContract.setResponsibleSubContractId(subContractBId);
        assertThatThrownBy(() -> wbs.updateWbsElement(footings.getId(), foreignContract))
                .isInstanceOf(InvalidRequestException.class);

        WbsElementUpdateDto owners = new WbsElementUpdateDto();
        owners.setResponsibleEmployeeId(employeeAId);
        owners.setResponsibleSubContractId(subContractAId);
        wbs.updateWbsElement(footings.getId(), owners);

        WbsElementDto row = wbs.getSchedule(projectAId).activities().get(0);
        assertThat(row.getResponsibleEmployeeName()).isEqualTo("Ravi Kumar");
        assertThat(row.getResponsibleSubContractorName()).isEqualTo("Sree Builders");
    }

    @Test
    void anOpenActivityPastItsPlannedFinishShowsItsDelayWithoutMovingThePlan() {
        activity(projectAId, "1", null, SEP_1, SEP_10);

        WbsElementDto row = wbs.getSchedule(projectAId).activities().get(0);

        // Today at the sites is the 19th: nine days past the planned finish on the 10th.
        assertThat(row.getDelayDays()).isEqualTo(9);
        assertThat(row.getEndDate()).isEqualTo(SEP_10);

        WbsElementUpdateDto forecast = new WbsElementUpdateDto();
        forecast.setForecastEndDate(SEP_10.plusDays(15));
        WbsElementDto updated = wbs.updateWbsElement(row.getId(), forecast);
        assertThat(updated.getDelayDays()).isEqualTo(15);
        assertThat(updated.getEndDate()).isEqualTo(SEP_10);
    }

    @Test
    void aPlannedFinishBeforeThePlannedStartIsRefused() {
        assertThatThrownBy(() -> activity(projectAId, "1", null, SEP_10, SEP_1)).isInstanceOf(InvalidRequestException.class);
    }
}
