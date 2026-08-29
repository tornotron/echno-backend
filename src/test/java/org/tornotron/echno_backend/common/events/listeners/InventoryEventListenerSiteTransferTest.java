package org.tornotron.echno_backend.common.events.listeners;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransactionRepository;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.storageLocation.StorageLocation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The opening and closing figures on a ledger entry have to describe the balance row the
 * movement actually writes. {@code CurrentStock} is keyed by material, project and storage
 * location, so a movement naming no location reads and writes the project's unlocated row
 * and not the project total. Reading the total instead puts a figure on the entry that the
 * row it moved never held, and on the outbound side it hides the fact that the row is
 * about to go negative.
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventListenerSiteTransferTest {

    private static final Long ORG = 100L;
    private static final Long MATERIAL = 2L;
    private static final Long SENDING_PROJECT = 7L;
    private static final Long RECEIVING_PROJECT = 9L;
    private static final Long SENDING_LOCATION = 3L;
    private static final Long RECEIVING_LOCATION = 4L;

    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private InventoryService inventoryService;

    private InventoryEventListener listener;
    private Material material;
    private Project sendingProject;
    private Project receivingProject;
    private Organization organization;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventListener(inventoryTransactionRepository, inventoryService);
        material = new Material();
        material.setId(MATERIAL);
        sendingProject = new Project();
        sendingProject.setId(SENDING_PROJECT);
        sendingProject.setProjectName("Sending site");
        receivingProject = new Project();
        receivingProject.setId(RECEIVING_PROJECT);
        receivingProject.setProjectName("Receiving site");
        organization = new Organization();
        organization.setId(ORG);
    }

    private StorageLocation location(Long id) {
        StorageLocation location = new StorageLocation();
        location.setId(id);
        return location;
    }

    private SiteTransfer transfer(StorageLocation sending, StorageLocation receiving) {
        SiteTransfer transfer = new SiteTransfer();
        transfer.setId(51L);
        transfer.setTransferNumber("TRF-2026-000042");
        transfer.setIssueDate(LocalDateTime.now());
        transfer.setSendingProject(sendingProject);
        transfer.setReceivingProject(receivingProject);
        transfer.setSendingStorageLocation(sending);
        transfer.setReceivingStorageLocation(receiving);
        transfer.setOrganization(organization);

        SiteTransferItem item = new SiteTransferItem();
        item.setMaterial(material);
        item.setSentQuantity(4);
        transfer.setItems(List.of(item));
        return transfer;
    }

    private List<InventoryTransaction> savedTransactions() {
        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository, times(2)).save(captor.capture());
        return captor.getAllValues();
    }

    private InventoryTransaction of(List<InventoryTransaction> saved, InventoryTransactionType type) {
        return saved.stream()
                .filter(t -> t.getTransactionType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " entry was written"));
    }

    @Test
    void aTransferOutWithNoSendingLocationOpensFromTheSendingProjectsUnlocatedBalance() {
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, null))
                .thenReturn(BigDecimal.TEN);
        when(inventoryService.findUnlocatedStock(MATERIAL, SENDING_PROJECT)).thenReturn(Optional.of(10.0));
        when(inventoryService.findUnlocatedStock(MATERIAL, RECEIVING_PROJECT)).thenReturn(Optional.of(0.0));

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer(null, null)));

        InventoryTransaction out = of(savedTransactions(), InventoryTransactionType.TRANSFER_OUT);
        assertThat(out.getOpeningStock()).isEqualTo(10.0);
        assertThat(out.getQuantityChanged()).isEqualTo(-4.0);
        assertThat(out.getClosingStock()).isEqualTo(6.0);
        verify(inventoryService, never()).getCurrentStock(any(), any());
    }

    @Test
    void aTransferInWithNoReceivingLocationOpensFromTheReceivingProjectsUnlocatedBalance() {
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, null))
                .thenReturn(BigDecimal.TEN);
        when(inventoryService.findUnlocatedStock(MATERIAL, SENDING_PROJECT)).thenReturn(Optional.of(10.0));
        when(inventoryService.findUnlocatedStock(MATERIAL, RECEIVING_PROJECT)).thenReturn(Optional.of(2.0));

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer(null, null)));

        InventoryTransaction in = of(savedTransactions(), InventoryTransactionType.TRANSFER_IN);
        assertThat(in.getOpeningStock()).isEqualTo(2.0);
        assertThat(in.getQuantityChanged()).isEqualTo(4.0);
        assertThat(in.getClosingStock()).isEqualTo(6.0);
        verify(inventoryService, never()).getCurrentStock(any(), any());
    }

    @Test
    void aProjectWithNoUnlocatedRowAtAllOpensFromZeroRatherThanTheProjectTotal() {
        // The sending project holds its stock inside storage locations, so there is no
        // unlocated row. The debit seeds one and takes it to -4, and the entry has to say so
        // rather than report the located stock the transfer never touched.
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, null))
                .thenReturn(BigDecimal.ZERO);
        when(inventoryService.findUnlocatedStock(MATERIAL, SENDING_PROJECT)).thenReturn(Optional.empty());
        when(inventoryService.findUnlocatedStock(MATERIAL, RECEIVING_PROJECT)).thenReturn(Optional.empty());

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer(null, null)));

        InventoryTransaction out = of(savedTransactions(), InventoryTransactionType.TRANSFER_OUT);
        assertThat(out.getOpeningStock()).isZero();
        assertThat(out.getClosingStock()).isEqualTo(-4.0);
    }

    @Test
    void aTransferBetweenTwoLocationsStillOpensFromEachLocationsOwnBalance() {
        StorageLocation sending = location(SENDING_LOCATION);
        StorageLocation receiving = location(RECEIVING_LOCATION);
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, SENDING_LOCATION))
                .thenReturn(BigDecimal.TEN);
        when(inventoryService.getStockAtLocation(MATERIAL, SENDING_PROJECT, SENDING_LOCATION)).thenReturn(10.0);
        when(inventoryService.getStockAtLocation(MATERIAL, RECEIVING_PROJECT, RECEIVING_LOCATION)).thenReturn(2.0);

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer(sending, receiving)));

        List<InventoryTransaction> saved = savedTransactions();
        assertThat(of(saved, InventoryTransactionType.TRANSFER_OUT).getOpeningStock()).isEqualTo(10.0);
        assertThat(of(saved, InventoryTransactionType.TRANSFER_IN).getOpeningStock()).isEqualTo(2.0);
        verify(inventoryService, never()).findUnlocatedStock(any(), any());
    }

    @Test
    void aGrnWithNoStorageLocationOpensFromTheProjectsUnlocatedBalance() {
        GoodsReceivedNote grn = new GoodsReceivedNote();
        grn.setGrnNumber("GRN-2026-000003");
        grn.setReceivedOn(LocalDateTime.now());
        grn.setProject(sendingProject);
        grn.setOrganization(organization);
        GrnItem item = new GrnItem();
        item.setMaterial(material);
        item.setReceivedQuantity(7);
        item.setUnitCost(BigDecimal.TEN);
        grn.setItems(List.of(item));

        when(inventoryService.findUnlocatedStock(MATERIAL, SENDING_PROJECT)).thenReturn(Optional.of(3.0));

        listener.handleGrnCreated(new GrnCreatedEvent(this, grn));

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getOpeningStock()).isEqualTo(3.0);
        assertThat(captor.getValue().getClosingStock()).isEqualTo(10.0);
        verify(inventoryService, never()).getCurrentStock(any(), any());
    }
}
