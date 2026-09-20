package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.pdf.InspectionReportPdfService;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistIncompleteException;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistIncompleteException.UnansweredCheckItem;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionEvidenceService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.web.InspectionControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.InspectionsExceptionHandler;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the submit endpoint answers when the gate refuses: a 422 whose body lists
 * the unanswered check points, rather than the 500 the global catch-all would
 * make of an exception it does not know.
 *
 * <p>Standalone MockMvc over the real controller and the module's advice with the
 * service mocked: the assertion is about the HTTP shape, and a web slice would
 * cost the test JVM another cached context.
 */
class ChecklistIncompleteResponseTest {

    private static final UUID INSPECTION_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID ITEM_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private final InspectionService service = mock(InspectionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InspectionControllerWeb controller = new InspectionControllerWeb(service,
                mock(DefectAnnotationService.class), mock(InspectionEvidenceService.class),
                mock(InspectionReportPdfService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InspectionsExceptionHandler())
                .build();
    }

    @Test
    void aRefusedSubmissionIsA422ThatListsTheUnansweredCheckPoints() throws Exception {
        when(service.update(eq(INSPECTION_ID), any())).thenThrow(new ChecklistIncompleteException(
                INSPECTION_ID, "INSP-2026-0007", List.of(
                        new UnansweredCheckItem(1, ITEM_ID, "Reinforcement", "Cover blocks"),
                        new UnansweredCheckItem(4, null, "Formwork", "Shutter alignment"))));

        mockMvc.perform(put("/api/v1/inspections/web/{id}", INSPECTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().findAndRegisterModules().writeValueAsString(submit())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Checklist Incomplete"))
                .andExpect(jsonPath("$.detail").value(containsString("2 check points are still unanswered")))
                .andExpect(jsonPath("$.message").value(containsString("Reinforcement / Cover blocks")))
                .andExpect(jsonPath("$.inspectionId").value(INSPECTION_ID.toString()))
                .andExpect(jsonPath("$.inspectionNumber").value("INSP-2026-0007"))
                .andExpect(jsonPath("$.unansweredCount").value(2))
                .andExpect(jsonPath("$.unansweredItems", hasSize(2)))
                .andExpect(jsonPath("$.unansweredItems[0].index").value(1))
                .andExpect(jsonPath("$.unansweredItems[0].id").value(ITEM_ID.toString()))
                .andExpect(jsonPath("$.unansweredItems[0].category").value("Reinforcement"))
                .andExpect(jsonPath("$.unansweredItems[0].checkPoint").value("Cover blocks"))
                .andExpect(jsonPath("$.unansweredItems[1].index").value(4))
                .andExpect(jsonPath("$.unansweredItems[1].id").value(nullValue()))
                .andExpect(jsonPath("$.unansweredItems[1].checkPoint").value("Shutter alignment"));
    }

    private static UpdateInspectionRequest submit() {
        return new UpdateInspectionRequest(
                "Wall check", InspectionType.QUALITY, null, "plastering", null,
                InspectionStatus.COMPLETED, null, 42L, "Block A", null, null,
                LocalDate.of(2026, 9, 20), null, null, null, null, 100L, null, null,
                null, null, null, null, null, null);
    }
}
