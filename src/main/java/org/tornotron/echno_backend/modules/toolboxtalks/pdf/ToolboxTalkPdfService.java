package org.tornotron.echno_backend.modules.toolboxtalks.pdf;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkAttendeeDto;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;
import org.tornotron.echno_backend.pdfGeneration.PdfRenderer;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;
import org.tornotron.echno_backend.pdfGeneration.ReportText;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.spatial.SpatialNodeRepository;

/**
 * The one-page talk record: topic, project, place, conductor, the attendee column, notes.
 *
 * <p>Follows the shape of the inspection report without importing any of it: a Thymeleaf
 * template under {@code templates/toolbox-talks/}, filled from the module's own DTO and the
 * core's read services, rendered through the shared {@link PdfRenderer}. Names are resolved
 * here rather than stored on the talk, so the record prints what the roster says today.
 */
@Slf4j
@Service
public class ToolboxTalkPdfService {

    private static final String TEMPLATE = "toolbox-talks/talk-record";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final SpringTemplateEngine pdfTemplateEngine;
    private final PdfRenderer pdfRenderer;
    private final ToolboxTalksService talks;
    private final ProjectService projectService;
    private final SpatialNodeRepository spatialNodes;
    private final EmployeeRepository employees;

    public ToolboxTalkPdfService(
            @Qualifier("pdfTemplateEngine") SpringTemplateEngine pdfTemplateEngine,
            PdfRenderer pdfRenderer,
            ToolboxTalksService talks,
            ProjectService projectService,
            SpatialNodeRepository spatialNodes,
            EmployeeRepository employees) {
        this.pdfTemplateEngine = pdfTemplateEngine;
        this.pdfRenderer = pdfRenderer;
        this.talks = talks;
        this.projectService = projectService;
        this.spatialNodes = spatialNodes;
        this.employees = employees;
    }

    @Transactional(readOnly = true)
    public RenderedReport render(UUID talkId) throws IOException {
        ToolboxTalkDto talk = talks.get(talkId);
        Context ctx = populate(talk);
        byte[] pdf = pdfRenderer.render(pdfTemplateEngine.process(TEMPLATE, ctx));
        return new RenderedReport("toolbox-talk-" + talk.talkDate() + "-" + shortId(talk.id()), pdf);
    }

    private Context populate(ToolboxTalkDto talk) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(talk.conductorEmployeeId());
        for (ToolboxTalkAttendeeDto attendee : talk.attendees()) {
            ids.add(attendee.employeeId());
        }
        Map<Long, String> names = employeeNames(ids);

        List<AttendeeRow> rows = new ArrayList<>();
        int n = 1;
        for (ToolboxTalkAttendeeDto attendee : talk.attendees()) {
            rows.add(new AttendeeRow(n++, names.getOrDefault(attendee.employeeId(),
                    "Employee #" + attendee.employeeId())));
        }

        Context ctx = new Context();
        ctx.setVariable("topic", ReportText.orDash(talk.topic()));
        ctx.setVariable("projectName", resolveProjectName(talk.projectId()));
        ctx.setVariable("place", resolvePlace(talk.spatialNodeId()));
        ctx.setVariable("talkDate", ReportText.date(talk.talkDate()));
        ctx.setVariable("talkTime", talk.talkTime() == null ? ReportText.DASH : TIME.format(talk.talkTime()));
        ctx.setVariable("conductor", names.getOrDefault(talk.conductorEmployeeId(),
                "Employee #" + talk.conductorEmployeeId()));
        ctx.setVariable("statusLabel", ReportText.humanise(talk.status()));
        ctx.setVariable("recordedAt", ReportText.stamp(talk.recordedAt()));
        ctx.setVariable("notes", ReportText.orDash(talk.notes()));
        ctx.setVariable("attendees", rows);
        ctx.setVariable("attendeeCount", rows.size());
        ctx.setVariable("generatedOn", ReportText.generatedNow());
        return ctx;
    }

    private Map<Long, String> employeeNames(Set<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        Long orgId = TenantContext.getCurrentOrgId();
        if (ids.isEmpty() || orgId == null) {
            return names;
        }
        for (Employee employee : employees.findAllByIdInAndOrganizationId(ids, orgId)) {
            names.put(employee.getId(), ReportText.orDash(employee.getEmployeeName()));
        }
        return names;
    }

    private String resolveProjectName(Long projectId) {
        try {
            ProjectDto project = projectService.getAProject(projectId);
            if (project != null && project.getProjectName() != null && !project.getProjectName().isBlank()) {
                return project.getProjectName();
            }
        } catch (RuntimeException e) {
            log.debug("Could not resolve project {} for a toolbox talk record", projectId);
        }
        return "Project #" + projectId;
    }

    private String resolvePlace(UUID spatialNodeId) {
        if (spatialNodeId == null) {
            return ReportText.DASH;
        }
        return spatialNodes.findByIdScoped(spatialNodeId)
                .map(node -> node.getPath() != null && !node.getPath().isBlank() ? node.getPath() : node.getName())
                .map(ReportText::orDash)
                .orElse(ReportText.DASH);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /** One line of the attendee column. */
    public record AttendeeRow(int number, String name) {
    }
}
