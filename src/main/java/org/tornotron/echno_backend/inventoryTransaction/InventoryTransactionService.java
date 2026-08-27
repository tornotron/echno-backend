package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.inventoryTransaction.mapper.InventoryTransactionMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialMovementHistoryDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.TaskMaterialUsageDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read access over the append-only inventory transaction ledger.
 *
 * <p>Exposes single-row lookup and various filtered listings of stock movements, plus a
 * per-task material usage rollup. This service does not write ledger rows; those are
 * created by the event handlers that post stock changes.
 */
@Service
public class InventoryTransactionService {

    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryTransactionMapper inventoryTransactionMapper;

    public InventoryTransactionService(InventoryTransactionRepository inventoryTransactionRepository,
                                       InventoryTransactionMapper inventoryTransactionMapper) {
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.inventoryTransactionMapper = inventoryTransactionMapper;
    }

    /**
     * Retrieves a single inventory transaction by its id within the current tenant.
     *
     * @param id The id of the transaction to retrieve.
     * @return The transaction as a DTO.
     * @throws ResourceNotFoundException if no transaction with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public InventoryTransactionDto getTransactionById(Long id) {
        InventoryTransaction transaction = inventoryTransactionRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory transaction not found with id: " + id));
        return inventoryTransactionMapper.toDto(transaction);
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getAllTransactions() {
        return inventoryTransactionRepository.findAll().stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    /**
     * Retrieves transactions one page at a time, newest first.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of transactions per page.
     * @return A page of transaction DTOs ordered by transaction date descending.
     */
    @Transactional(readOnly = true)
    public Page<InventoryTransactionDto> getAllTransactions(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "transactionDate"));
        return inventoryTransactionRepository.findAll(pageable)
                .map(transaction -> inventoryTransactionMapper.toDto(transaction));
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByMaterial(Long materialId) {
        return inventoryTransactionRepository.findByMaterialId(materialId).stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    /**
     * A material's movement history as a timeline, one page at a time, oldest movement first.
     *
     * <p>Backs the Location module timeline (issue #256): each entry carries where the material
     * moved (storage location), the project, when, the movement type and its stock direction,
     * the quantity changed, the stock level either side of it, the source reference and the employee
     * who booked it. Ordered oldest-first so the page reads as a forward-running timeline, with the id
     * as a stable tie-break within one transaction date. The finder fetch-joins the associations the
     * entry reads, so a page costs one query rather than one per row.
     *
     * @param materialId The material whose movements are listed.
     * @param pageNo Zero-based page index.
     * @param pageSize Number of movements per page.
     * @return A page of movement-history entries ordered by transaction date ascending.
     */
    @Transactional(readOnly = true)
    public Page<MaterialMovementHistoryDto> getMaterialMovementHistory(Long materialId, int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize);
        return inventoryTransactionRepository.findMovementHistoryByMaterial(materialId, pageable)
                .map(inventoryTransactionMapper::toMovementHistoryDto);
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByProject(Long projectId) {
        return inventoryTransactionRepository.findByProjectId(projectId).stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByType(InventoryTransactionType transactionType) {
        return inventoryTransactionRepository.findByTransactionType(transactionType).stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return inventoryTransactionRepository.findByTransactionDateBetween(startDate, endDate).stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByStorageLocation(Long storageLocationId) {
        return inventoryTransactionRepository.findByStorageLocationId(storageLocationId).stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    /**
     * Lists transactions for one material at one storage location within a project.
     *
     * @param storageLocationId The storage location to filter by.
     * @param materialId The material to filter by.
     * @param projectId The project to filter by.
     * @return The matching transactions as DTOs.
     */
    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByStorageLocationMaterialAndProject(Long storageLocationId, Long materialId, Long projectId) {
        return inventoryTransactionRepository.findByStorageLocationIdAndMaterialIdAndProjectId(storageLocationId,materialId,projectId).stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByTask(Long taskId) {
        return inventoryTransactionRepository.findByTaskId(taskId).stream()
                .map(transaction -> inventoryTransactionMapper.toDto(transaction))
                .collect(Collectors.toList());
    }

    /**
     * Summarises material usage per task for a project from the transaction ledger.
     *
     * <p>Considers only transactions attached to a task, grouping them by task and then by
     * material. Usage transactions carry a negative quantity, so quantities are accumulated
     * as absolute values, and cost is accumulated from the unit cost where present. Task and
     * material totals are rolled up alongside the per-material lines.
     *
     * @param projectId The project whose task material usage is summarised.
     * @return One entry per task, each listing its materials with quantity and cost totals.
     */
    @Transactional(readOnly = true)
    public List<TaskMaterialUsageDto> getTaskMaterialUsageSummary(Long projectId) {
        List<InventoryTransaction> transactions = inventoryTransactionRepository.findByProjectIdAndTaskIsNotNull(projectId);

        // Group by task, then by material
        Map<Long, TaskMaterialUsageDto> taskMap = new LinkedHashMap<>();

        for (InventoryTransaction txn : transactions) {
            Long taskId = txn.getTask().getId();
            String taskTitle = txn.getTask().getTitle();
            Long materialId = txn.getMaterial().getId();

            TaskMaterialUsageDto taskDto = taskMap.computeIfAbsent(taskId, k -> {
                TaskMaterialUsageDto dto = new TaskMaterialUsageDto();
                dto.setTaskId(taskId);
                dto.setTaskTitle(taskTitle);
                dto.setMaterials(new ArrayList<>());
                dto.setTotalQuantityUsed(0.0);
                dto.setTotalCost(BigDecimal.ZERO);
                return dto;
            });

            // Find or create material usage item
            TaskMaterialUsageDto.MaterialUsageItem materialItem = taskDto.getMaterials().stream()
                    .filter(m -> m.getMaterialId().equals(materialId))
                    .findFirst()
                    .orElseGet(() -> {
                        TaskMaterialUsageDto.MaterialUsageItem item = new TaskMaterialUsageDto.MaterialUsageItem();
                        item.setMaterialId(materialId);
                        item.setMaterialName(txn.getMaterial().getMaterialName());
                        item.setUnit(txn.getMaterial().getUnit());
                        item.setTotalQuantityUsed(0.0);
                        item.setTotalCost(BigDecimal.ZERO);
                        taskDto.getMaterials().add(item);
                        return item;
                    });

            // Accumulate quantities (USE transactions have negative quantityChanged, so we use abs)
            Double absQuantity = Math.abs(txn.getQuantityChanged());
            materialItem.setTotalQuantityUsed(materialItem.getTotalQuantityUsed() + absQuantity);
            if (txn.getUnitCost() != null) {
                BigDecimal cost = txn.getUnitCost().multiply(BigDecimal.valueOf(absQuantity));
                materialItem.setTotalCost(materialItem.getTotalCost().add(cost));
            }

            // Update task totals
            taskDto.setTotalQuantityUsed(taskDto.getTotalQuantityUsed() + absQuantity);
            if (txn.getUnitCost() != null) {
                BigDecimal cost = txn.getUnitCost().multiply(BigDecimal.valueOf(absQuantity));
                taskDto.setTotalCost(taskDto.getTotalCost().add(cost));
            }
        }

        return new ArrayList<>(taskMap.values());
    }
}
