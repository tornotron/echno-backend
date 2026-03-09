package org.tornotron.echno_backend.common.events.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleGrnCreated(GrnCreatedEvent event) {
        GoodsReceivedNote grn = event.getGoodsReceivedNote();
        logger.info("Handling GRN created event for GRN number: {}", grn.getGrnNumber());

        for (GrnItem item : grn.getItems()) {
            Integer openingStock = inventoryService.getCurrentStock(item.getMaterial().getId());
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
            transaction.setOrganization(grn.getOrganization());

            inventoryTransactionRepository.save(transaction);
            logger.debug("Created inventory transaction for material ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), quantityChanged, closingStock);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMaterialConsumed(MaterialConsumedEvent event) {
        MaterialConsumption consumption = event.getMaterialConsumption();
        logger.info("Handling material consumed event for consumption ID: {}", consumption.getId());

        Integer openingStock = inventoryService.getCurrentStock(consumption.getMaterial().getId());
        Integer quantityChanged = -consumption.getQuantity(); // Negative for consumption
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
        transaction.setOrganization(consumption.getOrganization());

        inventoryTransactionRepository.save(transaction);
        logger.debug("Created inventory transaction for material ID: {}, quantity: {}, closing stock: {}",
                consumption.getMaterial().getId(), quantityChanged, closingStock);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSiteTransferCreated(SiteTransferCreatedEvent event) {
        SiteTransfer transfer = event.getSiteTransfer();
        logger.info("Handling site transfer created event for transfer number: {}", transfer.getTransferNumber());

        for (SiteTransferItem item : transfer.getItems()) {
            Integer openingStock = inventoryService.getCurrentStock(item.getMaterial().getId());
            Integer quantityChanged = -item.getSentQuantity(); // Negative for transfer out
            Integer closingStock = openingStock + quantityChanged;

            InventoryTransaction transaction = new InventoryTransaction();
            transaction.setTransactionDate(transfer.getIssueDate() != null ? transfer.getIssueDate() : LocalDateTime.now());
            transaction.setMaterial(item.getMaterial());
            transaction.setOpeningStock(openingStock);
            transaction.setQuantityChanged(quantityChanged);
            transaction.setClosingStock(closingStock);
            transaction.setTransactionType(InventoryTransactionType.TRANSFER);
            transaction.setReferenceNumber(transfer.getTransferNumber());
            transaction.setRemarks("Site transfer to " + (transfer.getReceivingSite() != null ? transfer.getReceivingSite() : "site") +
                    " by " + (transfer.getSendingPerson() != null ? transfer.getSendingPerson().getEmployeeName() : "employee") +
                    (item.getRemarks() != null ? " - " + item.getRemarks() : ""));
            transaction.setCreatedBy(transfer.getSendingPerson());
            transaction.setOrganization(transfer.getOrganization());

            inventoryTransactionRepository.save(transaction);
            logger.debug("Created inventory transaction for material ID: {}, quantity: {}, closing stock: {}",
                    item.getMaterial().getId(), quantityChanged, closingStock);
        }
    }
}
