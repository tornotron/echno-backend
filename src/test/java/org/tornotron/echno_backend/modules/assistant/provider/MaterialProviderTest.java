package org.tornotron.echno_backend.modules.assistant.provider;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.MaterialService;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.Subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialProviderTest {

    private final MaterialService materialService = mock(MaterialService.class);
    private final InventoryService inventoryService = mock(InventoryService.class);
    private final MaterialProvider provider = new MaterialProvider(materialService, inventoryService);

    @Test
    @DisplayName("Descriptor accurately declares subjects, capabilities, and fields")
    void describesItself() {
        ProviderDescriptor descriptor = provider.describe();

        assertThat(descriptor.id()).isEqualTo("material");
        assertThat(descriptor.subjects()).containsExactly(Subject.MATERIAL);
        assertThat(descriptor.needsPeriod()).isFalse();
        assertThat(descriptor.carriesPersonalData()).isFalse();
        assertThat(descriptor.fields()).extracting("name")
                .containsExactly("materialId", "materialName", "sku", "unit",
                        "currentStock", "reorderLevel");
    }

    @Test
    @DisplayName("Retrieves project stock and builds evidence units")
    void retrievesProjectStockAndBuildsEvidence() {
        Long projectId = 101L;
        Question question = new Question("How much cement and steel is on site?");
        Scope scope = new Scope(projectId, LocalDate.now(), LocalDate.now(), null);

        MaterialDto cement = new MaterialDto();
        cement.setId(1L);
        cement.setMaterialName("OPC 53 Cement");
        cement.setSku("CEM-001");
        cement.setUnit("BAGS");
        cement.setReorderLevel(50.0);

        MaterialDto steel = new MaterialDto();
        steel.setId(2L);
        steel.setMaterialName("12mm TMT Steel Rebars");
        steel.setSku("STL-002");
        steel.setUnit("TONNES");
        steel.setReorderLevel(10.0);

        when(materialService.getAllMaterials(anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(cement, steel)));
        when(inventoryService.getCurrentStock(eq(1L), eq(projectId))).thenReturn(25.0);
        when(inventoryService.getCurrentStock(eq(2L), eq(projectId))).thenReturn(15.0);

        ProviderResult result = provider.retrieve(question, scope);

        assertThat(result).isInstanceOf(ProviderResult.Evidence.class);
        List<EvidenceUnit> units = ((ProviderResult.Evidence) result).units();
        assertThat(units).hasSize(2);

        EvidenceUnit cementUnit = units.get(0);
        assertThat(cementUnit.providerId()).isEqualTo("material");
        assertThat(cementUnit.source().type()).isEqualTo("material");
        assertThat(cementUnit.source().id()).isEqualTo("1");
        assertThat(cementUnit.values()).containsEntry("materialName", "OPC 53 Cement")
                .containsEntry("currentStock", 25.0)
                .containsEntry("reorderLevel", 50.0);

        EvidenceUnit steelUnit = units.get(1);
        assertThat(steelUnit.values()).containsEntry("materialName", "12mm TMT Steel Rebars")
                .containsEntry("currentStock", 15.0)
                .containsEntry("reorderLevel", 10.0);
    }

    @Test
    @DisplayName("Returns Empty when catalogue has no materials")
    void returnsEmptyWhenNoMaterials() {
        when(materialService.getAllMaterials(anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        ProviderResult result = provider.retrieve(new Question("Any materials?"),
                new Scope(101L, LocalDate.now(), LocalDate.now(), null));

        assertThat(result).isInstanceOf(ProviderResult.Empty.class);
        assertThat(((ProviderResult.Empty) result).lookedFor()).contains("materials in project 101");
    }
}
