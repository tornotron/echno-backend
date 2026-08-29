package org.tornotron.echno_backend.material.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.indentItem.IndentItemService;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapperImpl;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockTotals;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.material.MaterialService;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Records what the material conversion path costs per row, in database round trips.
 *
 * <p>It used to cost two aggregate reads per material. {@code fillStock} was an
 * {@code @AfterMapping} hook that asked {@link InventoryService} for the quantity and the value on
 * hand keyed on the material id, so the cost followed the row count rather than the request, and
 * nothing at the call site showed it: a service mapping a page in a stream reads as free and was
 * not. Hibernate's {@code default_batch_fetch_size} could not soften it either, because these are
 * explicit repository calls rather than lazy association loads.
 *
 * <p>The stock is now read once for the whole set of materials being mapped and handed to the
 * mapper, which is the shape {@link MaterialMapper#toWithStockDto} always had. The counts below
 * are the same measurements the converter audit on issue #522 quoted, taken again after the
 * change: what was two reads for one material, a hundred for a page of fifty and twenty for a
 * ten-line indent is one read in each case.
 *
 * <p>Reads are counted on {@link CurrentStockRepository} rather than on the service in front of
 * it, so the number really is the number of statements the request issues.
 */
class MaterialMapperCostTest {

    private static final long ORG_ID = 7L;

    private CurrentStockRepository currentStockRepository;
    private InventoryService inventoryService;
    private MaterialMapper materialMapper;
    private IndentItemMapper indentItemMapper;
    private MaterialRepository materialRepository;
    private MaterialService materialService;
    private IndentItemRepository indentItemRepository;
    private IndentItemService indentItemService;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);

        currentStockRepository = mock(CurrentStockRepository.class);
        when(currentStockRepository.sumStockByMaterialIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> new MaterialStockTotals(id, 10.0, new BigDecimal("100.00")))
                    .toList();
        });

        inventoryService = new InventoryService(currentStockRepository,
                mock(InventoryTransactionRepository.class), mock(MaterialRepository.class),
                mock(StorageLocationRepository.class));

        materialMapper = new MaterialMapperImpl();
        // The generated impl delegates createdBy unconditionally, so the collaborator has to exist
        // even when the field is null.
        ReflectionTestUtils.setField(materialMapper, "employeeMapper", mock(EmployeeMapper.class));

        indentItemMapper = new IndentItemMapperImpl();
        ReflectionTestUtils.setField(indentItemMapper, "materialMapper", materialMapper);

        materialRepository = mock(MaterialRepository.class);
        materialService = new MaterialService(materialRepository, inventoryService,
                mock(InventoryTransactionRepository.class), mock(TenantEntityHelper.class),
                mock(EmployeeRepository.class), mock(ProjectRepository.class),
                mock(StorageLocationRepository.class), materialMapper, mock(UserContextService.class));

        indentItemRepository = mock(IndentItemRepository.class);
        indentItemService = new IndentItemService(indentItemRepository, mock(IndentRepository.class),
                mock(MaterialRepository.class), mock(TenantEntityHelper.class), indentItemMapper,
                inventoryService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Material material(long id) {
        Material material = new Material();
        material.setId(id);
        material.setSku("SKU-" + id);
        material.setMaterialName("Material " + id);
        material.setUnit("bags");
        return material;
    }

    private static IndentItem line(long id) {
        IndentItem line = new IndentItem();
        line.setId(id);
        line.setMaterial(material(id));
        line.setRequestedQuantity(5);
        return line;
    }

    @Test
    void readingOneMaterialCostsOneAggregateRead() {
        when(materialRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(material(1L)));

        MaterialDto dto = materialService.getMaterialById(1L);

        assertThat(dto.getCurrentStock()).isEqualTo(10.0);
        assertThat(dto.getStockValue()).isEqualByComparingTo("100.00");

        // Was two: one sum for the quantity and one for the value, both issued from the mapper.
        verify(currentStockRepository, times(1)).sumStockByMaterialIds(any());
        verify(currentStockRepository, never()).sumCurrentQuantityByMaterial(anyLong());
        verify(currentStockRepository, never()).sumStockValueByMaterial(anyLong());
    }

    @Test
    void readingAPageOfFiftyMaterialsCostsOneAggregateRead() {
        List<Material> materials = new ArrayList<>();
        for (long id = 1; id <= 50; id++) {
            materials.add(material(id));
        }
        when(materialRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(materials));

        Page<MaterialDto> page = materialService.getAllMaterials(0, 50);

        assertThat(page.getContent()).hasSize(50);
        assertThat(page.getContent()).allSatisfy(dto -> assertThat(dto.getCurrentStock()).isEqualTo(10.0));

        // Was a hundred: two per row. One grouped read now covers the page, whatever its size.
        verify(currentStockRepository, times(1)).sumStockByMaterialIds(any());
    }

    @Test
    void theCostNoLongerFollowsMaterialDtoIntoAnIndentLine() {
        // IndentItemDto carries a full MaterialDto, so while the material mapper fetched its own
        // stock a ten-line indent paid the per-material cost once per line.
        List<IndentItem> lines = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            lines.add(line(id));
        }
        when(indentItemRepository.findByIndentId(anyLong())).thenReturn(lines);

        List<IndentItemDto> dtos = indentItemService.getIndentItemsByIndentId(99L);

        assertThat(dtos).hasSize(10);
        assertThat(dtos).allSatisfy(dto -> assertThat(dto.getMaterial().getCurrentStock()).isEqualTo(10.0));

        // Was twenty, before the page multiplied it by the number of indents.
        verify(currentStockRepository, times(1)).sumStockByMaterialIds(any());
    }

    @Test
    void theMapperItselfReadsNothing() {
        // The point of the change: the conversion is a conversion again. Handed a lookup, it asks
        // nobody anything, so nesting it inside another DTO cannot multiply a query count.
        MaterialDto dto = materialMapper.toDto(material(1L), MaterialStockLookup.none());
        indentItemMapper.toDto(line(2L), MaterialStockLookup.none());
        materialMapper.toWithStockDto(material(3L), 42.0, new BigDecimal("500.00"));

        assertThat(dto.getCurrentStock()).isEqualTo(0.0);
        assertThat(dto.getStockValue()).isEqualByComparingTo("0");
        verifyNoInteractions(currentStockRepository);
    }
}
