package org.tornotron.echno_backend.material;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.MaterialDtoConvertor;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.material.dto.MaterialCreationDto;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final org.tornotron.echno_backend.inventoryTransaction.InventoryService inventoryService;

    public MaterialService(MaterialRepository materialRepository,
                          org.tornotron.echno_backend.inventoryTransaction.InventoryService inventoryService) {
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public MaterialDto createMaterial(MaterialCreationDto creationDto) {
        // Check for duplicate SKU if provided
        if (creationDto.getSku() != null && materialRepository.existsBySku(creationDto.getSku())) {
            throw new DuplicateResourceException("Material with SKU " + creationDto.getSku() + " already exists");
        }

        Material material = new Material();
        material.setSku(creationDto.getSku());
        material.setMaterialName(creationDto.getMaterialName());
        material.setUnit(creationDto.getUnit());

        material = materialRepository.save(material);
        return MaterialDtoConvertor.convertToDto(material);
    }

    @Transactional(readOnly = true)
    public MaterialDto getMaterialById(Long id) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + id));
        return MaterialDtoConvertor.convertToDto(material);
    }

    @Transactional(readOnly = true)
    public List<MaterialDto> getAllMaterials() {
        return materialRepository.findAll().stream()
                .map(MaterialDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<MaterialDto> getAllMaterials(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "materialName"));
        return materialRepository.findAll(pageable)
                .map(MaterialDtoConvertor::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<MaterialDto> searchMaterialsByName(String name) {
        return materialRepository.findByMaterialNameContainingIgnoreCase(name).stream()
                .map(MaterialDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public MaterialDto updateMaterial(Long id, MaterialCreationDto updateDto) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + id));

        // Check SKU uniqueness if changed
        if (updateDto.getSku() != null && !updateDto.getSku().equals(material.getSku())) {
            if (materialRepository.existsBySku(updateDto.getSku())) {
                throw new DuplicateResourceException("Material with SKU " + updateDto.getSku() + " already exists");
            }
            material.setSku(updateDto.getSku());
        }

        material.setMaterialName(updateDto.getMaterialName());
        material.setUnit(updateDto.getUnit());

        material = materialRepository.save(material);
        return MaterialDtoConvertor.convertToDto(material);
    }

    @Transactional
    public void deleteMaterial(Long id) {
        if (!materialRepository.existsById(id)) {
            throw new ResourceNotFoundException("Material not found with id: " + id);
        }
        materialRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public MaterialWithStockDto getMaterialWithCurrentStock(Long id) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + id));

        Integer currentStock = inventoryService.getCurrentStock(id);
        return MaterialDtoConvertor.convertToWithStockDto(material, currentStock);
    }
}
