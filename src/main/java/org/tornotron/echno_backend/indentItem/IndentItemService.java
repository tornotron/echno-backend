package org.tornotron.echno_backend.indentItem;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.IndentItemDtoConvertor;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class IndentItemService {

    private final IndentItemRepository indentItemRepository;
    private final IndentRepository indentRepository;
    private final MaterialRepository materialRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final FileStorageService fileStorageService;
    private final InventoryService inventoryService;

    public IndentItemService(IndentItemRepository indentItemRepository,
                             IndentRepository indentRepository,
                             MaterialRepository materialRepository,
                             TenantEntityHelper tenantEntityHelper, FileStorageService fileStorageService, InventoryService inventoryService) {
        this.indentItemRepository = indentItemRepository;
        this.indentRepository = indentRepository;
        this.materialRepository = materialRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.fileStorageService = fileStorageService;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public IndentItemDto createIndentItem(IndentItemCreationDto creationDto) {
        Indent indent = indentRepository.findById(creationDto.getIndentId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent not found with id: " + creationDto.getIndentId()));

        Material material = materialRepository.findById(creationDto.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + creationDto.getMaterialId()));

        IndentItem indentItem = new IndentItem();
        indentItem.setIndent(indent);
        indentItem.setMaterial(material);
        indentItem.setAdditionalSpecifications(creationDto.getAdditionalSpecifications());
        indentItem.setRequestedQuantity(creationDto.getRequestedQuantity());
        indentItem.setOrderedQuantity(creationDto.getOrderedQuantity());
        indentItem.setRemarks(creationDto.getRemarks());
        indentItem.setConvertedToPurchaseOrder(false);
        indentItem.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        indentItem = indentItemRepository.save(indentItem);
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService);
    }

    @Transactional(readOnly = true)
    public IndentItemDto getIndentItemById(Long id) {
        IndentItem indentItem = indentItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + id));
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService);
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getAllIndentItems() {
        return indentItemRepository.findAll().stream()
                .map(indentItem -> IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByIndentId(Long indentId) {
        return indentItemRepository.findByIndentId(indentId).stream()
                .map(indentItem -> IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByMaterialId(Long materialId) {
        return indentItemRepository.findByMaterialId(materialId).stream()
                .map(indentItem -> IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByConversionStatus(Boolean converted) {
        return indentItemRepository.findByConvertedToPurchaseOrder(converted).stream()
                .map(indentItem -> IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService))
                .collect(Collectors.toList());
    }

    @Transactional
    public IndentItemDto updateIndentItem(Long id, IndentItemCreationDto updateDto) {
        IndentItem indentItem = indentItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + id));

        Indent indent = indentRepository.findById(updateDto.getIndentId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent not found with id: " + updateDto.getIndentId()));

        Material material = materialRepository.findById(updateDto.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + updateDto.getMaterialId()));

        indentItem.setIndent(indent);
        indentItem.setMaterial(material);
        indentItem.setAdditionalSpecifications(updateDto.getAdditionalSpecifications());
        indentItem.setRequestedQuantity(updateDto.getRequestedQuantity());
        indentItem.setOrderedQuantity(updateDto.getOrderedQuantity());
        indentItem.setRemarks(updateDto.getRemarks());

        indentItem = indentItemRepository.save(indentItem);
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService);
    }

    @Transactional
    public void deleteIndentItem(Long id) {
        if (!indentItemRepository.existsById(id)) {
            throw new ResourceNotFoundException("IndentItem not found with id: " + id);
        }
        indentItemRepository.deleteById(id);
    }

    @Transactional
    public IndentItemDto markAsConverted(Long id, String purchaseOrderNumber) {
        IndentItem indentItem = indentItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + id));

        indentItem.setConvertedToPurchaseOrder(true);
        indentItem.setLinkedPurchaseOrderNumber(purchaseOrderNumber);

        indentItem = indentItemRepository.save(indentItem);
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService);
    }
}
