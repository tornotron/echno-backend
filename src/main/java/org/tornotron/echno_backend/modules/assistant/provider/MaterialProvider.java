package org.tornotron.echno_backend.modules.assistant.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.MaterialService;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.CostProfile;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;
import org.tornotron.echno_backend.modules.assistant.api.Subject;

/**
 * Answers questions about materials, current stock on site, and reorder levels.
 *
 * <p>Reads the material catalogue through {@link MaterialService} and queries stock levels
 * through {@link InventoryService#getCurrentStock(Long, Long)} for the project.
 */
@Slf4j
@Component
@AssistantModuleEnabled
public class MaterialProvider implements AssistantProvider {

    public static final String ID = "material";
    private static final int PAGE_SIZE = 100;

    private final MaterialService materialService;
    private final InventoryService inventoryService;

    public MaterialProvider(MaterialService materialService, InventoryService inventoryService) {
        this.materialService = materialService;
        this.inventoryService = inventoryService;
    }

    @Override
    public ProviderDescriptor describe() {
        return new ProviderDescriptor(
                ID,
                "Answers questions about materials, stock quantities on site, and reorder levels for a project.",
                List.of(
                        new FieldSpec("materialId", "id", "Unique identifier of the material."),
                        new FieldSpec("materialName", "name", "Descriptive name of the material."),
                        new FieldSpec("sku", "code", "Stock Keeping Unit or item code."),
                        new FieldSpec("unit", "unit", "Unit of measurement (e.g. bags, tonnes, meters)."),
                        new FieldSpec("currentStock", "quantity", "Quantity currently on hand for the project."),
                        new FieldSpec("reorderLevel", "quantity", "Minimum stock threshold before reordering is needed.")
                ),
                Set.of(Subject.MATERIAL),
                false, // needsPeriod
                false, // carriesPersonalData
                new CostProfile(500, 0)
        );
    }

    @Override
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager','store-keeper')")
    public ProviderResult retrieve(Question question, Scope scope) {
        if (scope == null || scope.projectId() == null) {
            return new ProviderResult.Unavailable("A project is required to check material stock.");
        }

        Page<MaterialDto> page;
        try {
            page = materialService.getAllMaterials(0, PAGE_SIZE);
        } catch (Exception e) {
            log.error("Failed to read materials: {}", e.getMessage(), e);
            return new ProviderResult.Unavailable("Material service could not be reached: " + e.getMessage());
        }

        if (page == null || page.isEmpty()) {
            return new ProviderResult.Empty("materials in project " + scope.projectId());
        }

        List<EvidenceUnit> units = new ArrayList<>();

        for (MaterialDto mat : page.getContent()) {
            if (mat == null || mat.getId() == null) {
                continue;
            }

            Double stock;
            try {
                stock = inventoryService.getCurrentStock(mat.getId(), scope.projectId());
            } catch (Exception e) {
                log.warn("Failed to get stock for material {} at project {}: {}", mat.getId(), scope.projectId(), e.getMessage());
                stock = 0.0;
            }
            if (stock == null) {
                stock = 0.0;
            }

            Map<String, Object> values = new HashMap<>();
            values.put("materialId", mat.getId());
            values.put("materialName", mat.getMaterialName() != null ? mat.getMaterialName() : "");
            values.put("sku", mat.getSku() != null ? mat.getSku() : "");
            values.put("unit", mat.getUnit() != null ? mat.getUnit() : "units");
            values.put("currentStock", stock);
            values.put("reorderLevel", mat.getReorderLevel() != null ? mat.getReorderLevel() : 0.0);

            units.add(new EvidenceUnit(
                    "material:" + mat.getId() + ":project-" + scope.projectId(),
                    ID,
                    "material-stock",
                    values,
                    SourceRef.entity("material", Long.toString(mat.getId())),
                    1.0
            ));
        }

        if (units.isEmpty()) {
            return new ProviderResult.Empty("materials matching question in project " + scope.projectId());
        }

        return new ProviderResult.Evidence(units);
    }
}
