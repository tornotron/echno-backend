package org.tornotron.echno_backend.common.events.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.events.MaterialConsumedEvent;
import org.tornotron.echno_backend.common.events.SiteTransferCancelledEvent;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.common.events.SiteTransferReceivedEvent;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransfer.SiteTransferReceiptReconciler;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
public class InventoryEventListener {

    private static final Logger logger = LoggerFactory.getLogger(InventoryEventListener.class);

    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final InventoryService inventoryService;

    public InventoryEventListener(InventoryTransactionRepository inventoryTransactionRepository,
                                   InventoryService inventoryService) {
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.inventoryService = inventoryService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleGrnCreated(GrnCreatedEvent event) {
        GoodsReceivedNote grn = event.getGoodsReceivedNote();
        logger.info("Handling GRN created event for GRN number: {}", grn.getGrnNumber());

        for (GrnItem item : grn.getItems()) {
            // Read the balance this receipt actually credits. With no storage location that is
            // the project's unlocated row, not the project total.
            Double openingStock;
            if (grn.getStorageLocation() != null) {
                openingStock = inventoryService.getStockAtLocation(
                        item.getMaterial().getId(), grn.getProject().getId(),
                        grn.getStorageLocation().getId());
            } else {
                openingStock = inventoryService.findUnlocatedStock(
                        item.getMaterial().getId(), grn.getProject().getId()).orElse(0.0);
            }
            Double quantityChanged = item.getReceivedQuantity().doubleValue();
            Double closingStock = openingStock + quantityChanged;
            BigDecimal unitCost = item.getUnitCost();

            InventoryTransaction transaction = new InventoryTransaction();
            transaction.setTransactionDate(grn.getReceivedOn() != null ? grn.getReceivedOn() : LocalDateTime.now());
            transaction.setMaterial(item.getMaterial());
            transaction.setOpeningStock(openingStock);
            transaction.setQuantityChanged(quantityChanged);
            transaction.setClosingStock(closingStock);
            transaction.setTransactionType(InventoryTransactionType.GRN);
            transaction.setReferenceNumber(grn.getGrnNumber());
            transaction.setRemarks("GRN received from " + (grn.getVendor() != null ? grn.getVendor().getVendorName() : "vendor"));
            transaction.setCreatedBy(grn.getReceivedBy());
            transaction.setProject(grn.getProject());
            transaction.setStorageLocation(grn.getStorageLocation());
            transaction.setOrganization(grn.getOrganization());
            transaction.setUnitCost(unitCost);

            inventoryTransactionRepository.save(transaction);

            inventoryService.updateCurrentStock(
                    item.getMaterial(), grn.getProject(), grn.getStorageLocation(),
                    grn.getOrganization(), quantityChanged, unitCost);

            logger.debug("Created inventory transaction for material ID: {}, project ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), grn.getProject().getId(), quantityChanged, closingStock);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMaterialConsumed(MaterialConsumedEvent event) {
        MaterialConsumption consumption = event.getMaterialConsumption();
        logger.info("Handling material consumed event for consumption ID: {}", consumption.getId());

        // Read the balance this movement actually draws down. With no storage location that
        // is the project's unlocated row, not the project total: the total would put an
        // opening and closing figure on the ledger entry that the row it moves never held.
        Double openingStock;
        if (consumption.getStorageLocation() != null) {
            openingStock = inventoryService.getStockAtLocation(
                    consumption.getMaterial().getId(), consumption.getProject().getId(),
                    consumption.getStorageLocation().getId());
        } else {
            openingStock = inventoryService.findUnlocatedStock(
                    consumption.getMaterial().getId(), consumption.getProject().getId()).orElse(0.0);
        }
        Double quantityChanged = -consumption.getQuantity().doubleValue();
        Double closingStock = openingStock + quantityChanged;

        InventoryTransaction transaction = new InventoryTransaction();
        transaction.setTransactionDate(consumption.getConsumptionDate() != null ? consumption.getConsumptionDate() : LocalDateTime.now());
        transaction.setMaterial(consumption.getMaterial());
        transaction.setOpeningStock(openingStock);
        transaction.setQuantityChanged(quantityChanged);
        transaction.setClosingStock(closingStock);
        transaction.setTransactionType(InventoryTransactionType.USE);
        transaction.setReferenceNumber("MC-" + consumption.getId());
        transaction.setRemarks("Material consumed - " + consumption.getConsumptionType() +
                (consumption.getDetails() != null ? ": " + consumption.getDetails() : ""));
        transaction.setCreatedBy(consumption.getCreatedBy());
        transaction.setProject(consumption.getProject());
        transaction.setStorageLocation(consumption.getStorageLocation());
        transaction.setOrganization(consumption.getOrganization());
        transaction.setTask(consumption.getTask());

        inventoryTransactionRepository.save(transaction);

        inventoryService.updateCurrentStock(
                consumption.getMaterial(), consumption.getProject(), consumption.getStorageLocation(),
                consumption.getOrganization(), quantityChanged, null);

        logger.debug("Created inventory transaction for material ID: {}, project ID: {}, quantity: {}, closing stock: {}",
                consumption.getMaterial().getId(), consumption.getProject().getId(), quantityChanged, closingStock);
    }

    /**
     * Posts the legs a newly created site transfer writes.
     *
     * <p>The outbound leg is always written: the material has left the sending balance whichever
     * kind of transfer this is. The inbound leg is written here only when the transfer stays
     * inside one project, where the material is handed between two stores on a site whose
     * storekeeper is accountable for it throughout and there is no window in which nobody is.
     *
     * <p>Across a project boundary the inbound leg waits for
     * {@link #handleSiteTransferReceived}. Writing it here asserted that stock had arrived at a
     * site where nobody had seen the lorry, which is the claim that made a receiving-site count
     * unexplainable: the balance said twenty bags, the yard held none, and the ledger offered a
     * TRANSFER_IN whose closing figure nobody could reconcile. Between the two legs the quantity
     * is in transit, which is where it actually is.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSiteTransferCreated(SiteTransferCreatedEvent event) {
        SiteTransfer transfer = event.getSiteTransfer();
        boolean crossesProjects = SiteTransferReceiptReconciler.crossesProjectBoundary(transfer);
        logger.info("Handling site transfer created event for transfer number: {} ({})",
                transfer.getTransferNumber(),
                crossesProjects ? "outbound leg only, awaiting receipt" : "both legs, within one project");

        for (SiteTransferItem item : transfer.getItems()) {
            BigDecimal avgCost = writeTransferOut(transfer, item, item.getSentQuantity().doubleValue());

            if (!crossesProjects) {
                writeTransferIn(transfer, item, item.getSentQuantity().doubleValue(), avgCost,
                        transfer.getIssueDate(), transfer.getSendingPerson(),
                        "Transfer in from " + transfer.getSendingProject().getProjectName()
                                + " by " + personName(transfer.getSendingPerson())
                                + (item.getRemarks() != null ? " - " + item.getRemarks() : ""));
            }
        }
    }

    /**
     * Raises stock at the receiving site for what somebody there confirmed had arrived.
     *
     * <p>The arrival is valued at the unit cost the outbound leg carried, not at the sending
     * site's average cost as it stands now. By the time a lorry is unloaded the sending site may
     * have moved or run out of the material entirely, and pricing the arrival off its current
     * balance would value it at zero and silently destroy the stock value that left.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSiteTransferReceived(SiteTransferReceivedEvent event) {
        SiteTransfer transfer = event.getSiteTransfer();
        logger.info("Handling site transfer received event for transfer number: {}, {} line(s)",
                transfer.getTransferNumber(), event.getReceivedLines().size());

        for (SiteTransferReceivedEvent.ReceivedLine line : event.getReceivedLines()) {
            SiteTransferItem item = line.item();
            BigDecimal avgCost = costThatLeftTheSendingSite(transfer, item);
            writeTransferIn(transfer, item, (double) line.quantity(), avgCost,
                    event.getReceivedOn(), event.getReceivedBy(),
                    "Transfer in from " + transfer.getSendingProject().getProjectName()
                            + ", received by " + personName(event.getReceivedBy())
                            + (event.getRemarks() != null && !event.getRemarks().isBlank()
                                    ? " - " + event.getRemarks() : ""));
        }
    }

    /**
     * Returns a cancelled transfer's stock to the sending site.
     *
     * <p>A cancellation is only reachable while nothing has been received, so the whole sent
     * quantity is still in transit and the whole of it comes back. Written as a TRANSFER_IN
     * against the sending balance rather than as a new movement shape: the stock really is
     * arriving back at that row, and the remark says why.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSiteTransferCancelled(SiteTransferCancelledEvent event) {
        SiteTransfer transfer = event.getSiteTransfer();
        logger.info("Handling site transfer cancelled event for transfer number: {}", transfer.getTransferNumber());

        for (SiteTransferItem item : event.getItems()) {
            BigDecimal avgCost = costThatLeftTheSendingSite(transfer, item);
            Double quantityChanged = item.getSentQuantity().doubleValue();
            Double openingStock = openingStockAt(item, transfer.getSendingProject(),
                    transfer.getSendingStorageLocation());

            InventoryTransaction reversal = new InventoryTransaction();
            reversal.setTransactionDate(LocalDateTime.now());
            reversal.setMaterial(item.getMaterial());
            reversal.setOpeningStock(openingStock);
            reversal.setQuantityChanged(quantityChanged);
            reversal.setClosingStock(openingStock + quantityChanged);
            reversal.setTransactionType(InventoryTransactionType.TRANSFER_IN);
            reversal.setReferenceNumber(transfer.getTransferNumber());
            reversal.setRemarks("Transfer cancelled in transit and returned to "
                    + transfer.getSendingProject().getProjectName()
                    + " by " + personName(event.getCancelledBy())
                    + (event.getReason() != null ? " - " + event.getReason() : ""));
            reversal.setCreatedBy(event.getCancelledBy());
            reversal.setProject(transfer.getSendingProject());
            reversal.setStorageLocation(transfer.getSendingStorageLocation());
            reversal.setOrganization(transfer.getOrganization());
            reversal.setUnitCost(avgCost);

            inventoryTransactionRepository.save(reversal);

            inventoryService.updateCurrentStock(
                    item.getMaterial(), transfer.getSendingProject(), transfer.getSendingStorageLocation(),
                    transfer.getOrganization(), quantityChanged, avgCost);

            logger.debug("Reversed TRANSFER_OUT for material ID: {}, sending project ID: {}, quantity: {}",
                    item.getMaterial().getId(), transfer.getSendingProject().getId(), quantityChanged);
        }
    }

    /**
     * Draws a quantity down at the sending balance and returns the unit cost it left at.
     *
     * <p>The average cost is read before the balance is updated, so it prices the stock that is
     * leaving rather than what remains behind it.
     */
    private BigDecimal writeTransferOut(SiteTransfer transfer, SiteTransferItem item, Double quantity) {
        // Read the balance this movement actually debits. With no sending storage location
        // that is the sending project's unlocated row, not the project total: the total
        // would put an opening and closing figure on the ledger entry that the row it
        // moves never held, and it is a different quantity from the one updateCurrentStock
        // goes on to write.
        Double openingStock = openingStockAt(item, transfer.getSendingProject(),
                transfer.getSendingStorageLocation());
        Double quantityChanged = -quantity;

        // Compute avg cost from sending side before the outbound update
        BigDecimal avgCost = inventoryService.getAverageCost(
                item.getMaterial().getId(), transfer.getSendingProject().getId(),
                transfer.getSendingStorageLocation() != null ? transfer.getSendingStorageLocation().getId() : null);

        InventoryTransaction outTransaction = new InventoryTransaction();
        outTransaction.setTransactionDate(transfer.getIssueDate() != null ? transfer.getIssueDate() : LocalDateTime.now());
        outTransaction.setMaterial(item.getMaterial());
        outTransaction.setOpeningStock(openingStock);
        outTransaction.setQuantityChanged(quantityChanged);
        outTransaction.setClosingStock(openingStock + quantityChanged);
        outTransaction.setTransactionType(InventoryTransactionType.TRANSFER_OUT);
        outTransaction.setReferenceNumber(transfer.getTransferNumber());
        outTransaction.setRemarks("Transfer out to " + transfer.getReceivingProject().getProjectName() +
                " by " + personName(transfer.getSendingPerson()) +
                (item.getRemarks() != null ? " - " + item.getRemarks() : ""));
        outTransaction.setCreatedBy(transfer.getSendingPerson());
        outTransaction.setProject(transfer.getSendingProject());
        outTransaction.setStorageLocation(transfer.getSendingStorageLocation());
        outTransaction.setOrganization(transfer.getOrganization());
        outTransaction.setUnitCost(avgCost);

        inventoryTransactionRepository.save(outTransaction);

        inventoryService.updateCurrentStock(
                item.getMaterial(), transfer.getSendingProject(), transfer.getSendingStorageLocation(),
                transfer.getOrganization(), quantityChanged, null);

        logger.debug("Created TRANSFER_OUT transaction for material ID: {}, sending project ID: {}, quantity: {}",
                item.getMaterial().getId(), transfer.getSendingProject().getId(), quantityChanged);
        return avgCost;
    }

    /** Raises a quantity at the receiving balance, carrying the cost it left the sender at. */
    private void writeTransferIn(SiteTransfer transfer, SiteTransferItem item, Double quantity,
                                 BigDecimal avgCost, LocalDateTime movementDate,
                                 Employee createdBy, String remarks) {
        // The row credited with no location is the receiving project's unlocated row.
        Double openingStock = openingStockAt(item, transfer.getReceivingProject(),
                transfer.getReceivingStorageLocation());

        InventoryTransaction inTransaction = new InventoryTransaction();
        inTransaction.setTransactionDate(movementDate != null ? movementDate : LocalDateTime.now());
        inTransaction.setMaterial(item.getMaterial());
        inTransaction.setOpeningStock(openingStock);
        inTransaction.setQuantityChanged(quantity);
        inTransaction.setClosingStock(openingStock + quantity);
        inTransaction.setTransactionType(InventoryTransactionType.TRANSFER_IN);
        inTransaction.setReferenceNumber(transfer.getTransferNumber());
        inTransaction.setRemarks(remarks);
        inTransaction.setCreatedBy(createdBy);
        inTransaction.setProject(transfer.getReceivingProject());
        inTransaction.setStorageLocation(transfer.getReceivingStorageLocation());
        inTransaction.setOrganization(transfer.getOrganization());
        inTransaction.setUnitCost(avgCost);

        inventoryTransactionRepository.save(inTransaction);

        inventoryService.updateCurrentStock(
                item.getMaterial(), transfer.getReceivingProject(), transfer.getReceivingStorageLocation(),
                transfer.getOrganization(), quantity, avgCost);

        logger.debug("Created TRANSFER_IN transaction for material ID: {}, receiving project ID: {}, quantity: {}",
                item.getMaterial().getId(), transfer.getReceivingProject().getId(), quantity);
    }

    /**
     * The unit cost this transfer's outbound leg carried for a material.
     *
     * <p>Read back off the ledger rather than recomputed, because the sending balance has moved
     * on since the lorry left and may hold nothing at all. Falls back to the sending balance's
     * current average cost when no outbound movement is found, which is the shape a transfer
     * created before this ledger read existed would have.
     *
     * <p>The organization comes off the transfer rather than off the ambient tenant context, the
     * same rule the status trail follows: the movement being looked for belongs to this document,
     * so the document is what says whose it is.
     */
    private BigDecimal costThatLeftTheSendingSite(SiteTransfer transfer, SiteTransferItem item) {
        return inventoryTransactionRepository
                .findUnitCostsForReference(transfer.getTransferNumber(), item.getMaterial().getId(),
                        InventoryTransactionType.TRANSFER_OUT, transfer.getOrganization().getId(),
                        PageRequest.of(0, 1))
                .stream().findFirst()
                .orElseGet(() -> inventoryService.getAverageCost(
                        item.getMaterial().getId(), transfer.getSendingProject().getId(),
                        transfer.getSendingStorageLocation() != null
                                ? transfer.getSendingStorageLocation().getId() : null));
    }

    /** The balance row a movement touches: the located row, or the project's unlocated one. */
    private Double openingStockAt(SiteTransferItem item, Project project, StorageLocation location) {
        if (location != null) {
            return inventoryService.getStockAtLocation(item.getMaterial().getId(), project.getId(), location.getId());
        }
        return inventoryService.findUnlocatedStock(item.getMaterial().getId(), project.getId()).orElse(0.0);
    }

    private static String personName(Employee employee) {
        return employee != null && employee.getEmployeeName() != null ? employee.getEmployeeName() : "employee";
    }
}
