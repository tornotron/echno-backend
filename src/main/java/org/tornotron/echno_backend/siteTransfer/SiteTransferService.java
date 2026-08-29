package org.tornotron.echno_backend.siteTransfer;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.siteTransfer.mapper.SiteTransferMapper;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
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
import org.tornotron.echno_backend.storageLocation.StorageLocationScope;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Creates and queries site transfers that move stock between projects or storage locations.
 *
 * <p>Creating a transfer sums the requested quantity per material, checks that the sending
 * side holds enough (at the sending storage location when one is given, otherwise across the
 * sending project), persists the transfer and its items, then publishes a
 * {@link SiteTransferCreatedEvent} so the ledger draws stock down at the source and raises it
 * at the destination in the same transaction.
 *
 * <p>A transfer has to move stock from one balance row to another. Balances are held per
 * (material, project, storage location), with a location of none being its own row rather
 * than a project total, so the two sides are the same row exactly when they name the same
 * project and the same location. Such a transfer is refused: it moved nothing and would
 * leave an out and a matching in that cancel, sitting in the movement history looking like
 * real movement. Two locations inside one project are two rows, so a store-to-store move
 * within a project is a real transfer and is allowed.
 */
@Service
public class SiteTransferService {

    private final SiteTransferRepository siteTransferRepository;
    private final SiteTransferItemRepository siteTransferItemRepository;
    private final UserRepository userRepository;
    private final MaterialRepository materialRepository;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final SiteTransferMapper siteTransferMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final DocumentNumberAllocator documentNumberAllocator;
    private final TransactionRetryTemplate retryTemplate;

    public SiteTransferService(SiteTransferRepository siteTransferRepository,
                               SiteTransferItemRepository siteTransferItemRepository,
                               UserRepository userRepository,
                               MaterialRepository materialRepository,
                               InventoryService inventoryService,
                               ApplicationEventPublisher eventPublisher,
                               SiteTransferMapper siteTransferMapper,
                               TenantEntityHelper tenantEntityHelper,
                               EmployeeRepository employeeRepository,
                               ProjectRepository projectRepository,
                               StorageLocationRepository storageLocationRepository,
                               DocumentNumberAllocator documentNumberAllocator,
                               TransactionRetryTemplate retryTemplate) {
        this.siteTransferRepository = siteTransferRepository;
        this.siteTransferItemRepository = siteTransferItemRepository;
        this.userRepository = userRepository;
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
        this.siteTransferMapper = siteTransferMapper;
        this.eventPublisher = eventPublisher;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.documentNumberAllocator = documentNumberAllocator;
        this.retryTemplate = retryTemplate;
    }

    /**
     * Creates a site transfer with its items after checking sending-side stock.
     *
     * <p>Resolves the sending person, both projects and both optional storage locations,
     * refuses a pair of sides that resolve to the same balance row, then totals the requested
     * quantities per material and validates them against the sending side. The transfer
     * number is allocated only once all of that has passed, so a rejected request does not
     * spend one. After saving the transfer and its items, a {@link SiteTransferCreatedEvent}
     * is published so inventory moves.
     *
     * <p>The transaction is restarted on a serialization abort, and also on a unique
     * violation: the counter behind the transfer number is the row two concurrent creates
     * contend on, and a fresh attempt allocates the next number rather than reporting a
     * collision the user did not cause. The inventory listener runs after commit, so only the
     * attempt that commits moves stock.
     *
     * @param creationDto The transfer header fields and the list of items to move.
     * @return The created site transfer as a DTO.
     * @throws ResourceNotFoundException if the sending person, either project, a storage location, or a line's material is not found in this organization.
     * @throws InvalidRequestException if both sides name the same project and the same storage location, so nothing would move, or if a storage location belongs to a project other than the one it is used from.
     * @throws org.tornotron.echno_backend.common.exception.InsufficientStockException if the sending side does not hold enough of any requested material.
     */
    public SiteTransferDto createSiteTransfer(SiteTransferCreationDto creationDto) {
        return retryTemplate.execute(
                "SiteTransferService.createSiteTransfer",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> createSiteTransferInTransaction(creationDto));
    }

    private SiteTransferDto createSiteTransferInTransaction(SiteTransferCreationDto creationDto) {
        Employee sendingPerson = employeeRepository.findByIdAndOrganizationId(creationDto.getSendingPerson(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Sending person (employee) with ID " + creationDto.getSendingPerson() + " was not found in this organization"));

        // Validate sending project
        Project sendingProject = projectRepository.findByIdAndOrganization_Id(creationDto.getSendingProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Sending project with ID " + creationDto.getSendingProjectId() + " was not found in this organization"));

        // Validate receiving project
        Project receivingProject = projectRepository.findByIdAndOrganization_Id(creationDto.getReceivingProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving project with ID " + creationDto.getReceivingProjectId() + " was not found in this organization"));

        // Resolve both storage locations here, before anything is decided. They are what say
        // which balance rows this transfer draws from and credits, and that is needed twice
        // over: to refuse a transfer whose two sides are the same row, and to know which row
        // the stock check has to read.
        StorageLocation sendingLocation = resolveStorageLocation(
                creationDto.getSendingStorageLocationId(), sendingProject, "Sending");
        StorageLocation receivingLocation = resolveStorageLocation(
                creationDto.getReceivingStorageLocationId(), receivingProject, "Receiving");

        requireTheTransferMovesStock(sendingProject, sendingLocation, receivingProject, receivingLocation);

        // Check the sending side for every item, against the balance row the transfer will
        // actually debit. Named location means that location's row; no location means the
        // sending project's unlocated row, which is what the listener goes on to write. The
        // project total would pass a draw against stock sitting in storage locations the
        // debit never reaches, and take the unlocated row negative.
        Map<Long, Double> requiredQuantities = new HashMap<>();
        for (SiteTransferItemDto itemDto : creationDto.getItems()) {
            requiredQuantities.merge(itemDto.getMaterialId(), itemDto.getSentQuantity().doubleValue(), Double::sum);
        }
        if (creationDto.getSendingStorageLocationId() != null) {
            inventoryService.validateSufficientStockForMultipleItemsAtLocation(
                    requiredQuantities, sendingProject.getId(), creationDto.getSendingStorageLocationId());
        } else {
            inventoryService.validateSufficientUnlocatedStockForMultipleItems(
                    requiredQuantities, sendingProject.getId());
        }

        // Create site transfer
        SiteTransfer transfer = new SiteTransfer();
        transfer.setTransferNumber(
                documentNumberAllocator.allocate(DocumentNumberType.SITE_TRANSFER, TenantContext.getCurrentOrgId()));
        transfer.setIssueDate(creationDto.getIssueDate());
        transfer.setSendingPerson(sendingPerson);
        transfer.setSendingProject(sendingProject);
        transfer.setReceivingProject(receivingProject);
        transfer.setSendingStorageLocation(sendingLocation);
        transfer.setReceivingStorageLocation(receivingLocation);

        transfer.setStatus(SiteTransferStatus.valueOf(creationDto.getStatus()));
        transfer.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Save transfer first
        transfer = siteTransferRepository.save(transfer);

        // Create transfer items
        List<SiteTransferItem> items = new ArrayList<>();
        for (SiteTransferItemDto itemDto : creationDto.getItems()) {
            Material material = materialRepository.findByIdAndOrganization_Id(itemDto.getMaterialId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + itemDto.getMaterialId() + " was not found in this organization"));

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

        return siteTransferMapper.toDto(transfer);
    }

    /**
     * Resolves one side's optional storage location and checks it may be used from that side's project.
     *
     * @param storageLocationId The requested storage location id, or null when the side names no location.
     * @param project The project the location will be booked against.
     * @param side Either Sending or Receiving, used to say which end of the transfer a message is about.
     * @return The resolved storage location, or null when none was requested.
     * @throws ResourceNotFoundException if the id names no location in this organization.
     * @throws InvalidRequestException if the location belongs to a different project.
     */
    private StorageLocation resolveStorageLocation(Long storageLocationId, Project project, String side) {
        if (storageLocationId == null) {
            return null;
        }
        StorageLocation location = storageLocationRepository
                .findByIdAndOrganization_Id(storageLocationId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        side + " storage location with ID " + storageLocationId + " was not found in this organization"));
        // A location on another project names a balance row this side can never reach, so the
        // transfer would be written against a row that cannot exist. Same rule as consumption.
        StorageLocationScope.requireUsableFromProject(location, project.getId());
        return location;
    }

    /**
     * Refuses a transfer whose two sides are the same balance row, so nothing would move.
     *
     * <p>Balances are held per (material, project, storage location), and a movement with no
     * location writes the project's unlocated row rather than a project total. The two sides
     * are therefore the same row exactly when the projects match and the locations match,
     * counting no location on both sides as a match. Everything else moves stock between two
     * distinct rows and is a real transfer, including two locations inside one project and
     * one organisation-level store used from two different projects.
     *
     * @param sendingProject The project stock is drawn from.
     * @param sendingLocation The location stock is drawn from, or null for the project's unlocated balance.
     * @param receivingProject The project stock is credited to.
     * @param receivingLocation The location stock is credited to, or null for the project's unlocated balance.
     * @throws InvalidRequestException if both sides resolve to the same balance row.
     */
    private void requireTheTransferMovesStock(Project sendingProject, StorageLocation sendingLocation,
                                              Project receivingProject, StorageLocation receivingLocation) {
        if (!Objects.equals(sendingProject.getId(), receivingProject.getId())) {
            return;
        }
        Long sendingLocationId = sendingLocation != null ? sendingLocation.getId() : null;
        Long receivingLocationId = receivingLocation != null ? receivingLocation.getId() : null;
        if (!Objects.equals(sendingLocationId, receivingLocationId)) {
            return;
        }

        String where = sendingLocationId != null
                ? "storage location with ID " + sendingLocationId + " in project with ID " + sendingProject.getId()
                : "project with ID " + sendingProject.getId() + ", against no storage location";
        throw new InvalidRequestException(
                "This transfer sends stock from " + where + " straight back to the same place, so nothing "
                        + "moves and the movement history would carry an out and a matching in that cancel. "
                        + "To move stock between two stores on one project, name a different receiving "
                        + "storage location; to move it to another site, name a different receiving project; "
                        + "to correct a balance that is wrong, raise a stock adjustment instead.");
    }

    /**
     * Retrieves a single site transfer by its id within the current tenant.
     *
     * @param id The id of the site transfer to retrieve.
     * @return The site transfer as a DTO.
     * @throws ResourceNotFoundException if no site transfer with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public SiteTransferDto getSiteTransferById(Long id) {
        SiteTransfer transfer = siteTransferRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Site transfer with ID " + id + " was not found in this organization"));
        return siteTransferMapper.toDto(transfer);
    }


    /**
     * Retrieves site transfers one page at a time, newest first.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of transfers per page.
     * @return A page of site transfer DTOs ordered by issue date descending.
     */
    @Transactional(readOnly = true)
    public Page<SiteTransferDto> getAllSiteTransfers(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "issueDate"));
        return siteTransferRepository.findAll(pageable)
                .map(transfer -> siteTransferMapper.toDto(transfer));
    }

    /**
     * Lists site transfers in a given status.
     *
     * @param status The status to filter by.
     * @return The matching site transfers as DTOs.
     */
    @Transactional(readOnly = true)
    public List<SiteTransferDto> getSiteTransfersByStatus(SiteTransferStatus status) {
        return siteTransferRepository.findByStatus(status).stream()
                .map(transfer -> siteTransferMapper.toDto(transfer))
                .collect(Collectors.toList());
    }

    /**
     * Lists site transfers sent from a given project.
     *
     * @param projectId The sending project to filter by.
     * @return The matching site transfers as DTOs.
     */
    @Transactional(readOnly = true)
    public List<SiteTransferDto> getSiteTransfersBySendingProject(Long projectId) {
        return siteTransferRepository.findBySendingProjectId(projectId).stream()
                .map(transfer -> siteTransferMapper.toDto(transfer))
                .collect(Collectors.toList());
    }

    /**
     * Lists site transfers received by a given project.
     *
     * @param projectId The receiving project to filter by.
     * @return The matching site transfers as DTOs.
     */
    @Transactional(readOnly = true)
    public List<SiteTransferDto> getSiteTransfersByReceivingProject(Long projectId) {
        return siteTransferRepository.findByReceivingProjectId(projectId).stream()
                .map(transfer -> siteTransferMapper.toDto(transfer))
                .collect(Collectors.toList());
    }

    /**
     * Sets the status of a site transfer.
     *
     * @param id The id of the site transfer to update.
     * @param status The new status.
     * @throws ResourceNotFoundException if no site transfer with the given id exists in this organization.
     */
    @Transactional
    public void updateSiteTransferStatus(Long id, SiteTransferStatus status) {
        SiteTransfer transfer = siteTransferRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Site transfer with ID " + id + " was not found in this organization"));

        transfer.setStatus(status);
        siteTransferRepository.save(transfer);
    }
}
