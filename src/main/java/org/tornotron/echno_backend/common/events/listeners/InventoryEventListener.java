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
            // Use storage-location-level stock when a storage location is specified
            Integer openingStock;
            if (grn.getStorageLocation() != null) {
                openingStock = inventoryService.getStockAtLocation(
                        item.getMaterial().getId(), grn.getProject().getId(),
                        grn.getStorageLocation().getId());
            } else {
                openingStock = inventoryService.getCurrentStock(item.getMaterial().getId(), grn.getProject().getId());
            }
            Integer quantityChanged = item.getReceivedQuantity();
            Integer closingStock = openingStock + quantityChanged;

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

            inventoryTransactionRepository.save(transaction);
            logger.debug("Created inventory transaction for material ID: {}, project ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), grn.getProject().getId(), quantityChanged, closingStock);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMaterialConsumed(MaterialConsumedEvent event) {
        MaterialConsumption consumption = event.getMaterialConsumption();
        logger.info("Handling material consumed event for consumption ID: {}", consumption.getId());

        // Use storage-location-level stock when a storage location is specified
        Integer openingStock;
        if (consumption.getStorageLocation() != null) {
            openingStock = inventoryService.getStockAtLocation(
                    consumption.getMaterial().getId(), consumption.getProject().getId(),
                    consumption.getStorageLocation().getId());
        } else {
            openingStock = inventoryService.getCurrentStock(consumption.getMaterial().getId(), consumption.getProject().getId());
        }
        Integer quantityChanged = -consumption.getQuantity();
        Integer closingStock = openingStock + quantityChanged;

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

        inventoryTransactionRepository.save(transaction);
        logger.debug("Created inventory transaction for material ID: {}, project ID: {}, quantity: {}, closing stock: {}",
                consumption.getMaterial().getId(), consumption.getProject().getId(), quantityChanged, closingStock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSiteTransferCreated(SiteTransferCreatedEvent event) {
        SiteTransfer transfer = event.getSiteTransfer();
        logger.info("Handling site transfer created event for transfer number: {}", transfer.getTransferNumber());

        for (SiteTransferItem item : transfer.getItems()) {
            // TRANSFER_OUT: Stock decreases at sending project
            // Use storage-location-level stock when a sending storage location is specified
            Integer sendingOpeningStock;
            if (transfer.getSendingStorageLocation() != null) {
                sendingOpeningStock = inventoryService.getStockAtLocation(
                        item.getMaterial().getId(), transfer.getSendingProject().getId(),
                        transfer.getSendingStorageLocation().getId());
            } else {
                sendingOpeningStock = inventoryService.getCurrentStock(
                        item.getMaterial().getId(), transfer.getSendingProject().getId());
            }
            Integer sendingQuantityChanged = -item.getSentQuantity();
            Integer sendingClosingStock = sendingOpeningStock + sendingQuantityChanged;

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

            inventoryTransactionRepository.save(outTransaction);
            logger.debug("Created TRANSFER_OUT transaction for material ID: {}, sending project ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), transfer.getSendingProject().getId(), sendingQuantityChanged, sendingClosingStock);

            // TRANSFER_IN: Stock increases at receiving project
            // Use storage-location-level stock when a receiving storage location is specified
            Integer receivingOpeningStock;
            if (transfer.getReceivingStorageLocation() != null) {
                receivingOpeningStock = inventoryService.getStockAtLocation(
                        item.getMaterial().getId(), transfer.getReceivingProject().getId(),
                        transfer.getReceivingStorageLocation().getId());
            } else {
                receivingOpeningStock = inventoryService.getCurrentStock(
                        item.getMaterial().getId(), transfer.getReceivingProject().getId());
            }
            Integer receivingQuantityChanged = item.getSentQuantity();
            Integer receivingClosingStock = receivingOpeningStock + receivingQuantityChanged;

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

            inventoryTransactionRepository.save(inTransaction);
            logger.debug("Created TRANSFER_IN transaction for material ID: {}, receiving project ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), transfer.getReceivingProject().getId(), receivingQuantityChanged, receivingClosingStock);
        }
    }
}
