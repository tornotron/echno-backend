package org.tornotron.echno_backend.material.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapperImpl;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.dto.MaterialDto;

/**
 * Records what {@link MaterialMapper} costs per row, in database round trips.
 *
 * <p>This is a cost record, not a statement of desired behaviour. {@code fillStock} is an
 * {@code @AfterMapping} hook that asks {@link InventoryService} for two aggregates keyed on the
 * material id, so the cost is per mapped material rather than per request, and it is invisible at
 * the call site: a service that maps a page of materials in a stream reads as free and is not.
 * Hibernate's {@code default_batch_fetch_size} does not help here, because these are explicit
 * repository calls and not lazy association loads.
 *
 * <p>The numbers asserted below are the ones quoted in the converter audit on issue #522. When the
 * aggregates move to a single batched lookup per page, these assertions are what change, and the
 * change is then visible in the diff rather than only in a query log.
 */
class MaterialMapperCostTest {

    private InventoryService inventoryService;
    private MaterialMapper materialMapper;

    @BeforeEach
    void setUp() {
        inventoryService = mock(InventoryService.class);
        when(inventoryService.getAggregateStock(anyLong())).thenReturn(10.0);
        when(inventoryService.getAggregateStockValue(anyLong())).thenReturn(new BigDecimal("100.00"));

        materialMapper = new MaterialMapperImpl();
        ReflectionTestUtils.setField(materialMapper, "inventoryService", inventoryService);
        // The generated impl delegates createdBy unconditionally, so the collaborator has to exist
        // even when the field is null.
        ReflectionTestUtils.setField(materialMapper, "employeeMapper", mock(EmployeeMapper.class));
    }

    private static Material material(long id) {
        Material material = new Material();
        material.setId(id);
        material.setSku("SKU-" + id);
        material.setMaterialName("Material " + id);
        material.setUnit("bags");
        return material;
    }

    @Test
    void mappingOneMaterialCostsTwoAggregateReads() {
        MaterialDto dto = materialMapper.toDto(material(1L));

        assertThat(dto.getCurrentStock()).isEqualTo(10.0);
        assertThat(dto.getStockValue()).isEqualByComparingTo("100.00");

        verify(inventoryService, times(1)).getAggregateStock(1L);
        verify(inventoryService, times(1)).getAggregateStockValue(1L);
    }

    @Test
    void mappingAPageOfFiftyMaterialsCostsOneHundredAggregateReads() {
        List<MaterialDto> dtos = new ArrayList<>();
        for (long id = 1; id <= 50; id++) {
            dtos.add(materialMapper.toDto(material(id)));
        }

        assertThat(dtos).hasSize(50);
        // One page query, then two aggregate reads for every row on the page.
        verify(inventoryService, times(50)).getAggregateStock(anyLong());
        verify(inventoryService, times(50)).getAggregateStockValue(anyLong());
    }

    @Test
    void theCostFollowsMaterialDtoIntoAnIndentLine() {
        // IndentItemMapper delegates the material to MaterialMapper, and IndentMapper delegates the
        // lines to IndentItemMapper, so an indent pays the per-material cost once per line item.
        IndentItemMapper indentItemMapper = new IndentItemMapperImpl();
        ReflectionTestUtils.setField(indentItemMapper, "materialMapper", materialMapper);

        for (long id = 1; id <= 10; id++) {
            IndentItem line = new IndentItem();
            line.setId(id);
            line.setMaterial(material(id));
            line.setRequestedQuantity(5);
            indentItemMapper.toDto(line);
        }

        // Ten lines on one indent, before the page multiplies it by the number of indents.
        verify(inventoryService, times(10)).getAggregateStock(anyLong());
        verify(inventoryService, times(10)).getAggregateStockValue(anyLong());
    }

    @Test
    void theWithStockVariantTakesTheAggregatesFromTheCaller() {
        // The batched shape already exists on this mapper and is used by three call sites in
        // MaterialService. It costs nothing per row, which is the target for toDto as well.
        materialMapper.toWithStockDto(material(1L), 42.0, new BigDecimal("500.00"));

        verify(inventoryService, times(0)).getAggregateStock(anyLong());
        verify(inventoryService, times(0)).getAggregateStockValue(anyLong());
    }
}
