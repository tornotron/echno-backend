package org.tornotron.echno_backend.material.lowstock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.material.dto.LowStockMaterialDto;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How the low-stock read picks its scope and what it refuses.
 *
 * <p>The refusals are the substance here. An empty page from this endpoint reads as "nothing has
 * run out", which is the most reassuring answer it can give, so every way of asking a question
 * about somewhere that does not exist has to be an error rather than an empty page. A typo in a
 * project id returning "all clear" is the kind of wrong answer nobody checks.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LowStockServiceTest {

    @Mock private LowStockRepository lowStockRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private StorageLocationRepository storageLocationRepository;

    private LowStockService service;

    private static final Pageable PAGE = PageRequest.of(0, 10);

    @BeforeEach
    void setUp() {
        service = new LowStockService(lowStockRepository, projectRepository, storageLocationRepository);
        TenantContext.setCurrentOrgId(1L);
        when(lowStockRepository.findLowStockForOrganization(any(), any())).thenReturn(Page.empty());
        when(lowStockRepository.findLowStockForProject(any(), any(), any())).thenReturn(Page.empty());
        when(lowStockRepository.findLowStockAtStorageLocation(any(), any(), any(), any()))
                .thenReturn(Page.empty());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("no ids reads the whole organization")
    void noIds_readsTheOrganization() {
        service.findLowStock(null, null, PAGE);

        verify(lowStockRepository).findLowStockForOrganization(eq(1L), any());
        verify(lowStockRepository, never()).findLowStockForProject(any(), any(), any());
    }

    @Test
    @DisplayName("a project alone reads that project")
    void projectOnly_readsTheProject() {
        when(projectRepository.existsByIdAndOrganization_Id(5L, 1L)).thenReturn(true);

        service.findLowStock(5L, null, PAGE);

        verify(lowStockRepository).findLowStockForProject(eq(1L), eq(5L), any());
    }

    @Test
    @DisplayName("a storage location without a project is refused")
    void locationWithoutProject_isRefused() {
        assertThatThrownBy(() -> service.findLowStock(null, 2L, PAGE))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("projectId is required");

        verifyNoInteractions(lowStockRepository);
    }

    @Test
    @DisplayName("an unknown project is not found rather than an empty page")
    void unknownProject_isNotFound() {
        when(projectRepository.existsByIdAndOrganization_Id(99L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.findLowStock(99L, null, PAGE))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project with ID 99");

        verifyNoInteractions(lowStockRepository);
    }

    @Test
    @DisplayName("an unknown storage location is not found rather than an empty page")
    void unknownLocation_isNotFound() {
        when(projectRepository.existsByIdAndOrganization_Id(5L, 1L)).thenReturn(true);
        when(storageLocationRepository.findByIdAndOrganization_Id(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findLowStock(5L, 99L, PAGE))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Storage location with ID 99");

        verifyNoInteractions(lowStockRepository);
    }

    @Test
    @DisplayName("a storage location belonging to another project is refused, not answered with all clear")
    void locationOnAnotherProject_isRefused() {
        Project otherProject = new Project();
        otherProject.setId(7L);
        StorageLocation location = new StorageLocation();
        location.setId(2L);
        location.setProject(otherProject);

        when(projectRepository.existsByIdAndOrganization_Id(5L, 1L)).thenReturn(true);
        when(storageLocationRepository.findByIdAndOrganization_Id(2L, 1L)).thenReturn(Optional.of(location));

        // That pairing can hold no stock at all, so the query over it returns nothing. Answering
        // "nothing has run out" to a question about a location that cannot hold anything is worse
        // than answering nothing.
        assertThatThrownBy(() -> service.findLowStock(5L, 2L, PAGE))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("belongs to project");

        verifyNoInteractions(lowStockRepository);
    }

    @Test
    @DisplayName("an organisation-level location is readable from any project")
    void organisationLevelLocation_isAccepted() {
        StorageLocation central = new StorageLocation();
        central.setId(2L);   // no project: a central yard every project draws from

        when(projectRepository.existsByIdAndOrganization_Id(5L, 1L)).thenReturn(true);
        when(storageLocationRepository.findByIdAndOrganization_Id(2L, 1L)).thenReturn(Optional.of(central));

        service.findLowStock(5L, 2L, PAGE);

        verify(lowStockRepository).findLowStockAtStorageLocation(eq(1L), eq(5L), eq(2L), any());
    }

    @Test
    @DisplayName("the shortfall is the gap to the level, and never negative")
    void shortfall_isTheGapAndNeverNegative() {
        LowStockRow short_ = new LowStockRow(1L, "TMT-1", "TMT bars", "kg", 10000.0, 11000.0, 3501.0);
        LowStockRow exactly = new LowStockRow(2L, "PLY-1", "Plywood", "sheets", 500.0, 500.0, 500.0);
        when(lowStockRepository.findLowStockForOrganization(any(), any()))
                .thenReturn(new PageImpl<>(List.of(short_, exactly)));

        List<LowStockMaterialDto> rows = service.findLowStock(null, null, PAGE).getContent();

        assertThat(rows).extracting(LowStockMaterialDto::getShortfall)
                .containsExactly(7499.0, 0.0);
        assertThat(rows.get(0).getMoq()).isEqualTo(10000.0);
        assertThat(rows.get(0).getProjectId()).isNull();
        assertThat(rows.get(0).getStorageLocationId()).isNull();
    }

    @Test
    @DisplayName("the scope the caller asked about comes back on every row")
    void scopeIsEchoedOnEveryRow() {
        StorageLocation central = new StorageLocation();
        central.setId(2L);
        when(projectRepository.existsByIdAndOrganization_Id(5L, 1L)).thenReturn(true);
        when(storageLocationRepository.findByIdAndOrganization_Id(2L, 1L)).thenReturn(Optional.of(central));
        when(lowStockRepository.findLowStockAtStorageLocation(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new LowStockRow(1L, "CEM-1", "Cement", "bags", 100.0, 200.0, 150.0))));

        LowStockMaterialDto row = service.findLowStock(5L, 2L, PAGE).getContent().get(0);

        assertThat(row.getProjectId()).isEqualTo(5L);
        assertThat(row.getStorageLocationId()).isEqualTo(2L);
        assertThat(row.getReorderLevel()).isEqualTo(200.0);
        assertThat(row.getShortfall()).isEqualTo(50.0);
    }
}
