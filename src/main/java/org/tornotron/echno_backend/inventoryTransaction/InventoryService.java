package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InsufficientStockException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryMaterialStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.LocationStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialLocationStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.StockDto;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class InventoryService {

    private final CurrentStockRepository currentStockRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final MaterialRepository materialRepository;
    private final StorageLocationRepository storageLocationRepository;

    public InventoryService(CurrentStockRepository currentStockRepository,
                            InventoryTransactionRepository inventoryTransactionRepository,
                            MaterialRepository materialRepository, StorageLocationRepository storageLocationRepository) {
        this.currentStockRepository = currentStockRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.materialRepository = materialRepository;
        this.storageLocationRepository = storageLocationRepository;
    }

    /**
     * Get current stock for a material at a specific project (across all storage locations).
     * Reads from the CurrentStock table by summing all CurrentStock records for the material+project.
     */
    @Transactional(readOnly = true)
    public Double getCurrentStock(Long materialId, Long projectId) {
        return currentStockRepository.sumCurrentQuantityByMaterialAndProject(materialId, projectId);
    }

    /**
     * Get aggregate stock for a material across all projects in the organization.
     */
    @Transactional(readOnly = true)
    public Double getAggregateStock(Long materialId) {
        return currentStockRepository.sumCurrentQuantityByMaterial(materialId);
    }

    /**
     * Get current stock for a material at a specific storage location within a project.
     */
    @Transactional(readOnly = true)
    public Double getStockAtLocation(Long materialId, Long projectId, Long storageLocationId) {
        return currentStockRepository
                .findByMaterialIdAndProjectIdAndStorageLocationId(materialId, projectId, storageLocationId)
                .map(CurrentStock::getCurrentQuantity)
                .orElse(0.0);
    }

    /**
     * Get current stock for multiple materials at a specific project in batch.
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> getCurrentStockForMaterials(List<Long> materialIds, Long projectId) {
        Map<Long, Double> stockMap = new HashMap<>();
        for (Long materialId : materialIds) {
            stockMap.put(materialId, getCurrentStock(materialId, projectId));
        }
        return stockMap;
    }

    /**
     * Validate that sufficient stock exists for a single material at a specific project.
     */
    public void validateSufficientStock(Long materialId, Long projectId, Double requiredQuantity) {
        Double currentStock = getCurrentStock(materialId, projectId);
        if (currentStock < requiredQuantity) {
            throw new InsufficientStockException(
                String.format("Insufficient stock for material ID %d at project ID %d. Required: %.2f, Available: %.2f",
                    materialId, projectId, requiredQuantity, currentStock)
            );
        }
    }

    /**
     * Validate that sufficient stock exists for a single material at a specific storage location.
     */
    public void validateSufficientStockAtLocation(Long materialId, Long projectId, Long storageLocationId, Double requiredQuantity) {
        Double currentStock = getStockAtLocation(materialId, projectId, storageLocationId);
        if (currentStock < requiredQuantity) {
            throw new InsufficientStockException(
                String.format("Insufficient stock for material ID %d at project ID %d, storage location ID %d. Required: %.2f, Available: %.2f",
                    materialId, projectId, storageLocationId, requiredQuantity, currentStock)
            );
        }
    }

    /**
     * Validate sufficient stock for multiple materials at a specific storage location.
     */
    public void validateSufficientStockForMultipleItemsAtLocation(Map<Long, Double> requiredQuantities, Long projectId, Long storageLocationId) {
        List<String> insufficientItems = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : requiredQuantities.entrySet()) {
            Long materialId = entry.getKey();
            Double required = entry.getValue();
            Double available = getStockAtLocation(materialId, projectId, storageLocationId);

            if (available < required) {
                insufficientItems.add(
                    String.format("Material ID %d: Required %.2f, Available %.2f",
                        materialId, required, available)
                );
            }
        }

        if (!insufficientItems.isEmpty()) {
            throw new InsufficientStockException(
                "Insufficient stock at project ID " + projectId + ", storage location ID " + storageLocationId +
                " for items: " + String.join("; ", insufficientItems)
            );
        }
    }

    /**
     * Get stock for all materials at a specific storage location.
     * Reads from CurrentStock table.
     */
    @Transactional(readOnly = true)
    public InventoryMaterialStockDto getStockByStorageLocation(Long storageLocationId) {
        StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(storageLocationId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location not found with id: " + storageLocationId));
        List<CurrentStock> stockRecords = currentStockRepository.findByStorageLocationIdAndOrganization_Id(storageLocationId, TenantContext.getCurrentOrgId());

        List<StockDto> stockDtos = new ArrayList<>();
        double totalStock = 0.0;
        BigDecimal totalStockValue = BigDecimal.ZERO;

        for (CurrentStock cs : stockRecords) {
            StockDto dto = new StockDto();
            dto.setMaterialId(cs.getMaterial().getId());
            dto.setMaterialName(cs.getMaterial().getMaterialName());
            dto.setUnit(cs.getMaterial().getUnit());
            dto.setStock(cs.getCurrentQuantity());
            dto.setStockValue(cs.getStockValue());
            stockDtos.add(dto);
            totalStock += cs.getCurrentQuantity();
            totalStockValue = totalStockValue.add(cs.getStockValue());
        }

        InventoryMaterialStockDto result = new InventoryMaterialStockDto();
        result.setStorageLocationId(storageLocation.getId());
        result.setStorageLocationName(storageLocation.getLocationName());
        result.setProjectId(storageLocation.getProject().getId());
        result.setMaterialStock(stockDtos);
        result.setTotalStock(totalStock);
        result.setTotalStockValue(totalStockValue);
        return result;
    }

    /**
     * Get stock for a specific material across all storage locations.
     * Returns per-location breakdown with totals.
     */
    @Transactional(readOnly = true)
    public MaterialLocationStockDto getStockByMaterial(Long materialId) {
        Material material = materialRepository.findByIdAndOrganization_Id(materialId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Material not found with id: " + materialId));

        List<CurrentStock> stockRecords = currentStockRepository.findByMaterialIdAndOrganization_Id(
                materialId, TenantContext.getCurrentOrgId());

        List<LocationStockDto> locationStockDtos = new ArrayList<>();
        double totalStock = 0.0;
        BigDecimal totalStockValue = BigDecimal.ZERO;

        for (CurrentStock cs : stockRecords) {
            LocationStockDto dto = new LocationStockDto();
            if (cs.getStorageLocation() != null) {
                dto.setStorageLocationId(cs.getStorageLocation().getId());
                dto.setStorageLocationName(cs.getStorageLocation().getLocationName());
            }
            dto.setProjectId(cs.getProject().getId());
            dto.setProjectName(cs.getProject().getProjectName());
            dto.setStock(cs.getCurrentQuantity());
            dto.setStockValue(cs.getStockValue());
            locationStockDtos.add(dto);
            totalStock += cs.getCurrentQuantity();
            totalStockValue = totalStockValue.add(cs.getStockValue());
        }

        MaterialLocationStockDto result = new MaterialLocationStockDto();
        result.setMaterialId(material.getId());
        result.setMaterialName(material.getMaterialName());
        result.setLocationStock(locationStockDtos);
        result.setTotalStock(totalStock);
        result.setTotalStockValue(totalStockValue);
        return result;
    }

    /**
     * Validate sufficient stock for multiple materials at a specific project.
     */
    public void validateSufficientStockForMultipleItems(Map<Long, Double> requiredQuantities, Long projectId) {
        Map<Long, Double> currentStock = getCurrentStockForMaterials(
                new ArrayList<>(requiredQuantities.keySet()), projectId);

        List<String> insufficientItems = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : requiredQuantities.entrySet()) {
            Long materialId = entry.getKey();
            Double required = entry.getValue();
            Double available = currentStock.getOrDefault(materialId, 0.0);

            if (available < required) {
                insufficientItems.add(
                    String.format("Material ID %d: Required %.2f, Available %.2f",
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

    /**
     * Update the CurrentStock record for a given material, project, and storage location.
     * Creates the record if it doesn't exist (upsert).
     * This must be called within the same transaction as the InventoryTransaction save.
     *
     * @param unitCost For inbound (positive qty): the cost per unit. For outbound (negative qty): pass null to use weighted average cost.
     */
    @Transactional
    public CurrentStock updateCurrentStock(Material material, Project project, StorageLocation storageLocation,
                                           Organization organization, Double quantityChanged, BigDecimal unitCost) {
        CurrentStock stock;
        if (storageLocation != null) {
            stock = currentStockRepository
                    .lockByMaterialProjectAndLocation(
                            material.getId(), project.getId(), storageLocation.getId())
                    .orElseGet(() -> {
                        CurrentStock newStock = new CurrentStock();
                        newStock.setMaterial(material);
                        newStock.setProject(project);
                        newStock.setStorageLocation(storageLocation);
                        newStock.setOrganization(organization);
                        newStock.setCurrentQuantity(0.0);
                        newStock.setStockValue(BigDecimal.ZERO);
                        return newStock;
                    });
        } else {
            stock = currentStockRepository
                    .lockByMaterialProjectAndNoLocation(material.getId(), project.getId())
                    .orElseGet(() -> {
                        CurrentStock newStock = new CurrentStock();
                        newStock.setMaterial(material);
                        newStock.setProject(project);
                        newStock.setOrganization(organization);
                        newStock.setCurrentQuantity(0.0);
                        newStock.setStockValue(BigDecimal.ZERO);
                        return newStock;
                    });
        }

        // Update stock value
        if (quantityChanged > 0 && unitCost != null) {
            // Inbound: add incoming value
            BigDecimal incomingValue = unitCost.multiply(BigDecimal.valueOf(quantityChanged));
            stock.setStockValue(stock.getStockValue().add(incomingValue));
        } else if (quantityChanged < 0) {
            // Outbound: reduce by weighted average cost
            if (stock.getCurrentQuantity() > 0 && stock.getStockValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal avgCost = stock.getStockValue()
                        .divide(BigDecimal.valueOf(stock.getCurrentQuantity()), 2, RoundingMode.HALF_UP);
                BigDecimal valueReduced = avgCost.multiply(BigDecimal.valueOf(Math.abs(quantityChanged)));
                stock.setStockValue(stock.getStockValue().subtract(valueReduced));
            }
        }

        stock.setCurrentQuantity(stock.getCurrentQuantity() + quantityChanged);
        return currentStockRepository.save(stock);
    }

    /**
     * Get the weighted average cost for a material at a specific CurrentStock record.
     */
    public BigDecimal getAverageCost(Long materialId, Long projectId, Long storageLocationId) {
        CurrentStock stock;
        if (storageLocationId != null) {
            stock = currentStockRepository
                    .findByMaterialIdAndProjectIdAndStorageLocationId(materialId, projectId, storageLocationId)
                    .orElse(null);
        } else {
            stock = currentStockRepository
                    .findByMaterialIdAndProjectIdAndStorageLocationIsNull(materialId, projectId)
                    .orElse(null);
        }
        if (stock == null || stock.getCurrentQuantity() <= 0 || stock.getStockValue().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return stock.getStockValue().divide(BigDecimal.valueOf(stock.getCurrentQuantity()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Get stock value for a material at a specific project (across all storage locations).
     */
    @Transactional(readOnly = true)
    public BigDecimal getStockValue(Long materialId, Long projectId) {
        return currentStockRepository.sumStockValueByMaterialAndProject(materialId, projectId);
    }

    /**
     * Get aggregate stock value for a material across all projects.
     */
    @Transactional(readOnly = true)
    public BigDecimal getAggregateStockValue(Long materialId) {
        return currentStockRepository.sumStockValueByMaterial(materialId);
    }

    /**
     * Get stock value for a material at a specific storage location.
     */
    @Transactional(readOnly = true)
    public BigDecimal getStockValueAtLocation(Long materialId, Long projectId, Long storageLocationId) {
        return currentStockRepository
                .findByMaterialIdAndProjectIdAndStorageLocationId(materialId, projectId, storageLocationId)
                .map(CurrentStock::getStockValue)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Recalculate stock from transaction history (SUM of quantityChanged).
     * Use this for auditing or correcting drift between CurrentStock and actual transactions.
     */
    @Transactional(readOnly = true)
    public Double recalculateStockFromTransactions(Long materialId, Long projectId) {
        return inventoryTransactionRepository.sumQuantityChangedByMaterialAndProject(materialId, projectId);
    }
}
