package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationListFilter;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.DetectionVisibility;
import org.tornotron.echno_backend.modules.inspections.service.ObservationBatchReviewService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationEvidenceService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.web.InspectionEventControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.ObservationControllerWeb;

/**
 * The default configuration with the real bean behind the real guards: decision A4 is open, so a
 * client's system-admin does not get detections, and only the {@code detection-reviewer} role does.
 */
@WebMvcTest({ObservationControllerWeb.class, InspectionEventControllerWeb.class})
@Import({DetectionVisibility.class, DetectionVisibilityGuardTest.TestSecurityConfig.class})
class DetectionVisibilityGuardTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean(name = "access")
    private AccessGuard access;

    @MockitoBean
    private ObservationRepository observations;

    @MockitoBean
    private ObservationService service;

    @MockitoBean
    private ObservationEvidenceService evidenceService;

    @MockitoBean
    private ObservationBatchReviewService batchReviewService;

    @MockitoBean
    private InspectionEventService eventService;

    // maySee is transactional; the web slice has the advice and no database.
    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void anyCallerMayReadAndReview() {
        when(access.can(eq("inspections"), anyString())).thenReturn(true);
        when(observations.countDetectionScoped(ID)).thenReturn(1L);
    }

    @Test
    void aClientsSystemAdminDoesNotGetDetectionsInTheList() throws Exception {
        holds("system-admin");

        assertThat(listedWithDetections()).isFalse();
    }

    @Test
    void aDetectionReviewerGetsThem() throws Exception {
        holds("detection-reviewer");

        assertThat(listedWithDetections()).isTrue();
    }

    @Test
    void aClientsSystemAdminCannotReachADetectionById() throws Exception {
        holds("system-admin");

        mockMvc.perform(get("/api/v1/inspections/web/observations/{id}", ID).with(jwt())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inspections/web/observations/{id}/evidence", ID).with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/inspections/web/observations/{id}/review", ID).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).findById(any(), eq(false));
    }

    @Test
    void aDetectionReviewerCanReachOneById() throws Exception {
        holds("detection-reviewer");

        mockMvc.perform(get("/api/v1/inspections/web/observations/{id}", ID).with(jwt())).andExpect(status().isOk());
    }

    @Test
    void theEventLogIsToldWhetherTheCallerSeesDetections() throws Exception {
        holds("system-admin");
        mockMvc.perform(get("/api/v1/inspections/web/events").with(jwt())).andExpect(status().isOk());
        verify(eventService).search(any(), any(), any(), any(), any(), any(), eq(false), any(Pageable.class));

        holds("detection-reviewer");
        mockMvc.perform(get("/api/v1/inspections/web/{id}/events", ID).with(jwt())).andExpect(status().isOk());
        verify(eventService).inspectionTimeline(eq(ID), eq(true), any(Pageable.class));
    }

    @Test
    void aBatchIsToldWhetherTheCallerSeesDetections() throws Exception {
        holds("system-admin");

        mockMvc.perform(post("/api/v1/inspections/web/observations/review-batch").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"observationIds\":[\"" + ID + "\"]}"))
                .andExpect(status().isOk());

        verify(batchReviewService).review(any(), eq(false));
    }

    private boolean listedWithDetections() throws Exception {
        when(service.findAll(any(ObservationListFilter.class), any(Pageable.class))).thenReturn(Page.empty());
        mockMvc.perform(get("/api/v1/inspections/web/observations").with(jwt())).andExpect(status().isOk());
        ArgumentCaptor<ObservationListFilter> filter = ArgumentCaptor.forClass(ObservationListFilter.class);
        verify(service).findAll(filter.capture(), any(Pageable.class));
        return filter.getValue().includeDetections();
    }

    /** The caller holds this starting role; only the detection reviewer's gives inspections:read-detections. */
    private void holds(String role) {
        when(access.can("inspections", "read-detections")).thenReturn(role.equals("detection-reviewer"));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }
}
