package org.tornotron.echno_backend.material.summary;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.material.dto.MaterialStockSummaryDto;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which set of figures each scope answers with, and what an unknown project gets.
 *
 * <p>The arithmetic is checked against a real database in {@link MaterialStockSummaryIT}. What is
 * checked here is the choice between the two scopes, because the two count different things on
 * purpose and picking the wrong one is a mistake that produces a plausible number rather than an
 * error: an organization count on a project view says the site carries the whole catalogue.
 */
@ExtendWith(MockitoExtension.class)
class MaterialStockSummaryServiceTest {

    private static final long ORG_ID = 7L;
    private static final long PROJECT_ID = 42L;

    @Mock
    private MaterialRepository materialRepository;

    @Mock
    private CurrentStockRepository currentStockRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private MaterialStockSummaryService service;

    @BeforeEach
    void scopeToATenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("organization scope counts the catalogue and never asks about a project")
    void organizationScope_countsTheCatalogue() {
        when(materialRepository.countForOrganization(ORG_ID)).thenReturn(743L);
        when(materialRepository.countDistinctUnitsForOrganization(ORG_ID)).thenReturn(9L);
        when(currentStockRepository.sumStockValueForOrganization(ORG_ID))
                .thenReturn(new BigDecimal("50148300.00"));
        when(currentStockRepository.countUnvaluedHoldingsForOrganization(ORG_ID)).thenReturn(0L);

        MaterialStockSummaryDto summary = service.summarize(null);

        assertThat(summary.getProjectId()).isNull();
        assertThat(summary.getMaterialCount()).isEqualTo(743);
        assertThat(summary.getDistinctUnits()).isEqualTo(9);
        assertThat(summary.getTotalStockValue()).isEqualByComparingTo("50148300.00");
        assertThat(summary.getUnvaluedHoldingCount()).isZero();
        verifyNoInteractions(projectRepository);
    }

    @Test
    @DisplayName("project scope counts what the project carries, not the catalogue")
    void projectScope_countsWhatTheProjectCarries() {
        when(projectRepository.existsByIdAndOrganization_Id(PROJECT_ID, ORG_ID)).thenReturn(true);
        when(currentStockRepository.countDistinctMaterialsForProject(ORG_ID, PROJECT_ID)).thenReturn(12L);
        when(currentStockRepository.countDistinctUnitsForProject(ORG_ID, PROJECT_ID)).thenReturn(4L);
        when(currentStockRepository.sumStockValueForProject(ORG_ID, PROJECT_ID))
                .thenReturn(new BigDecimal("620000.00"));
        when(currentStockRepository.countUnvaluedHoldingsForProject(ORG_ID, PROJECT_ID)).thenReturn(2L);

        MaterialStockSummaryDto summary = service.summarize(PROJECT_ID);

        assertThat(summary.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(summary.getMaterialCount()).isEqualTo(12);
        assertThat(summary.getDistinctUnits()).isEqualTo(4);
        assertThat(summary.getTotalStockValue()).isEqualByComparingTo("620000.00");
        assertThat(summary.getUnvaluedHoldingCount()).isEqualTo(2);
        // The catalogue counts describe the organization. On a project view they would say the
        // site carries every material the company has ever listed.
        verify(materialRepository, never()).countForOrganization(ORG_ID);
        verify(materialRepository, never()).countDistinctUnitsForOrganization(ORG_ID);
    }

    @Test
    @DisplayName("a project that is not this tenant's is refused rather than summarised as zero")
    void unknownProject_isRefused() {
        when(projectRepository.existsByIdAndOrganization_Id(PROJECT_ID, ORG_ID)).thenReturn(false);

        // Zeroes are an answer, and "this site holds nothing worth anything" must never be the
        // answer to a question asked of a project that is not there. The same reason the
        // low-stock read gives for its 404.
        assertThatThrownBy(() -> service.summarize(PROJECT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(String.valueOf(PROJECT_ID));

        verifyNoInteractions(currentStockRepository);
    }
}
