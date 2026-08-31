package org.tornotron.echno_backend.siteTransfer;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.siteTransfer.mapper.SiteTransferMapper;
import org.tornotron.echno_backend.common.events.SiteTransferCancelledEvent;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.common.events.SiteTransferReceivedEvent;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.dto.StatusTransitionDto;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCancellationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferReceiptDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferReceiptLineDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItemRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocationScope;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p><strong>How many legs a creation writes depends on whether it crosses a project
 * boundary.</strong> Within one project the material is handed between two stores on a site
 * whose storekeeper is accountable for it throughout, so both legs are written at creation and
 * the transfer is {@code COMPLETED} from the moment it exists: there is no arrival to confirm.
 * Between two projects there is a lorry, a road and a gap of hours or days, so only the
 * outbound leg is written, the transfer is {@code PENDING}, and the quantity is in transit until
 * somebody at the receiving site records what arrived through {@link #receiveSiteTransfer}.
 * Writing the inbound leg at creation asserted that stock had reached a site where nobody had
 * seen it, which is the claim that made a receiving-site count unexplainable.
 *
 * <p>The reporting consequence is worth stating rather than leaving to be discovered: an
 * organization-wide total of on-hand stock is short by everything in transit. That is the truth
 * about material on a lorry, and each line reports its own in-transit quantity so a report
 * summing stock across projects can show a labelled in-transit figure beside the total.
 */
@Service
public class SiteTransferService {

    /** The kind this module files its status trail under. */
    public static final String HISTORY_ENTITY_TYPE = "SITE_TRANSFER";

    /**
     * The state a cross-project transfer starts in, and the only one create accepts from a
     * payload. It is what the web client's transfer form already sends, and what
     * {@code echno-core} documents as the typical initial value. A transfer that stays inside
     * one project is written {@link SiteTransferStatus#COMPLETED} by the server instead, because
     * both its legs are posted at creation.
     */
    private static final SiteTransferStatus CREATION_STATUS = SiteTransferStatus.PENDING;

    /**
     * The states a transfer can be received from.
     *
     * <p>A {@code COMPLETED} transfer has had everything it sent recorded as arriving, and a
     * {@code CANCELLED} one has had its outbound leg reversed, so in both cases there is nothing
     * left in transit for a receipt to be about.
     */
    private static final Set<SiteTransferStatus> RECEIVABLE = EnumSet.of(
            SiteTransferStatus.PENDING, SiteTransferStatus.PARTIALLY_TRANSFERRED);

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
    private final SiteTransferReceiptReconciler receiptReconciler;
    private final CurrentEmployeeService currentEmployeeService;
    private final UserContextService userContextService;
    private final StatusTransitionRecorder statusTransitionRecorder;
    private final StatusTransitionRepository statusTransitionRepository;
    private final StatusTransitionMapper statusTransitionMapper;

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
                               TransactionRetryTemplate retryTemplate,
                               SiteTransferReceiptReconciler receiptReconciler,
                               CurrentEmployeeService currentEmployeeService,
                               UserContextService userContextService,
                               StatusTransitionRecorder statusTransitionRecorder,
                               StatusTransitionRepository statusTransitionRepository,
                               StatusTransitionMapper statusTransitionMapper) {
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
        this.receiptReconciler = receiptReconciler;
        this.currentEmployeeService = currentEmployeeService;
        this.userContextService = userContextService;
        this.statusTransitionRecorder = statusTransitionRecorder;
        this.statusTransitionRepository = statusTransitionRepository;
        this.statusTransitionMapper = statusTransitionMapper;
    }

    /**
     * Creates a site transfer with its items after checking sending-side stock.
     *
     * <p>A transfer between two projects starts {@link SiteTransferStatus#PENDING} with only its
     * outbound leg posted; one between two stores on a single project starts
     * {@link SiteTransferStatus#COMPLETED} with both legs posted and every line received in
     * full. The payload may ask for {@code PENDING} or for nothing at all, and any other value is
     * refused: see {@link #requireCreatableStatus}.
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
     * @throws InvalidRequestException if both sides name the same project and the same storage location, so nothing would move, or if a storage location belongs to a project other than the one it is used from, or if the payload asks for a status other than PENDING.
     * @throws org.tornotron.echno_backend.common.exception.InsufficientStockException if the sending side does not hold enough of any requested material.
     */
    public SiteTransferDto createSiteTransfer(SiteTransferCreationDto creationDto) {
        // Checked before the retry rather than inside it: a refused status is the caller's to
        // correct, and retrying it would only refuse it again.
        requireCreatableStatus(creationDto.getStatus());
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

        // A transfer that stays inside one project never leaves that site's custody, so both
        // legs are written at creation and there is nothing left to confirm. One that crosses a
        // project boundary is in transit until somebody at the far end says what arrived.
        boolean crossesProjects = SiteTransferReceiptReconciler.crossesProjectBoundary(transfer);
        transfer.setStatus(crossesProjects ? CREATION_STATUS : SiteTransferStatus.COMPLETED);
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
            // Within one project the inbound leg is written now, so the line records that the
            // whole quantity arrived. Across projects it stays null: nobody has confirmed
            // anything about this line, which is different from confirming that nothing came.
            item.setReceivedQuantity(crossesProjects ? null : itemDto.getSentQuantity());
            item.setRemarks(itemDto.getRemarks());
            item.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

            items.add(item);
        }

        siteTransferItemRepository.saveAll(items);
        transfer.setItems(items);

        statusTransitionRecorder.recordCreation(HISTORY_ENTITY_TYPE, transfer.getId(),
                transfer.getOrganization(), transfer.getStatus().name(),
                userContextService.getCurrentUser());

        // Publish SiteTransferCreatedEvent for automatic inventory update
        eventPublisher.publishEvent(new SiteTransferCreatedEvent(this, transfer));

        return siteTransferMapper.toDto(transfer);
    }

    /**
     * Refuses a transfer asked to be created in any state but {@link #CREATION_STATUS}.
     *
     * <p>The other states all say something happened after the transfer was issued: two of them
     * say the receiving site took delivery, and one says the transfer was abandoned and its stock
     * returned. Each is now reached by an endpoint that moves stock, so a create that copied the
     * payload's status would let a caller record their own receipt of a lorry nobody has seen, or
     * mark a transfer cancelled while its outbound leg still stands.
     *
     * <p>This is the rule {@code ProjectService.addProject} and
     * {@code PurchaseOrderService.createPurchaseOrder} apply: a create may not reach a state
     * whose transition carries a check or a movement. That leaves {@code PENDING}. The server
     * still writes {@code COMPLETED} itself for a transfer that stays inside one project, because
     * there both legs are posted at creation and nothing is outstanding; what is refused is the
     * caller claiming that state, not the state existing.
     *
     * @param status The status the create payload asked for, or null when it asked for none.
     * @throws InvalidRequestException if that status is anything but {@link #CREATION_STATUS}.
     */
    private void requireCreatableStatus(SiteTransferStatus status) {
        if (status != null && status != CREATION_STATUS) {
            throw new InvalidRequestException(
                    "A site transfer is issued as " + CREATION_STATUS + " and cannot be created as "
                            + status + ". Issue it first, then record what the receiving site took "
                            + "delivery of with POST /site-transfers/{id}/receive, which posts the "
                            + "stock that actually arrived and takes the person confirming it from "
                            + "the session; a transfer that never arrived is closed with POST "
                            + "/site-transfers/{id}/cancel, which returns the stock to the sender.");
        }
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
     * Refuses to set a transfer's status from a payload, and says where the status now comes from.
     *
     * <p>This endpoint used to assign whatever status it was handed and move no stock. That is
     * how a transfer could read {@code COMPLETED} with nothing confirmed and how it could read
     * {@code PENDING} with both legs already posted: the status was a label somebody typed rather
     * than a claim the ledger supported. Every state a transfer can hold now follows from a
     * movement, so there is no status left for a payload to set.
     *
     * <p>The route is kept rather than removed so an existing client gets an answer that names
     * the endpoint it wants instead of a 404 it has to guess at.
     *
     * @param id The id of the site transfer the caller tried to move.
     * @param status The status the caller asked for.
     * @throws ResourceNotFoundException if no site transfer with the given id exists in this organization.
     * @throws InvalidRequestException always, once the transfer has been found.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public void updateSiteTransferStatus(Long id, SiteTransferStatus status) {
        SiteTransfer transfer = siteTransferRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Site transfer with ID " + id + " was not found in this organization"));

        throw new InvalidRequestException(
                "Site transfer " + transfer.getTransferNumber() + " is " + transfer.getStatus()
                        + " and its status cannot be set to " + status + " from a payload. A status "
                        + "that says stock arrived must not be settable by a request that moves no "
                        + "stock. Record what the receiving site took delivery of with POST "
                        + "/site-transfers/{id}/receive, which posts the inbound movements and "
                        + "works the status out from them, or abandon a transfer that never "
                        + "arrived with POST /site-transfers/{id}/cancel, which returns the stock "
                        + "to the sending site.");
    }

    /**
     * Records what the receiving site took delivery of, posts the stock that arrived, and moves
     * the transfer's status to match.
     *
     * <p>This is the second step the two-step document exists for. Until it is called, a
     * cross-project transfer's quantity is in transit: off the sending site's balance, not yet on
     * the receiving site's, and visible as each line's in-transit quantity.
     *
     * <p>Who confirmed the delivery is taken from the session and never from the payload. A
     * receipt is read as that person's own statement that they saw the material, and an id off
     * the request body would be whatever the caller typed.
     *
     * <p>The lines are taken under a write lock, so two people confirming the same lorry at once
     * queue rather than each judging themselves against the figure that stood before the other.
     * The transaction is restarted on a serialization abort, as the create path is: the lock
     * queues the second caller rather than aborting it on CockroachDB, but the transfer row and
     * the status trail are written too, and a storekeeper standing at a gate should not be shown
     * a failure for a conflict they did not cause. The work is safe to run again from the start,
     * because the inbound movements are published as an event after the transaction commits,
     * which is the same shape the outbound leg has used since the document existed, so an attempt
     * that rolls back posts nothing.
     *
     * @param id The transfer being received.
     * @param receiptDto The quantities that arrived, and whether an over-receipt was acknowledged.
     * @return The transfer as it now stands.
     * @throws ResourceNotFoundException if no site transfer with the given id exists in this organization.
     * @throws InvalidRequestException if the transfer never went anywhere to be received from,
     *     if it is already completed or cancelled, if the payload names a line that is not on it,
     *     or if a line would be over-received without {@code allowOverReceipt}.
     * @throws org.springframework.security.access.AccessDeniedException if the caller has no
     *     employee record in this organization, so there would be nobody to record as having
     *     taken delivery.
     */
    public SiteTransferDto receiveSiteTransfer(Long id, SiteTransferReceiptDto receiptDto) {
        return retryTemplate.execute(
                "SiteTransferService.receiveSiteTransfer",
                () -> receiveSiteTransferInTransaction(id, receiptDto));
    }

    private SiteTransferDto receiveSiteTransferInTransaction(Long id, SiteTransferReceiptDto receiptDto) {
        SiteTransfer transfer = siteTransferRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Site transfer with ID " + id + " was not found in this organization"));

        requireReceivable(transfer);
        Employee receivedBy = currentEmployeeService.requireCurrentEmployee("record a site transfer as received");

        List<SiteTransferItem> lines = siteTransferItemRepository
                .lockBySiteTransferIdAndOrganizationId(transfer.getId(), TenantContext.getCurrentOrgId());
        transfer.setItems(lines);

        Map<Long, Integer> requested = new LinkedHashMap<>();
        for (SiteTransferReceiptLineDto line : receiptDto.getItems()) {
            // Merged rather than overwritten, so a payload naming the same line twice records
            // both quantities instead of silently dropping the first.
            requested.merge(line.getItemId(), line.getReceivedQuantity(), Integer::sum);
        }

        SiteTransferReceiptReconciler.ReceiptOutcome outcome = receiptReconciler.applyReceipt(
                transfer, lines, requested, Boolean.TRUE.equals(receiptDto.getAllowOverReceipt()),
                userContextService.getCurrentUser(), receiptDto.getRemarks());

        siteTransferItemRepository.saveAll(lines);
        if (outcome.movedTo() != null) {
            siteTransferRepository.save(transfer);
        }

        if (!outcome.received().isEmpty()) {
            eventPublisher.publishEvent(new SiteTransferReceivedEvent(
                    this, transfer, outcome.received(), receivedBy,
                    receiptDto.getReceivedOn() != null ? receiptDto.getReceivedOn() : LocalDateTime.now(),
                    receiptDto.getRemarks()));
        }

        return siteTransferMapper.toDto(transfer);
    }

    /**
     * Abandons a transfer that never arrived, returning its stock to the sending site.
     *
     * <p>Without this the two-step document would be strictly worse than the one-step one it
     * replaces: a transfer written off in transit would leave the sending project permanently
     * short, with no way back except a stock adjustment reading as an unexplained count variance.
     *
     * <p>Only a {@link SiteTransferStatus#PENDING} transfer can be cancelled. Once something has
     * been received, part of the material is standing at the far site and reversing the whole
     * outbound leg would claim it came back. What to do about the rest is a decision rather than
     * a reversal, and it belongs in a stock adjustment naming this transfer. A transfer inside
     * one project is never {@code PENDING}, so it cannot be cancelled either: it was complete the
     * moment it was written, and correcting it is an adjustment.
     *
     * @param id The transfer being cancelled.
     * @param cancellationDto Why it is being cancelled.
     * @return The transfer as it now stands.
     * @throws ResourceNotFoundException if no site transfer with the given id exists in this organization.
     * @throws InvalidRequestException if the transfer is in any state but {@code PENDING}.
     * @throws org.springframework.security.access.AccessDeniedException if the caller has no
     *     employee record in this organization.
     */
    public SiteTransferDto cancelSiteTransfer(Long id, SiteTransferCancellationDto cancellationDto) {
        return retryTemplate.execute(
                "SiteTransferService.cancelSiteTransfer",
                () -> cancelSiteTransferInTransaction(id, cancellationDto));
    }

    private SiteTransferDto cancelSiteTransferInTransaction(Long id, SiteTransferCancellationDto cancellationDto) {
        SiteTransfer transfer = siteTransferRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Site transfer with ID " + id + " was not found in this organization"));

        if (transfer.getStatus() != SiteTransferStatus.PENDING) {
            throw new InvalidRequestException(
                    "Site transfer " + transfer.getTransferNumber() + " is " + transfer.getStatus()
                            + " and cannot be cancelled. Cancelling returns the whole sent quantity "
                            + "to the sending site, which would be untrue of a transfer that has "
                            + "already had something received against it or that never left one "
                            + "site. Correct it with a stock adjustment naming this transfer.");
        }

        Employee cancelledBy = currentEmployeeService.requireCurrentEmployee("cancel a site transfer");

        List<SiteTransferItem> lines = siteTransferItemRepository
                .lockBySiteTransferIdAndOrganizationId(transfer.getId(), TenantContext.getCurrentOrgId());
        transfer.setItems(lines);

        SiteTransferStatus previous = transfer.getStatus();
        transfer.setStatus(SiteTransferStatus.CANCELLED);
        siteTransferRepository.save(transfer);
        statusTransitionRecorder.recordChange(
                HISTORY_ENTITY_TYPE, transfer.getId(), transfer.getOrganization(),
                previous.name(), SiteTransferStatus.CANCELLED.name(),
                userContextService.getCurrentUser(),
                "Cancelled in transit and the sent quantity returned to the sending site. "
                        + cancellationDto.getReason().trim());

        eventPublisher.publishEvent(new SiteTransferCancelledEvent(
                this, transfer, lines, cancelledBy, cancellationDto.getReason().trim()));

        return siteTransferMapper.toDto(transfer);
    }

    /**
     * Reads a site transfer's status trail.
     *
     * @param id The transfer whose trail to read.
     * @param pageNo Zero-based page index.
     * @param pageSize Entries per page.
     * @return A page of trail entries, newest first.
     * @throws ResourceNotFoundException if no site transfer with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public Page<StatusTransitionDto> getStatusHistory(Long id, int pageNo, int pageSize) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (siteTransferRepository.findByIdAndOrganization_Id(id, orgId).isEmpty()) {
            throw new ResourceNotFoundException(
                    "Site transfer with ID " + id + " was not found in this organization");
        }
        return statusTransitionRepository
                .findByEntityTypeAndEntityIdAndOrganization_IdOrderByOccurredAtDescIdDesc(
                        HISTORY_ENTITY_TYPE, id, orgId, PageRequest.of(pageNo, pageSize))
                .map(statusTransitionMapper::toDto);
    }

    /**
     * Refuses a receipt against a transfer that has nothing in transit to be received.
     *
     * <p>A transfer inside one project is refused first and by name, because the answer a
     * storekeeper needs there is not "it is already completed" but "there was never a lorry".
     */
    private void requireReceivable(SiteTransfer transfer) {
        if (!SiteTransferReceiptReconciler.crossesProjectBoundary(transfer)) {
            throw new InvalidRequestException(
                    "Site transfer " + transfer.getTransferNumber() + " moves stock between two "
                            + "storage locations on one project, so the material never left that "
                            + "site's custody and both of its inventory legs were written when it "
                            + "was created. There is no delivery to confirm. If the quantity that "
                            + "reached the receiving store was not the quantity sent, correct it "
                            + "with a stock adjustment.");
        }
        if (!RECEIVABLE.contains(transfer.getStatus())) {
            throw new InvalidRequestException(
                    "Site transfer " + transfer.getTransferNumber() + " is " + transfer.getStatus()
                            + " and has nothing left in transit to receive. A further delivery "
                            + "against it would be raising stock the transfer never sent; raise it "
                            + "as its own transfer, or correct the balance with a stock adjustment.");
        }
    }
}
