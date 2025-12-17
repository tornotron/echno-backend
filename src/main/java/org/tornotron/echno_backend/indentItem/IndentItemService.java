package org.tornotron.echno_backend.indentItem;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.IndentItemDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.intend.Intend;
import org.tornotron.echno_backend.intend.IntendRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class IndentItemService {

    private final IndentItemRepository indentItemRepository;
    private final IntendRepository intendRepository;
    private final MaterialRepository materialRepository;

    public IndentItemService(IndentItemRepository indentItemRepository,
                            IntendRepository intendRepository,
                            MaterialRepository materialRepository) {
        this.indentItemRepository = indentItemRepository;
        this.intendRepository = intendRepository;
        this.materialRepository = materialRepository;
    }

    @Transactional
    public IndentItemDto createIndentItem(IndentItemCreationDto creationDto) {
        Intend intend = intendRepository.findById(creationDto.getIntendId())
                .orElseThrow(() -> new ResourceNotFoundException("Intend not found with id: " + creationDto.getIntendId()));

        Material material = materialRepository.findById(creationDto.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + creationDto.getMaterialId()));

        IndentItem indentItem = new IndentItem();
        indentItem.setIntend(intend);
        indentItem.setMaterial(material);
        indentItem.setAdditionalSpecifications(creationDto.getAdditionalSpecifications());
        indentItem.setRequestedQuantity(creationDto.getRequestedQuantity());
        indentItem.setOrderedQuantity(creationDto.getOrderedQuantity());
        indentItem.setRemarks(creationDto.getRemarks());
        indentItem.setConvertedToPurchaseOrder(false);

        indentItem = indentItemRepository.save(indentItem);
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem);
    }

    @Transactional(readOnly = true)
    public IndentItemDto getIndentItemById(Long id) {
        IndentItem indentItem = indentItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + id));
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem);
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getAllIndentItems() {
        return indentItemRepository.findAll().stream()
                .map(IndentItemDtoConvertor::convertIndentItemToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByIntendId(Long intendId) {
        return indentItemRepository.findByIntendId(intendId).stream()
                .map(IndentItemDtoConvertor::convertIndentItemToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByMaterialId(Long materialId) {
        return indentItemRepository.findByMaterialId(materialId).stream()
                .map(IndentItemDtoConvertor::convertIndentItemToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByConversionStatus(Boolean converted) {
        return indentItemRepository.findByConvertedToPurchaseOrder(converted).stream()
                .map(IndentItemDtoConvertor::convertIndentItemToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public IndentItemDto updateIndentItem(Long id, IndentItemCreationDto updateDto) {
        IndentItem indentItem = indentItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + id));

        Intend intend = intendRepository.findById(updateDto.getIntendId())
                .orElseThrow(() -> new ResourceNotFoundException("Intend not found with id: " + updateDto.getIntendId()));

        Material material = materialRepository.findById(updateDto.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + updateDto.getMaterialId()));

        indentItem.setIntend(intend);
        indentItem.setMaterial(material);
        indentItem.setAdditionalSpecifications(updateDto.getAdditionalSpecifications());
        indentItem.setRequestedQuantity(updateDto.getRequestedQuantity());
        indentItem.setOrderedQuantity(updateDto.getOrderedQuantity());
        indentItem.setRemarks(updateDto.getRemarks());

        indentItem = indentItemRepository.save(indentItem);
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem);
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
        return IndentItemDtoConvertor.convertIndentItemToDto(indentItem);
    }
}
