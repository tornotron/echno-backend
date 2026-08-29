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
     * <p>Flattens a missing balance row to zero. Use {@link #findStockAtLocation} where the
     * difference matters: a material that has never been stocked at a location and a location
     * that has genuinely run out are different situations and deserve different messages.
     *
     * @param materialId The material to look up.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to read.
     * @return The quantity on hand at that location, or zero if no row exists.
     */
    @Transactional(readOnly = true)
    public Double getStockAtLocation(Long materialId, Long projectId, Long storageLocationId) {
        return findStockAtLocation(materialId, projectId, storageLocationId).orElse(0.0);
    }

    /**
     * Returns the balance for a material at one storage location, empty when no row exists.
     *
     * <p>The empty result means "this material has never been stocked here", which reads the
     * same as a zero balance once flattened but is a different fact about the data.
     *
     * @param materialId The material to look up.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to read.
     * @return The quantity on hand, or empty when the balance row does not exist.
     */
    @Transactional(readOnly = true)
    public Optional<Double> findStockAtLocation(Long materialId, Long projectId, Long storageLocationId) {
        return currentStockRepository
                .findByMaterialIdAndProjectIdAndStorageLocationId(materialId, projectId, storageLocationId)
                .map(CurrentStock::getCurrentQuantity);
    }

    /**
     * Returns the project balance held against no storage location, empty when no row exists.
     *
     * <p>This is the row a movement with no storage location reads and writes. It is not the
     * project total: stock booked into a storage location lives in its own row and is not
     * reachable from here.
     *
     * @param materialId The material to look up.
     * @param projectId The project to read.
     * @return The unlocated quantity on hand, or empty when the balance row does not exist.
     */
    @Transactional(readOnly = true)
    public Optional<Double> findUnlocatedStock(Long materialId, Long projectId) {
        return currentStockRepository
                .findByMaterialIdAndProjectIdAndStorageLocationIsNull(materialId, projectId)
                .map(CurrentStock::getCurrentQuantity);
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
     * <p>A balance row that does not exist is reported separately from a row that has run
     * down to zero. Both leave nothing to draw on, but only the second is a stock-out; the
     * first means the material has never been received into that location, which is a
     * different thing to tell the user and a different thing for them to fix.
     *
     * @param materialId The material to check.
     * @param projectId The project the location belongs to.
     * @param storageLocationId The storage location to check.
     * @param requiredQuantity The quantity that must be available.
     * @throws InsufficientStockException if no balance exists at the location, or the quantity on hand is below the required amount.
     */
    public void validateSufficientStockAtLocation(Long materialId, Long projectId, Long storageLocationId, Double requiredQuantity) {
        Optional<Double> balance = findStockAtLocation(materialId, projectId, storageLocationId);
        if (balance.isEmpty() && requiredQuantity > 0) {
            throw new InsufficientStockException(
                String.format("No stock of material ID %d has ever been held at project ID %d, storage location ID %d, "
                        + "so there is none to draw on. Required: %.2f. Receive the material into this location with a "
                        + "goods receipt or a transfer in, or record a stock adjustment, before drawing from it.",
                    materialId, projectId, storageLocationId, requiredQuantity)
            );
        }
        Double currentStock = balance.orElse(0.0);
        if (currentStock < requiredQuantity) {
            throw new InsufficientStockException(
                String.format("Insufficient stock for material ID %d at project ID %d, storage location ID %d. Required: %.2f, Available: %.2f",
                    materialId, projectId, storageLocationId, requiredQuantity, currentStock)
            );
        }
    }

    /**
     * Checks the project balance held against no storage location.
     *
     * <p>This is the check for a movement that names no location, because that is the row the
     * movement goes on to write. {@link #validateSufficientStock} is deliberately not used
     * here: it sums every row on the project, so it passes on stock sitting in storage
     * locations that the unlocated write will never touch, and the balance goes negative.
     *
     * @param materialId The material to check.
     * @param projectId The project to check within.
     * @param requiredQuantity The quantity that must be available.
     * @throws InsufficientStockException if no unlocated balance exists, or it is below the required amount.
     */
    public void validateSufficientUnlocatedStock(Long materialId, Long projectId, Double requiredQuantity) {
        Optional<Double> balance = findUnlocatedStock(materialId, projectId);
        if (balance.isEmpty() && requiredQuantity > 0) {
            throw new InsufficientStockException(
                String.format("Material ID %d holds no stock at project ID %d outside a storage location. Required: %.2f. "
                        + "Any stock the project holds sits in its storage locations, so name the location to draw from.",
                    materialId, projectId, requiredQuantity)
            );
        }
        Double currentStock = balance.orElse(0.0);
        if (currentStock < requiredQuantity) {
            throw new InsufficientStockException(
                String.format("Insufficient stock for material ID %d at project ID %d outside a storage location. "
                        + "Required: %.2f, Available: %.2f",
                    materialId, projectId, requiredQuantity, currentStock)
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
            Optional<Double> balance = findStockAtLocation(materialId, projectId, storageLocationId);

            if (balance.isEmpty() && required > 0) {
                insufficientItems.add(
                    String.format("Material ID %d: Required %.2f, never stocked at this location",
                        materialId, required)
                );
                continue;
            }
            Double available = balance.orElse(0.0);
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
     * Checks the project's unlocated balance holds enough of every requested material.
     *
     * <p>The multiple-item form of {@link #validateSufficientUnlocatedStock}, for a movement
     * that names no storage location and so reads and writes the project's unlocated row.
     * A check that sums every row on the project is deliberately not used here: it would
     * authorise a draw against stock sitting in storage locations that the unlocated write
     * never touches, and the balance would go negative.
     *
     * <p>All shortfalls are collected so the failure lists every insufficient material
     * rather than stopping at the first.
     *
     * @param requiredQuantities A map from material id to the quantity needed.
     * @param projectId The project to check within.
     * @throws InsufficientStockException if any material falls short, naming each shortfall.
     */
    public void validateSufficientUnlocatedStockForMultipleItems(Map<Long, Double> requiredQuantities, Long projectId) {
        List<String> insufficientItems = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : requiredQuantities.entrySet()) {
            Long materialId = entry.getKey();
            Double required = entry.getValue();
            Optional<Double> balance = findUnlocatedStock(materialId, projectId);

            if (balance.isEmpty() && required > 0) {
                insufficientItems.add(
                    String.format("Material ID %d: Required %.2f, none held outside a storage location",
                        materialId, required)
                );
                continue;
            }
            Double available = balance.orElse(0.0);
            if (available < required) {
                insufficientItems.add(
                    String.format("Material ID %d: Required %.2f, Available %.2f",
                        materialId, required, available)
                );
            }
        }

        if (!insufficientItems.isEmpty()) {
            throw new InsufficientStockException(
                "Insufficient stock at project ID " + projectId + " outside a storage location for items: "
                    + String.join("; ", insufficientItems)
                    + ". Any stock the project holds sits in its storage locations, so name the location to draw from."
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
     * Reads the aggregate stock for a whole set of materials in one query.
     *
     * <p>This is what a caller uses before mapping a page. {@link #getAggregateStock} and
     * {@link #getAggregateStockValue} answer for one material and cost two queries doing it, so
     * asking them once per row is what turned a fifty-material page into a hundred reads. Both
     * aggregates come back in a single grouped read here, and materials that hold no stock read
     * as zero rather than being missing.
     *
     * @param materialIds The materials about to be mapped; duplicates and nulls are ignored.
     * @return A lookup over their totals, empty when no ids were given.
     */
    @Transactional(readOnly = true)
    public MaterialStockLookup aggregateStockFor(Collection<Long> materialIds) {
        Set<Long> ids = distinctIds(materialIds);
        if (ids.isEmpty()) {
            return MaterialStockLookup.none();
        }
        return MaterialStockLookup.of(currentStockRepository.sumStockByMaterialIds(ids));
    }

    /**
     * Reads the distinct-material count for a whole set of storage locations in one query.
     *
     * <p>The storage-location counterpart to {@link #aggregateStockFor}, for the same reason: the
     * count used to run once per row on every location listing.
     *
     * @param storageLocationIds The locations about to be mapped; duplicates and nulls are ignored.
     * @return A lookup over their counts, empty when no ids were given.
     */
    @Transactional(readOnly = true)
    public StorageLocationItemCounts itemCountsAt(Collection<Long> storageLocationIds) {
        Set<Long> ids = distinctIds(storageLocationIds);
        if (ids.isEmpty()) {
            return StorageLocationItemCounts.none();
        }
        return StorageLocationItemCounts.of(currentStockRepository
                .countDistinctMaterialsByStorageLocationIds(ids, TenantContext.getCurrentOrgId()));
    }

    private static Set<Long> distinctIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<Long> distinct = new HashSet<>(ids);
        distinct.remove(null);
        return distinct;
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
