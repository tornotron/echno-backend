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
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
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

    public SiteTransferService(SiteTransferRepository siteTransferRepository,
                              SiteTransferItemRepository siteTransferItemRepository,
                              UserRepository userRepository,
                              MaterialRepository materialRepository,
                              InventoryService inventoryService,
                              ApplicationEventPublisher eventPublisher,
                              FileStorageService fileStorageService,
                              TenantEntityHelper tenantEntityHelper) {
        this.siteTransferRepository = siteTransferRepository;
        this.siteTransferItemRepository = siteTransferItemRepository;
        this.userRepository = userRepository;
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
    }

    @Transactional
    public SiteTransferDto createSiteTransfer(SiteTransferCreationDto creationDto) {
        // Check for duplicate transfer number
        if (siteTransferRepository.existsByTransferNumber(creationDto.getTransferNumber())) {
            throw new DuplicateResourceException("Site transfer with number " + creationDto.getTransferNumber() + " already exists");
        }

        // Validate user exists
        User sendingPerson = userRepository.findUserByName(creationDto.getSendingPerson())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with name: " + creationDto.getSendingPerson()));

        // CRITICAL: Validate sufficient stock for ALL items before creating transfer
        Map<Long, Integer> requiredQuantities = new HashMap<>();
        for (SiteTransferItemDto itemDto : creationDto.getItems()) {
            requiredQuantities.merge(itemDto.getMaterialId(), itemDto.getSentQuantity(), Integer::sum);
        }
        inventoryService.validateSufficientStockForMultipleItems(requiredQuantities);

        // Create site transfer
        SiteTransfer transfer = new SiteTransfer();
        transfer.setTransferNumber(creationDto.getTransferNumber());
        transfer.setIssueDate(creationDto.getIssueDate());
        transfer.setSendingPerson(sendingPerson);
        transfer.setReceivingSite(creationDto.getReceivingSite());
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
    public List<SiteTransferDto> getSiteTransfersByReceivingSite(String receivingSite) {
        return siteTransferRepository.findByReceivingSite(receivingSite).stream()
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
