package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InsufficientStockException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    private final InventoryTransactionRepository inventoryTransactionRepository;

    public InventoryService(InventoryTransactionRepository inventoryTransactionRepository) {
        this.inventoryTransactionRepository = inventoryTransactionRepository;
    }

    /**
     * Get current stock for a material at a specific project
     */
    @Transactional(readOnly = true)
    public Integer getCurrentStock(Long materialId, Long projectId) {
        List<InventoryTransaction> latestTransactions =
                inventoryTransactionRepository.findLatestTransactionForMaterialAndProject(materialId, projectId);

        if (latestTransactions.isEmpty()) {
            return 0;
        }

        return latestTransactions.getFirst().getClosingStock();
    }

    /**
     * Get aggregate stock for a material across all projects in the organization
     */
    @Transactional(readOnly = true)
    public Integer getAggregateStock(Long materialId) {
        List<Long> projectIds = inventoryTransactionRepository.findDistinctProjectIdsByMaterialId(materialId);
        int totalStock = 0;
        for (Long projectId : projectIds) {
            totalStock += getCurrentStock(materialId, projectId);
        }
        return totalStock;
    }

    /**
     * Get current stock for multiple materials at a specific project in batch
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> getCurrentStockForMaterials(List<Long> materialIds, Long projectId) {
        Map<Long, Integer> stockMap = new HashMap<>();
        for (Long materialId : materialIds) {
            stockMap.put(materialId, getCurrentStock(materialId, projectId));
        }
        return stockMap;
    }

    /**
     * Validate that sufficient stock exists for a single material at a specific project
     * Throws InsufficientStockException if stock is insufficient
     */
    public void validateSufficientStock(Long materialId, Long projectId, Integer requiredQuantity) {
        Integer currentStock = getCurrentStock(materialId, projectId);
        if (currentStock < requiredQuantity) {
            throw new InsufficientStockException(
                String.format("Insufficient stock for material ID %d at project ID %d. Required: %d, Available: %d",
                    materialId, projectId, requiredQuantity, currentStock)
            );
        }
    }

    /**
     * Validate sufficient stock for multiple materials at a specific project
     * Throws InsufficientStockException if any material has insufficient stock
     */
    public void validateSufficientStockForMultipleItems(Map<Long, Integer> requiredQuantities, Long projectId) {
        Map<Long, Integer> currentStock = getCurrentStockForMaterials(
                new ArrayList<>(requiredQuantities.keySet()), projectId);

        List<String> insufficientItems = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : requiredQuantities.entrySet()) {
            Long materialId = entry.getKey();
            Integer required = entry.getValue();
            Integer available = currentStock.getOrDefault(materialId, 0);

            if (available < required) {
                insufficientItems.add(
                    String.format("Material ID %d: Required %d, Available %d",
                        materialId, required, available)
                );
            }
        }

        if (!insufficientItems.isEmpty()) {
            throw new InsufficientStockException(
                "Insufficient stock at project ID " + projectId + " for items: " + String.join("; ", insufficientItems)
            );
        }
    }
}
