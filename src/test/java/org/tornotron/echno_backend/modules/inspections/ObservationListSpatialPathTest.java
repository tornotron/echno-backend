package org.tornotron.echno_backend.modules.inspections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapper;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The observation list resolves the page's breadcrumbs in one lookup, not one per row (#814). */
@ExtendWith(MockitoExtension.class)
class ObservationListSpatialPathTest {

    @Mock private ObservationRepository observationRepo;
    @Mock private InspectionRepository inspectionRepo;
    @Mock private SpatialNodeService spatialNodeService;
    @Mock private InspectionEventRecorder events;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private UserContextService userContextService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ObservationMapper mapper;
    @Mock private ProjectRepository projectRepository;
    @Mock private AttachmentRepository attachmentRepository;

    @Test
    void aPageOfObservationsAsksForItsBreadcrumbsOnce() {
        UUID zoneA = UUID.randomUUID();
        UUID zoneB = UUID.randomUUID();
        Observation one = new Observation();
        Observation two = new Observation();
        Observation three = new Observation();
        Observation unplaced = new Observation();
        Page<Observation> rows = new PageImpl<>(List.of(one, two, three, unplaced), PageRequest.of(0, 10), 4);
        when(observationRepo.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(rows);
        when(mapper.toDto(one)).thenReturn(dto(zoneA));
        when(mapper.toDto(two)).thenReturn(dto(zoneA));
        when(mapper.toDto(three)).thenReturn(dto(zoneB));
        when(mapper.toDto(unplaced)).thenReturn(dto(null));
        List<SpatialPathSegment> pathA = List.of(new SpatialPathSegment(zoneA, SpatialLevel.ZONE, "ZA", "Zone A"));
        when(spatialNodeService.pathsOf(anyCollection())).thenReturn(Map.of(zoneA, pathA));

        ObservationService service = new ObservationService(observationRepo, inspectionRepo, spatialNodeService, events,
                tenantEntityHelper, userContextService, employeeRepository, mapper, projectRepository, attachmentRepository);
        Page<ObservationDto> page = service.findAll(42L, null, null, null, null, null, null, PageRequest.of(0, 10));

        verify(spatialNodeService).pathsOf(List.of(zoneA, zoneA, zoneB));
        verify(spatialNodeService, never()).pathOf(any());
        assertThat(page.getContent()).extracting(ObservationDto::spatialPath)
                .containsExactly(pathA, pathA, List.of(), List.of());
    }

    private static ObservationDto dto(UUID spatialNodeId) {
        return new ObservationDto(UUID.randomUUID(), 42L, null, spatialNodeId, List.of(), null, null, null, null, null,
                null, null, null, null, null, null, "t", null, null, null, List.of(), null, null, null, null, null,
                null, null, null, null);
    }
}
