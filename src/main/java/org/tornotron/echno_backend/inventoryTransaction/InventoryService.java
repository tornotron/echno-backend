package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * Get current stock for a material by retrieving the latest closing stock
     */
    @Transactional(readOnly = true)
    public Integer getCurrentStock(Long materialId) {
        List<InventoryTransaction> latestTransactions =
                inventoryTransactionRepository.findLatestTransactionForMaterial(materialId);

        if (latestTransactions.isEmpty()) {
            return 0;
        }

        return latestTransactions.getFirst().getClosingStock();
    }

    /**
     * Get current stock for multiple materials in batch
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> getCurrentStockForMaterials(List<Long> materialIds) {
        Map<Long, Integer> stockMap = new HashMap<>();
        for (Long materialId : materialIds) {
            stockMap.put(materialId, getCurrentStock(materialId));
        }
        return stockMap;
    }

    /**
     * Validate that sufficient stock exists for a single material
     * Throws InsufficientStockException if stock is insufficient
     */
    public void validateSufficientStock(Long materialId, Integer requiredQuantity) {
        Integer currentStock = getCurrentStock(materialId);
        if (currentStock < requiredQuantity) {
            throw new org.tornotron.echno_backend.common.exception.InsufficientStockException(
                String.format("Insufficient stock for material ID %d. Required: %d, Available: %d",
                    materialId, requiredQuantity, currentStock)
            );
        }
    }

    /**
     * Validate sufficient stock for multiple materials
     * Throws InsufficientStockException if any material has insufficient stock
     */
    public void validateSufficientStockForMultipleItems(Map<Long, Integer> requiredQuantities) {
        Map<Long, Integer> currentStock = getCurrentStockForMaterials(
                new ArrayList<>(requiredQuantities.keySet()));

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
            throw new org.tornotron.echno_backend.common.exception.InsufficientStockException(
                "Insufficient stock for items: " + String.join("; ", insufficientItems)
            );
        }
    }
}
