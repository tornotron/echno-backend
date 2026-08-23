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

/**
 * Reads stock positions and applies balance changes over the CurrentStock table.
 *
 * <p>Read methods sum or look up {@code CurrentStock} rows rather than replaying the
 * ledger, giving quantity and value on hand per material, project, and storage location.
 * {@link #updateCurrentStock} is the single write path that ledger events call to move a
 * balance, using row locking and a seeded zero row so concurrent movements on the same key
 * stay consistent. Stock value is carried as a running weighted average of unit cost.
 */
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
     * Returns current stock for a material at a project, summed across all storage locations.
     *
     * @param materialId The material to total.
     * @param projectId The project to total within.
     * @return The quantity on hand across the project's storage locations.
     */
    @Transactional(readOnly = true)
    public Double getCurrentStock(Long materialId, Long projectId) {
        return currentStockRepository.sumCurrentQuantityByMaterialAndProject(materialId, projectId);
    }

    /**
     * Returns total stock for a material across all projects in the organization.
     *
     * @param materialId The material to total.
     * @return The quantity on hand across every project.
     */
    @Transactional(readOnly = true)
    public Double getAggregateStock(Long materialId) {
        return currentStockRepository.sumCurrentQuantityByMaterial(materialId);
    }

    /**
     * Returns current stock for a material at one storage location within a project.
     *
     * @param materialId The material to look up.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to read.
     * @return The quantity on hand at that location, or zero if no row exists.
     */
    @Transactional(readOnly = true)
    public Double getStockAtLocation(Long materialId, Long projectId, Long storageLocationId) {
        return currentStockRepository
                .findByMaterialIdAndProjectIdAndStorageLocationId(materialId, projectId, storageLocationId)
                .map(CurrentStock::getCurrentQuantity)
                .orElse(0.0);
    }

    /**
     * Returns current stock for several materials at a project in one call.
     *
     * @param materialIds The materials to total.
     * @param projectId The project to total within.
     * @return A map from material id to quantity on hand at the project.
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
     * Checks that a project holds at least the required quantity of a material.
     *
     * @param materialId The material to check.
     * @param projectId The project to check within.
     * @param requiredQuantity The quantity that must be available.
     * @throws InsufficientStockException if the quantity on hand is below the required amount.
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
     * Checks that a storage location holds at least the required quantity of a material.
     *
     * @param materialId The material to check.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to check.
     * @param requiredQuantity The quantity that must be available.
     * @throws InsufficientStockException if the quantity on hand at the location is below the required amount.
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
     * Checks that a storage location holds enough of every requested material.
     *
     * <p>All shortfalls are collected so the failure lists every insufficient material
     * rather than stopping at the first.
     *
     * @param requiredQuantities A map from material id to the quantity needed.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to check.
     * @throws InsufficientStockException if any material falls short, naming each shortfall.
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
     * Returns the stock of every material held at a storage location, with totals.
     *
     * @param storageLocationId The storage location to report on.
     * @return Per-material stock and value at the location, plus location totals.
     * @throws ResourceNotFoundException if the storage location is not found in this organization.
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
     * Returns a material's stock broken down by storage location, with totals.
     *
     * @param materialId The material to report on.
     * @return Per-location stock and value for the material, plus overall totals.
     * @throws jakarta.persistence.EntityNotFoundException if the material is not found in this organization.
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
     * Checks that a project holds enough of every requested material across its locations.
     *
     * <p>All shortfalls are collected so the failure lists every insufficient material
     * rather than stopping at the first.
     *
     * @param requiredQuantities A map from material id to the quantity needed.
     * @param projectId The project to check within.
     * @throws InsufficientStockException if any material falls short, naming each shortfall.
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
     * Applies a signed quantity change to a CurrentStock balance, creating the row if absent.
     *
     * <p>Must run in the same transaction as the matching {@code InventoryTransaction} save.
     * A zero row is seeded first so the subsequent row lock always finds exactly one row to
     * serialize on, which keeps concurrent first-time movements on the same key from splitting
     * the balance across duplicate rows. Stock value is adjusted as a running weighted average:
     * inbound movements add incoming value, outbound movements reduce value at the current
     * average cost.
     *
     * @param material The material whose balance moves.
     * @param project The project the balance belongs to.
     * @param storageLocation The storage location, or null for a project-level balance with no location.
     * @param organization The owning organization used to seed the row.
     * @param quantityChanged The signed change (positive inbound, negative outbound).
     * @param unitCost For an inbound change, the cost per unit; for an outbound change, pass null to value the reduction at weighted average cost.
     * @return The saved CurrentStock row after the change.
     * @throws IllegalStateException if the balance row cannot be found after seeding.
     */
    @Transactional
    public CurrentStock updateCurrentStock(Material material, Project project, StorageLocation storageLocation,
                                           Organization organization, Double quantityChanged, BigDecimal unitCost) {
        Long storageLocationId = storageLocation != null ? storageLocation.getId() : null;

        // Guarantee the row exists before locking. A plain "lock or else create" loses the
        // race when two events create the first record for the same key at once: both find
        // no row to lock and both insert. For a located row the composite unique constraint
        // rejects the second insert, but for a no-location row NULLs are distinct so both
        // would succeed and split the stock across duplicate rows. Seeding a zero row (a
        // no-op on conflict) means the lock below always finds exactly one row to serialize on.
        currentStockRepository.seedZeroStockRow(material.getId(), project.getId(),
                storageLocationId, organization != null ? organization.getId() : null);

        CurrentStock stock = (storageLocation != null
                ? currentStockRepository.lockByMaterialProjectAndLocation(
                        material.getId(), project.getId(), storageLocationId)
                : currentStockRepository.lockByMaterialProjectAndNoLocation(
                        material.getId(), project.getId()))
                .orElseThrow(() -> new IllegalStateException(
                        "Current stock row missing after seed for material " + material.getId()
                                + " project " + project.getId()));

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
     * Returns the weighted average unit cost for a material at a balance row.
     *
     * <p>When a storage location is given the located row is used, otherwise the
     * no-location project row is used.
     *
     * @param materialId The material to price.
     * @param projectId The project the balance belongs to.
     * @param storageLocationId The storage location, or null for the no-location balance.
     * @return The average unit cost, or zero when there is no priced quantity on hand.
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
     * Returns the stock value for a material at a project, summed across all locations.
     *
     * @param materialId The material to value.
     * @param projectId The project to value within.
     * @return The total stock value across the project's storage locations.
     */
    @Transactional(readOnly = true)
    public BigDecimal getStockValue(Long materialId, Long projectId) {
        return currentStockRepository.sumStockValueByMaterialAndProject(materialId, projectId);
    }

    /**
     * Returns the total stock value for a material across all projects.
     *
     * @param materialId The material to value.
     * @return The total stock value across every project.
     */
    @Transactional(readOnly = true)
    public BigDecimal getAggregateStockValue(Long materialId) {
        return currentStockRepository.sumStockValueByMaterial(materialId);
    }

    /**
     * Returns the stock value for a material at one storage location.
     *
     * @param materialId The material to value.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to value.
     * @return The stock value at that location, or zero if no row exists.
     */
    @Transactional(readOnly = true)
    public BigDecimal getStockValueAtLocation(Long materialId, Long projectId, Long storageLocationId) {
        return currentStockRepository
                .findByMaterialIdAndProjectIdAndStorageLocationId(materialId, projectId, storageLocationId)
                .map(CurrentStock::getStockValue)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Recomputes stock for a material at a project by summing the ledger's quantity changes.
     *
     * <p>Use this to audit or correct drift between the CurrentStock balance and the
     * underlying inventory transactions.
     *
     * @param materialId The material to recompute.
     * @param projectId The project to recompute within.
     * @return The stock implied by the sum of ledger movements.
     */
    @Transactional(readOnly = true)
    public Double recalculateStockFromTransactions(Long materialId, Long projectId) {
        return inventoryTransactionRepository.sumQuantityChangedByMaterialAndProject(materialId, projectId);
    }
}
