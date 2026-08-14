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
import org.tornotron.echno_backend.inventoryTransaction.dto.TaskMaterialUsageDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InventoryTransactionService {

    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryTransactionMapper inventoryTransactionMapper;

    public InventoryTransactionService(InventoryTransactionRepository inventoryTransactionRepository,
                                       InventoryTransactionMapper inventoryTransactionMapper) {
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.inventoryTransactionMapper = inventoryTransactionMapper;
    }

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
