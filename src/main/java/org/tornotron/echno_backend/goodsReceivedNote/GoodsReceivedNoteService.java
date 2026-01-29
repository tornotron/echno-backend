package org.tornotron.echno_backend.goodsReceivedNote;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.GoodsReceivedNoteDtoConvertor;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteCreationDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GrnItemDto;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.grnItem.GrnItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoodsReceivedNoteService {

    private final GoodsReceivedNoteRepository goodsReceivedNoteRepository;
    private final GrnItemRepository grnItemRepository;
    private final VendorRepository vendorRepository;
    private final UserRepository userRepository;
    private final MaterialRepository materialRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStorageService fileStorageService;

    public GoodsReceivedNoteService(GoodsReceivedNoteRepository goodsReceivedNoteRepository,
                                   GrnItemRepository grnItemRepository,
                                   VendorRepository vendorRepository,
                                   UserRepository userRepository,
                                   MaterialRepository materialRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   FileStorageService fileStorageService) {
        this.goodsReceivedNoteRepository = goodsReceivedNoteRepository;
        this.grnItemRepository = grnItemRepository;
        this.vendorRepository = vendorRepository;
        this.userRepository = userRepository;
        this.materialRepository = materialRepository;
        this.eventPublisher = eventPublisher;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public GoodsReceivedNoteDto createGoodsReceivedNote(GoodsReceivedNoteCreationDto creationDto) {
        // Check for duplicate GRN number
        if (goodsReceivedNoteRepository.existsByGrnNumber(creationDto.getGrnNumber())) {
            throw new DuplicateResourceException("GRN with number " + creationDto.getGrnNumber() + " already exists");
        }

        // Validate vendor
        Vendor vendor = vendorRepository.findById(creationDto.getVendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + creationDto.getVendorId()));

        // Validate user
        User receivedBy = userRepository.findUserByName(creationDto.getReceivedBy())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with name: " + creationDto.getReceivedBy()));

        // Create GRN
        GoodsReceivedNote grn = new GoodsReceivedNote();
        grn.setGrnNumber(creationDto.getGrnNumber());
        grn.setReceivedOn(creationDto.getReceivedOn());
        grn.setReceivedBy(receivedBy);
        grn.setVendor(vendor);
        grn.setDeliveryChallanNumber(creationDto.getDeliveryChallanNumber());
        grn.setInvoiceNumber(creationDto.getInvoiceNumber());
        grn.setInvoiceAmount(creationDto.getInvoiceAmount());

        // Save GRN first
        grn = goodsReceivedNoteRepository.save(grn);

        // Create GRN items
        List<GrnItem> items = new ArrayList<>();
        for (GrnItemDto itemDto : creationDto.getItems()) {
            Material material = materialRepository.findById(itemDto.getMaterialId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + itemDto.getMaterialId()));

            GrnItem grnItem = new GrnItem();
            grnItem.setGoodsReceivedNote(grn);
            grnItem.setMaterial(material);
            grnItem.setOrderedQuantity(itemDto.getOrderedQuantity());
            grnItem.setReceivedQuantity(itemDto.getReceivedQuantity());

            items.add(grnItem);
        }

        grnItemRepository.saveAll(items);
        grn.setItems(items);

        // Publish GrnCreatedEvent for automatic inventory update
        eventPublisher.publishEvent(new GrnCreatedEvent(this, grn));

        return GoodsReceivedNoteDtoConvertor.convertToDto(grn, fileStorageService);
    }

    @Transactional(readOnly = true)
    public GoodsReceivedNoteDto getGrnById(Long id) {
        GoodsReceivedNote grn = goodsReceivedNoteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GRN not found with id: " + id));
        return GoodsReceivedNoteDtoConvertor.convertToDto(grn, fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getAllGrns() {
        return goodsReceivedNoteRepository.findAll().stream()
                .map(grn -> GoodsReceivedNoteDtoConvertor.convertToDto(grn, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<GoodsReceivedNoteDto> getAllGrns(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "receivedOn"));
        return goodsReceivedNoteRepository.findAll(pageable)
                .map(grn -> GoodsReceivedNoteDtoConvertor.convertToDto(grn, fileStorageService));
    }

    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getGrnsByVendor(Long vendorId) {
        return goodsReceivedNoteRepository.findByVendorId(vendorId).stream()
                .map(grn -> GoodsReceivedNoteDtoConvertor.convertToDto(grn, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<GoodsReceivedNoteDto> getGrnsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return goodsReceivedNoteRepository.findByReceivedOnBetween(startDate, endDate).stream()
                .map(grn -> GoodsReceivedNoteDtoConvertor.convertToDto(grn, fileStorageService))
                .collect(Collectors.toList());
    }
}
