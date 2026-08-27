package org.tornotron.echno_backend.indentItem;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.IndentRepository;
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
    private final IndentItemMapper indentItemMapper;

    public IndentItemService(IndentItemRepository indentItemRepository,
                             IndentRepository indentRepository,
                             MaterialRepository materialRepository,
                             TenantEntityHelper tenantEntityHelper, IndentItemMapper indentItemMapper) {
        this.indentItemRepository = indentItemRepository;
        this.indentRepository = indentRepository;
        this.materialRepository = materialRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.indentItemMapper = indentItemMapper;
    }

    @Transactional
    public IndentItemDto createIndentItem(IndentItemCreationDto creationDto) {
        Indent indent = indentRepository.findByIdAndOrganization_Id(creationDto.getIndentId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + creationDto.getIndentId() + " was not found in this organization"));

        Material material = materialRepository.findByIdAndOrganization_Id(creationDto.getMaterialId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + creationDto.getMaterialId() + " was not found in this organization"));

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
        return indentItemMapper.toDto(indentItem);
    }

    @Transactional(readOnly = true)
    public IndentItemDto getIndentItemById(Long id) {
        IndentItem indentItem = indentItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization"));
        return indentItemMapper.toDto(indentItem);
    }

    /**
     * Retrieves indent items one page at a time.
     *
     * <p>Replaces an unpaginated read of the whole table. Indent items accumulate with every
     * indent a tenant raises, so the row count has no ceiling and the only safe read is a
     * bounded one.
     *
     * @param pageable The page to fetch.
     * @return A page of indent item DTOs.
     */
    @Transactional(readOnly = true)
    public Page<IndentItemDto> getAllIndentItems(Pageable pageable) {
        return indentItemRepository.findAll(pageable)
                .map(indentItem -> indentItemMapper.toDto(indentItem));
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByIndentId(Long indentId) {
        return indentItemRepository.findByIndentId(indentId).stream()
                .map(indentItem -> indentItemMapper.toDto(indentItem))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByMaterialId(Long materialId) {
        return indentItemRepository.findByMaterialId(materialId).stream()
                .map(indentItem -> indentItemMapper.toDto(indentItem))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByConversionStatus(Boolean converted) {
        return indentItemRepository.findByConvertedToPurchaseOrder(converted).stream()
                .map(indentItem -> indentItemMapper.toDto(indentItem))
                .collect(Collectors.toList());
    }

    @Transactional
    public IndentItemDto updateIndentItem(Long id, IndentItemCreationDto updateDto) {
        IndentItem indentItem = indentItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization"));

        Indent indent = indentRepository.findByIdAndOrganization_Id(updateDto.getIndentId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + updateDto.getIndentId() + " was not found in this organization"));

        Material material = materialRepository.findByIdAndOrganization_Id(updateDto.getMaterialId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + updateDto.getMaterialId() + " was not found in this organization"));

        indentItem.setIndent(indent);
        indentItem.setMaterial(material);
        indentItem.setAdditionalSpecifications(updateDto.getAdditionalSpecifications());
        indentItem.setRequestedQuantity(updateDto.getRequestedQuantity());
        indentItem.setOrderedQuantity(updateDto.getOrderedQuantity());
        indentItem.setRemarks(updateDto.getRemarks());

        indentItem = indentItemRepository.save(indentItem);
        return indentItemMapper.toDto(indentItem);
    }

    @Transactional
    public void deleteIndentItem(Long id) {
        if (!indentItemRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization");
        }
        indentItemRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }

    @Transactional
    public IndentItemDto markAsConverted(Long id, String purchaseOrderNumber) {
        IndentItem indentItem = indentItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization"));

        indentItem.setConvertedToPurchaseOrder(true);
        indentItem.setLinkedPurchaseOrderNumber(purchaseOrderNumber);

        indentItem = indentItemRepository.save(indentItem);
        return indentItemMapper.toDto(indentItem);
    }
}
