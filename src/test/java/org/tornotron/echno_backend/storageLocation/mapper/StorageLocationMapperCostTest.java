package org.tornotron.echno_backend.storageLocation.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.StorageLocationItemCount;
import org.tornotron.echno_backend.inventoryTransaction.StorageLocationItemCounts;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationService;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;

/**
 * Records what the storage-location conversion path costs per row.
 *
 * <p>It used to cost one {@code COUNT DISTINCT} per location, issued from an
 * {@code @AfterMapping} hook that held the stock repository, on each of the four listing paths
 * through {@code StorageLocationService}. The count is now read for the whole page in one grouped
 * query and passed to the mapper.
 *
 * <p>Reads are counted on {@link CurrentStockRepository} so the number is the number of statements
 * the request issues.
 */
class StorageLocationMapperCostTest {

    private static final long ORG_ID = 7L;

    private CurrentStockRepository currentStockRepository;
    private StorageLocationMapper storageLocationMapper;
    private StorageLocationRepository storageLocationRepository;
    private StorageLocationService storageLocationService;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);

        currentStockRepository = mock(CurrentStockRepository.class);
        when(currentStockRepository.countDistinctMaterialsByStorageLocationIds(any(), anyLong()))
                .thenAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(0);
                    return ids.stream().map(id -> new StorageLocationItemCount(id, 3L)).toList();
                });

        InventoryService inventoryService = new InventoryService(currentStockRepository,
                mock(InventoryTransactionRepository.class), mock(MaterialRepository.class),
                mock(StorageLocationRepository.class));

        storageLocationMapper = new StorageLocationMapperImpl();
        storageLocationRepository = mock(StorageLocationRepository.class);
        storageLocationService = new StorageLocationService(storageLocationRepository,
                mock(ProjectRepository.class), mock(TenantEntityHelper.class), storageLocationMapper,
                inventoryService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static StorageLocation location(long id) {
        StorageLocation location = new StorageLocation();
        location.setId(id);
        location.setLocationName("Store " + id);
        return location;
    }

    @Test
    void readingAPageOfFiftyLocationsCostsOneCount() {
        List<StorageLocation> locations = new ArrayList<>();
        for (long id = 1; id <= 50; id++) {
            locations.add(location(id));
        }
        when(storageLocationRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(locations));

        Page<StorageLocationDto> page = storageLocationService.getAllStorageLocations(0, 50);

        assertThat(page.getContent()).hasSize(50);
        assertThat(page.getContent()).allSatisfy(dto -> assertThat(dto.getStorageItemsCount()).isEqualTo(3L));

        // Was fifty, one per row.
        verify(currentStockRepository, times(1)).countDistinctMaterialsByStorageLocationIds(any(), anyLong());
    }

    @Test
    void listingByProjectCostsOneCount() {
        when(storageLocationRepository.findByProjectId(anyLong()))
                .thenReturn(List.of(location(1L), location(2L), location(3L)));

        List<StorageLocationDto> dtos = storageLocationService.getStorageLocationsByProject(12L);

        assertThat(dtos).hasSize(3);
        // Was three.
        verify(currentStockRepository, times(1)).countDistinctMaterialsByStorageLocationIds(any(), anyLong());
    }

    @Test
    void theMapperItselfReadsNothing() {
        StorageLocationDto dto = storageLocationMapper.toDto(location(1L), StorageLocationItemCounts.none());

        assertThat(dto.getStorageItemsCount()).isZero();
        verifyNoInteractions(currentStockRepository);
    }
}
