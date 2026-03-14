package org.tornotron.echno_backend.siteTransfer;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.SiteTransferDtoConvertor;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SiteTransferService {

    private final SiteTransferRepository siteTransferRepository;
    private final SiteTransferItemRepository siteTransferItemRepository;
    private final UserRepository userRepository;
    private final MaterialRepository materialRepository;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStorageService fileStorageService;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final StorageLocationRepository storageLocationRepository;

    public SiteTransferService(SiteTransferRepository siteTransferRepository,
                               SiteTransferItemRepository siteTransferItemRepository,
                               UserRepository userRepository,
                               MaterialRepository materialRepository,
                               InventoryService inventoryService,
                               ApplicationEventPublisher eventPublisher,
                               FileStorageService fileStorageService,
                               TenantEntityHelper tenantEntityHelper,
                               EmployeeRepository employeeRepository,
                               ProjectRepository projectRepository,
                               StorageLocationRepository storageLocationRepository) {
        this.siteTransferRepository = siteTransferRepository;
        this.siteTransferItemRepository = siteTransferItemRepository;
        this.userRepository = userRepository;
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.storageLocationRepository = storageLocationRepository;
    }

    @Transactional
    public SiteTransferDto createSiteTransfer(SiteTransferCreationDto creationDto) {
        // Check for duplicate transfer number
        if (siteTransferRepository.existsByTransferNumber(creationDto.getTransferNumber())) {
            throw new DuplicateResourceException("Site transfer with number " + creationDto.getTransferNumber() + " already exists");
        }

        Employee sendingPerson = employeeRepository.findByIdAndOrganizationId(creationDto.getSendingPerson(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + creationDto.getSendingPerson()));

        // Validate sending project
        Project sendingProject = projectRepository.findByIdAndOrganization_Id(creationDto.getSendingProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Sending project not found with id: " + creationDto.getSendingProjectId()));

        // Validate receiving project
        Project receivingProject = projectRepository.findByIdAndOrganization_Id(creationDto.getReceivingProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving project not found with id: " + creationDto.getReceivingProjectId()));

        // CRITICAL: Validate sufficient stock at the SENDING location for ALL items
        // Use storage-location-level validation when a sending storage location is specified
        Map<Long, Integer> requiredQuantities = new HashMap<>();
        for (SiteTransferItemDto itemDto : creationDto.getItems()) {
            requiredQuantities.merge(itemDto.getMaterialId(), itemDto.getSentQuantity(), Integer::sum);
        }
        if (creationDto.getSendingStorageLocationId() != null) {
            inventoryService.validateSufficientStockForMultipleItemsAtLocation(
                    requiredQuantities, sendingProject.getId(), creationDto.getSendingStorageLocationId());
        } else {
            inventoryService.validateSufficientStockForMultipleItems(requiredQuantities, sendingProject.getId());
        }

        // Create site transfer
        SiteTransfer transfer = new SiteTransfer();
        transfer.setTransferNumber(creationDto.getTransferNumber());
        transfer.setIssueDate(creationDto.getIssueDate());
        transfer.setSendingPerson(sendingPerson);
        transfer.setSendingProject(sendingProject);
        transfer.setReceivingProject(receivingProject);

        // Validate and set storage locations (optional)
        if (creationDto.getSendingStorageLocationId() != null) {
            StorageLocation sendingLocation = storageLocationRepository.findByIdAndOrganization_Id(
                            creationDto.getSendingStorageLocationId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Sending storage location not found with id: " + creationDto.getSendingStorageLocationId()));
            transfer.setSendingStorageLocation(sendingLocation);
        }
        if (creationDto.getReceivingStorageLocationId() != null) {
            StorageLocation receivingLocation = storageLocationRepository.findByIdAndOrganization_Id(
                            creationDto.getReceivingStorageLocationId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Receiving storage location not found with id: " + creationDto.getReceivingStorageLocationId()));
            transfer.setReceivingStorageLocation(receivingLocation);
        }

        transfer.setStatus(SiteTransferStatus.valueOf(creationDto.getStatus()));
        transfer.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Save transfer first
        transfer = siteTransferRepository.save(transfer);

        // Create transfer items
        List<SiteTransferItem> items = new ArrayList<>();
        for (SiteTransferItemDto itemDto : creationDto.getItems()) {
            Material material = materialRepository.findById(itemDto.getMaterialId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + itemDto.getMaterialId()));

            SiteTransferItem item = new SiteTransferItem();
            item.setSiteTransfer(transfer);
            item.setMaterial(material);
            item.setSentQuantity(itemDto.getSentQuantity());
            item.setRemarks(itemDto.getRemarks());
            item.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

            items.add(item);
        }

        siteTransferItemRepository.saveAll(items);
        transfer.setItems(items);

        // Publish SiteTransferCreatedEvent for automatic inventory update
        eventPublisher.publishEvent(new SiteTransferCreatedEvent(this, transfer));

        return SiteTransferDtoConvertor.convertToDto(transfer, fileStorageService);
    }

    @Transactional(readOnly = true)
    public SiteTransferDto getSiteTransferById(Long id) {
        SiteTransfer transfer = siteTransferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Site transfer not found with id: " + id));
        return SiteTransferDtoConvertor.convertToDto(transfer, fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<SiteTransferDto> getAllSiteTransfers() {
        return siteTransferRepository.findAll().stream()
                .map(transfer -> SiteTransferDtoConvertor.convertToDto(transfer, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<SiteTransferDto> getAllSiteTransfers(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "issueDate"));
        return siteTransferRepository.findAll(pageable)
                .map(transfer -> SiteTransferDtoConvertor.convertToDto(transfer, fileStorageService));
    }

    @Transactional(readOnly = true)
    public List<SiteTransferDto> getSiteTransfersByStatus(SiteTransferStatus status) {
        return siteTransferRepository.findByStatus(status).stream()
                .map(transfer -> SiteTransferDtoConvertor.convertToDto(transfer, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SiteTransferDto> getSiteTransfersBySendingProject(Long projectId) {
        return siteTransferRepository.findBySendingProjectId(projectId).stream()
                .map(transfer -> SiteTransferDtoConvertor.convertToDto(transfer, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SiteTransferDto> getSiteTransfersByReceivingProject(Long projectId) {
        return siteTransferRepository.findByReceivingProjectId(projectId).stream()
                .map(transfer -> SiteTransferDtoConvertor.convertToDto(transfer, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateSiteTransferStatus(Long id, SiteTransferStatus status) {
        SiteTransfer transfer = siteTransferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Site transfer not found with id: " + id));

        transfer.setStatus(status);
        siteTransferRepository.save(transfer);
    }
}
