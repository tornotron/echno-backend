package org.tornotron.echno_backend.modules.toolboxtalks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.configuration.ThymeleafConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkAttendeeDto;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;
import org.tornotron.echno_backend.modules.toolboxtalks.pdf.ToolboxTalkPdfService;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;
import org.tornotron.echno_backend.pdfGeneration.PdfRenderer;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.spatial.SpatialNode;
import org.tornotron.echno_backend.project.spatial.SpatialNodeRepository;

/**
 * The talk record renders through the real template engine and renderer; only the reads are
 * mocked. A PDF is proven by its header, and the attendee column by the names it resolves.
 */
class ToolboxTalkPdfServiceTest {

    private static final UUID TALK_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ZONE_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");

    private final ToolboxTalksService talks = mock(ToolboxTalksService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final SpatialNodeRepository spatialNodes = mock(SpatialNodeRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final ToolboxTalkPdfService service = new ToolboxTalkPdfService(
            new ThymeleafConfig().pdfTemplateEngine(), new PdfRenderer(),
            talks, projectService, spatialNodes, employees);

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentOrgId(7L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rendersARecordedTalkWithItsAttendeeColumn() throws Exception {
        when(talks.get(TALK_ID)).thenReturn(talk(ToolboxTalkStatus.RECORDED, List.of(21L, 22L)));
        ProjectDto project = new ProjectDto();
        project.setProjectName("Tower B");
        when(projectService.getAProject(3L)).thenReturn(project);
        SpatialNode zone = new SpatialNode();
        zone.setName("Z1");
        zone.setPath("B1 / L03 / Z1");
        when(spatialNodes.findByIdScoped(ZONE_ID)).thenReturn(Optional.of(zone));
        when(employees.findAllByIdInAndOrganizationId(any(), anyLong()))
                .thenReturn(List.of(employee(20L, "Ravi Supervisor"), employee(21L, "Mason One"), employee(22L, "Mason Two")));

        RenderedReport report = service.render(TALK_ID);

        assertThat(report.documentName()).isEqualTo("toolbox-talk-2026-09-19-11111111");
        assertThat(new String(report.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void rendersADraftWithNoPlaceNoAttendeesAndAnUnknownProject() throws Exception {
        when(talks.get(TALK_ID)).thenReturn(talk(ToolboxTalkStatus.DRAFT, List.of()));
        when(projectService.getAProject(3L)).thenThrow(new RuntimeException("gone"));
        when(employees.findAllByIdInAndOrganizationId(any(), anyLong())).thenReturn(List.of());

        RenderedReport report = service.render(TALK_ID);

        assertThat(new String(report.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    private static ToolboxTalkDto talk(ToolboxTalkStatus status, List<Long> attendeeIds) {
        return new ToolboxTalkDto(TALK_ID, 3L, status == ToolboxTalkStatus.RECORDED ? ZONE_ID : null,
                "Working at height", LocalDate.of(2026, 9, 19), LocalTime.of(7, 30), 20L,
                attendeeIds.stream().map(ToolboxTalkAttendeeDto::new).toList(),
                "Harness checks before the scaffold", status,
                status == ToolboxTalkStatus.RECORDED ? LocalDateTime.of(2026, 9, 19, 7, 45) : null,
                LocalDateTime.of(2026, 9, 19, 7, 0), LocalDateTime.of(2026, 9, 19, 7, 45));
    }

    private static Employee employee(Long id, String name) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setEmployeeName(name);
        return employee;
    }
}
