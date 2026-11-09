package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.modules.inspections.compliance.web.ComplianceControllerWeb;
import org.tornotron.echno_backend.modules.inspections.dataset.web.DatasetExportControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ChecklistTemplateController;
import org.tornotron.echno_backend.modules.inspections.web.ChecklistTemplateControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ElementTypeControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.InspectionController;
import org.tornotron.echno_backend.modules.inspections.web.InspectionControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.InspectionEventController;
import org.tornotron.echno_backend.modules.inspections.web.InspectionEventControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.NcrController;
import org.tornotron.echno_backend.modules.inspections.web.NcrControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ObservationController;
import org.tornotron.echno_backend.modules.inspections.web.ObservationControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ReinspectionController;
import org.tornotron.echno_backend.modules.inspections.web.ReinspectionControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.TradeControllerWeb;

/**
 * Every inspection handler, on the tenant API and its {@code /web} twin, asks the access matrix for
 * the one action it performs. Inspections and observations are the inspections area, with a named
 * action for the compliance jobs and one for the dataset export; checklist templates, element types
 * and trades are inspection-checklists; non-conformance reports are ncrs, where signing one off, or
 * scheduling or recording its reinspection, takes the sign-off of the report's own discipline. A
 * machine observation stays on its role check (see {@code InspectionSecurityService}). A new handler
 * fails here until it is given its row.
 */
class InspectionsGuardsTest {

    static final String MAY_SEE = " and @detectionVisibility.maySee(#id)";
    static final String SIGN_OFFS = "@access.can('ncrs', 'sign-off-quality'), @access.can('ncrs', 'sign-off-safety')";
    static final String OUTCOME = "@inspectionSecurity.mayRecordReinspectionOutcome(#id, " + SIGN_OFFS + ")";

    /** Keyed by controller and handler, as {@link #key} names them; valued by the whole guard. */
    static final Map<String, String> GUARD_BY_HANDLER = Map.ofEntries(
            Map.entry("InspectionControllerWeb#create", can("inspections", "create")),
            Map.entry("InspectionControllerWeb#get", can("inspections", "read")),
            Map.entry("InspectionControllerWeb#list", can("inspections", "read")),
            Map.entry("InspectionControllerWeb#update", can("inspections", "edit")),
            Map.entry("InspectionControllerWeb#listAnnotations", can("inspections", "read")),
            Map.entry("InspectionControllerWeb#replaceAnnotations", can("inspections", "edit")),
            Map.entry("InspectionControllerWeb#downloadReport", can("inspections", "read")),
            Map.entry("InspectionControllerWeb#readEvidence", can("inspections", "read")),
            Map.entry("InspectionControllerWeb#attachEvidence", can("inspections", "create")),
            Map.entry("InspectionControllerWeb#presignEvidence", can("inspections", "create")),
            Map.entry("InspectionControllerWeb#registerEvidence", can("inspections", "create")),
            Map.entry("InspectionControllerWeb#removeEvidence", can("inspections", "edit")),
            Map.entry("ObservationControllerWeb#create", can("inspections", "create")),
            Map.entry("ObservationControllerWeb#read", can("inspections", "read") + MAY_SEE),
            Map.entry("ObservationControllerWeb#list", can("inspections", "read")),
            Map.entry("ObservationControllerWeb#review", can("inspections", "approve") + MAY_SEE),
            Map.entry("ObservationControllerWeb#reviewBatch", can("inspections", "approve")),
            Map.entry("ObservationControllerWeb#readEvidence", can("inspections", "read") + MAY_SEE),
            Map.entry("ObservationControllerWeb#presignEvidence", can("inspections", "create") + MAY_SEE),
            Map.entry("ObservationControllerWeb#registerEvidence", can("inspections", "create") + MAY_SEE),
            Map.entry("InspectionEventControllerWeb#inspectionTimeline", can("inspections", "read")),
            Map.entry("InspectionEventControllerWeb#ncrTimeline", can("ncrs", "read")),
            Map.entry("InspectionEventControllerWeb#search", can("inspections", "read")),
            Map.entry("InspectionEventController#inspectionTimeline", can("inspections", "read")),
            Map.entry("InspectionEventController#ncrTimeline", can("ncrs", "read")),
            Map.entry("InspectionEventController#search", can("inspections", "read")),
            Map.entry("ReinspectionControllerWeb#scheduleForNcr", signOff("#ncrId")),
            Map.entry("ReinspectionControllerWeb#listForNcr", can("ncrs", "read")),
            Map.entry("ReinspectionControllerWeb#scheduleForDefect", can("inspections", "create")),
            Map.entry("ReinspectionControllerWeb#get", can("inspections", "read")),
            Map.entry("ReinspectionControllerWeb#recordOutcome", OUTCOME),
            Map.entry("ReinspectionController#scheduleForNcr", signOff("#ncrId")),
            Map.entry("ReinspectionController#listForNcr", can("ncrs", "read")),
            Map.entry("ReinspectionController#scheduleForDefect", can("inspections", "create")),
            Map.entry("ReinspectionController#get", can("inspections", "read")),
            Map.entry("ReinspectionController#recordOutcome", OUTCOME),
            Map.entry("NcrControllerWeb#create", can("ncrs", "create")),
            Map.entry("NcrControllerWeb#get", can("ncrs", "read")),
            Map.entry("NcrControllerWeb#list", can("ncrs", "read")),
            Map.entry("NcrControllerWeb#assign", can("ncrs", "edit")),
            Map.entry("NcrControllerWeb#markCorrectiveActionComplete", can("ncrs", "report-corrective-action")),
            Map.entry("NcrControllerWeb#verify", signOff("#id")),
            Map.entry("NcrControllerWeb#reject", signOff("#id")),
            Map.entry("NcrControllerWeb#reopen", signOff("#id")),
            Map.entry("NcrControllerWeb#close", signOff("#id")),
            Map.entry("NcrControllerWeb#downloadPunchList", can("ncrs", "read")),
            Map.entry("NcrControllerWeb#downloadReport", can("ncrs", "read")),
            Map.entry("ChecklistTemplateControllerWeb#create", can("inspection-checklists", "create")),
            Map.entry("ChecklistTemplateControllerWeb#get", can("inspection-checklists", "read")),
            Map.entry("ChecklistTemplateControllerWeb#list", can("inspection-checklists", "read")),
            Map.entry("ChecklistTemplateControllerWeb#update", can("inspection-checklists", "edit")),
            Map.entry("ChecklistTemplateControllerWeb#applicable", can("inspection-checklists", "read")),
            Map.entry("ChecklistTemplateControllerWeb#listStarters", can("inspection-checklists", "read")),
            Map.entry("ChecklistTemplateControllerWeb#adoptStarter", can("inspection-checklists", "create")),
            Map.entry("ElementTypeControllerWeb#list", can("inspection-checklists", "read")),
            Map.entry("ElementTypeControllerWeb#catalogue", can("inspection-checklists", "read")),
            Map.entry("ElementTypeControllerWeb#create", can("inspection-checklists", "create")),
            Map.entry("ElementTypeControllerWeb#update", can("inspection-checklists", "edit")),
            Map.entry("TradeControllerWeb#list", can("inspection-checklists", "read")),
            Map.entry("TradeControllerWeb#catalogue", can("inspection-checklists", "read")),
            Map.entry("TradeControllerWeb#create", can("inspection-checklists", "create")),
            Map.entry("TradeControllerWeb#update", can("inspection-checklists", "edit")),
            Map.entry("ComplianceControllerWeb#startGeneration", can("inspections", "generate-compliance")),
            Map.entry("ComplianceControllerWeb#readJob", can("inspections", "generate-compliance")),
            Map.entry("ComplianceControllerWeb#readLatestJob", can("inspections", "generate-compliance")),
            Map.entry("ComplianceControllerWeb#regenerate", can("inspections", "generate-compliance")),
            Map.entry("DatasetExportControllerWeb#run", can("inspections", "export-datasets")),
            Map.entry("DatasetExportControllerWeb#list", can("inspections", "export-datasets")),
            Map.entry("DatasetExportControllerWeb#get", can("inspections", "export-datasets")));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(InspectionsGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards).containsExactlyInAnyOrderEntriesOf(GUARD_BY_HANDLER);
    }

    @Test
    void theInheritingTwinsDeclareNoHandlerOfTheirOwn() {
        for (Class<?> twin : List.of(InspectionController.class, NcrController.class, ObservationController.class,
                ChecklistTemplateController.class)) {
            assertThat(handlersOf(twin)).as(twin.getSimpleName()).isEmpty();
        }
    }

    static String can(String area, String action) {
        return "@access.can('" + area + "', '" + action + "')";
    }

    static String signOff(String ncrParameter) {
        return "@inspectionSecurity.maySignOffNcr(" + ncrParameter + ", " + SIGN_OFFS + ")";
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(InspectionControllerWeb.class, ObservationControllerWeb.class,
                        InspectionEventControllerWeb.class, InspectionEventController.class,
                        ReinspectionControllerWeb.class, ReinspectionController.class, NcrControllerWeb.class,
                        ChecklistTemplateControllerWeb.class, ElementTypeControllerWeb.class, TradeControllerWeb.class,
                        ComplianceControllerWeb.class, DatasetExportControllerWeb.class)
                .flatMap(type -> handlersOf(type).stream())
                .toList();
    }

    private static List<Method> handlersOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
