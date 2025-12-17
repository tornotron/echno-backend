package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.InventoryTransactionDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class InventoryTransactionService {

    private final InventoryTransactionRepository inventoryTransactionRepository;

    public InventoryTransactionService(InventoryTransactionRepository inventoryTransactionRepository) {
        this.inventoryTransactionRepository = inventoryTransactionRepository;
    }

    @Transactional(readOnly = true)
    public InventoryTransactionDto getTransactionById(Long id) {
        InventoryTransaction transaction = inventoryTransactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory transaction not found with id: " + id));
        return InventoryTransactionDtoConvertor.convertToDto(transaction);
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getAllTransactions() {
        return inventoryTransactionRepository.findAll().stream()
                .map(InventoryTransactionDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<InventoryTransactionDto> getAllTransactions(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "transactionDate"));
        return inventoryTransactionRepository.findAll(pageable)
                .map(InventoryTransactionDtoConvertor::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByMaterial(Long materialId) {
        return inventoryTransactionRepository.findByMaterialId(materialId).stream()
                .map(InventoryTransactionDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByType(InventoryTransactionType transactionType) {
        return inventoryTransactionRepository.findByTransactionType(transactionType).stream()
                .map(InventoryTransactionDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionDto> getTransactionsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return inventoryTransactionRepository.findByTransactionDateBetween(startDate, endDate).stream()
                .map(InventoryTransactionDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }
}
