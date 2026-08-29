package org.tornotron.echno_backend.common.events.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.events.MaterialConsumedEvent;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSiteTransferCreated(SiteTransferCreatedEvent event) {
        SiteTransfer transfer = event.getSiteTransfer();
        logger.info("Handling site transfer created event for transfer number: {}", transfer.getTransferNumber());

        for (SiteTransferItem item : transfer.getItems()) {
            // TRANSFER_OUT: Stock decreases at sending project.
            // Read the balance this movement actually debits. With no sending storage location
            // that is the sending project's unlocated row, not the project total: the total
            // would put an opening and closing figure on the ledger entry that the row it
            // moves never held, and it is a different quantity from the one updateCurrentStock
            // goes on to write.
            Double sendingOpeningStock;
            if (transfer.getSendingStorageLocation() != null) {
                sendingOpeningStock = inventoryService.getStockAtLocation(
                        item.getMaterial().getId(), transfer.getSendingProject().getId(),
                        transfer.getSendingStorageLocation().getId());
            } else {
                sendingOpeningStock = inventoryService.findUnlocatedStock(
                        item.getMaterial().getId(), transfer.getSendingProject().getId()).orElse(0.0);
            }
            Double sendingQuantityChanged = -item.getSentQuantity().doubleValue();
            Double sendingClosingStock = sendingOpeningStock + sendingQuantityChanged;

            // Compute avg cost from sending side before the outbound update
            BigDecimal avgCost = inventoryService.getAverageCost(
                    item.getMaterial().getId(), transfer.getSendingProject().getId(),
                    transfer.getSendingStorageLocation() != null ? transfer.getSendingStorageLocation().getId() : null);

            InventoryTransaction outTransaction = new InventoryTransaction();
            outTransaction.setTransactionDate(transfer.getIssueDate() != null ? transfer.getIssueDate() : LocalDateTime.now());
            outTransaction.setMaterial(item.getMaterial());
            outTransaction.setOpeningStock(sendingOpeningStock);
            outTransaction.setQuantityChanged(sendingQuantityChanged);
            outTransaction.setClosingStock(sendingClosingStock);
            outTransaction.setTransactionType(InventoryTransactionType.TRANSFER_OUT);
            outTransaction.setReferenceNumber(transfer.getTransferNumber());
            outTransaction.setRemarks("Transfer out to " + transfer.getReceivingProject().getProjectName() +
                    " by " + (transfer.getSendingPerson() != null ? transfer.getSendingPerson().getEmployeeName() : "employee") +
                    (item.getRemarks() != null ? " - " + item.getRemarks() : ""));
            outTransaction.setCreatedBy(transfer.getSendingPerson());
            outTransaction.setProject(transfer.getSendingProject());
            outTransaction.setStorageLocation(transfer.getSendingStorageLocation());
            outTransaction.setOrganization(transfer.getOrganization());
            outTransaction.setUnitCost(avgCost);

            inventoryTransactionRepository.save(outTransaction);

            inventoryService.updateCurrentStock(
                    item.getMaterial(), transfer.getSendingProject(), transfer.getSendingStorageLocation(),
                    transfer.getOrganization(), sendingQuantityChanged, null);

            logger.debug("Created TRANSFER_OUT transaction for material ID: {}, sending project ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), transfer.getSendingProject().getId(), sendingQuantityChanged, sendingClosingStock);

            // TRANSFER_IN: Stock increases at receiving project (carries avg cost from sender)
            // Same on the receiving side: the row credited with no location is the receiving
            // project's unlocated row.
            Double receivingOpeningStock;
            if (transfer.getReceivingStorageLocation() != null) {
                receivingOpeningStock = inventoryService.getStockAtLocation(
                        item.getMaterial().getId(), transfer.getReceivingProject().getId(),
                        transfer.getReceivingStorageLocation().getId());
            } else {
                receivingOpeningStock = inventoryService.findUnlocatedStock(
                        item.getMaterial().getId(), transfer.getReceivingProject().getId()).orElse(0.0);
            }
            Double receivingQuantityChanged = item.getSentQuantity().doubleValue();
            Double receivingClosingStock = receivingOpeningStock + receivingQuantityChanged;

            InventoryTransaction inTransaction = new InventoryTransaction();
            inTransaction.setTransactionDate(transfer.getIssueDate() != null ? transfer.getIssueDate() : LocalDateTime.now());
            inTransaction.setMaterial(item.getMaterial());
            inTransaction.setOpeningStock(receivingOpeningStock);
            inTransaction.setQuantityChanged(receivingQuantityChanged);
            inTransaction.setClosingStock(receivingClosingStock);
            inTransaction.setTransactionType(InventoryTransactionType.TRANSFER_IN);
            inTransaction.setReferenceNumber(transfer.getTransferNumber());
            inTransaction.setRemarks("Transfer in from " + transfer.getSendingProject().getProjectName() +
                    " by " + (transfer.getSendingPerson() != null ? transfer.getSendingPerson().getEmployeeName() : "employee") +
                    (item.getRemarks() != null ? " - " + item.getRemarks() : ""));
            inTransaction.setCreatedBy(transfer.getSendingPerson());
            inTransaction.setProject(transfer.getReceivingProject());
            inTransaction.setStorageLocation(transfer.getReceivingStorageLocation());
            inTransaction.setOrganization(transfer.getOrganization());
            inTransaction.setUnitCost(avgCost);

            inventoryTransactionRepository.save(inTransaction);

            inventoryService.updateCurrentStock(
                    item.getMaterial(), transfer.getReceivingProject(), transfer.getReceivingStorageLocation(),
                    transfer.getOrganization(), receivingQuantityChanged, avgCost);

            logger.debug("Created TRANSFER_IN transaction for material ID: {}, receiving project ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), transfer.getReceivingProject().getId(), receivingQuantityChanged, receivingClosingStock);
        }
    }
}
