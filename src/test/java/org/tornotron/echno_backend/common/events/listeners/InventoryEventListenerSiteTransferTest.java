package org.tornotron.echno_backend.common.events.listeners;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.events.GrnCreatedEvent;
import org.tornotron.echno_backend.common.events.SiteTransferCancelledEvent;
import org.tornotron.echno_backend.common.events.SiteTransferCreatedEvent;
import org.tornotron.echno_backend.common.events.SiteTransferReceivedEvent;
import org.tornotron.echno_backend.employee.Employee;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
        return transfer(sending, receiving, receivingProject);
    }

    private SiteTransfer transfer(StorageLocation sending, StorageLocation receiving, Project into) {
        SiteTransfer transfer = new SiteTransfer();
        transfer.setId(51L);
        transfer.setTransferNumber("TRF-2026-000042");
        transfer.setIssueDate(LocalDateTime.now());
        transfer.setSendingProject(sendingProject);
        transfer.setReceivingProject(into);
        transfer.setSendingStorageLocation(sending);
        transfer.setReceivingStorageLocation(receiving);
        transfer.setOrganization(organization);

        SiteTransferItem item = new SiteTransferItem();
        item.setId(84L);
        item.setMaterial(material);
        item.setSentQuantity(4);
        transfer.setItems(List.of(item));
        return transfer;
    }

    private List<InventoryTransaction> savedTransactions(int expected) {
        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryTransactionRepository, times(expected)).save(captor.capture());
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

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer(null, null)));

        InventoryTransaction out = of(savedTransactions(1), InventoryTransactionType.TRANSFER_OUT);
        assertThat(out.getOpeningStock()).isEqualTo(10.0);
        assertThat(out.getQuantityChanged()).isEqualTo(-4.0);
        assertThat(out.getClosingStock()).isEqualTo(6.0);
        verify(inventoryService, never()).getCurrentStock(any(), any());
    }

    /**
     * The claim that made a receiving-site count unexplainable: the balance said twenty bags, the
     * yard held none, and the ledger offered a TRANSFER_IN whose closing figure nobody could
     * reconcile. Creation now posts only what actually happened, which is that the stock left.
     */
    @Test
    void aTransferBetweenTwoProjectsPostsOnlyTheOutboundLegAtCreation() {
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, null))
                .thenReturn(BigDecimal.TEN);
        when(inventoryService.findUnlocatedStock(MATERIAL, SENDING_PROJECT)).thenReturn(Optional.of(10.0));

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer(null, null)));

        List<InventoryTransaction> saved = savedTransactions(1);
        assertThat(saved).singleElement()
                .extracting(InventoryTransaction::getTransactionType)
                .isEqualTo(InventoryTransactionType.TRANSFER_OUT);
        verify(inventoryService, never()).findUnlocatedStock(MATERIAL, RECEIVING_PROJECT);
        verify(inventoryService, never()).updateCurrentStock(any(), eq(receivingProject), any(), any(), any(), any());
    }

    /**
     * Within one project the storekeeper hands the material from one store to the other and is
     * accountable for it throughout, so there is no window in which nobody is and nothing to
     * confirm. Both legs are written at once.
     */
    @Test
    void aTransferInsideOneProjectPostsBothLegsAtCreation() {
        StorageLocation sending = location(SENDING_LOCATION);
        StorageLocation receiving = location(RECEIVING_LOCATION);
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, SENDING_LOCATION))
                .thenReturn(BigDecimal.TEN);
        when(inventoryService.getStockAtLocation(MATERIAL, SENDING_PROJECT, SENDING_LOCATION)).thenReturn(10.0);
        when(inventoryService.getStockAtLocation(MATERIAL, SENDING_PROJECT, RECEIVING_LOCATION)).thenReturn(2.0);

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(
                this, transfer(sending, receiving, sendingProject)));

        List<InventoryTransaction> saved = savedTransactions(2);
        assertThat(of(saved, InventoryTransactionType.TRANSFER_OUT).getOpeningStock()).isEqualTo(10.0);
        assertThat(of(saved, InventoryTransactionType.TRANSFER_IN).getOpeningStock()).isEqualTo(2.0);
        assertThat(of(saved, InventoryTransactionType.TRANSFER_IN).getClosingStock()).isEqualTo(6.0);
    }

    @Test
    void aTransferInWithNoReceivingLocationOpensFromTheReceivingProjectsUnlocatedBalance() {
        SiteTransfer transfer = transfer(null, null);
        SiteTransferItem item = transfer.getItems().get(0);
        when(inventoryService.findUnlocatedStock(MATERIAL, RECEIVING_PROJECT)).thenReturn(Optional.of(2.0));
        when(inventoryTransactionRepository.findUnitCostsForReference(
                eq("TRF-2026-000042"), eq(MATERIAL), eq(InventoryTransactionType.TRANSFER_OUT), eq(ORG), any()))
                .thenReturn(List.of(BigDecimal.TEN));

        listener.handleSiteTransferReceived(new SiteTransferReceivedEvent(
                this, transfer, List.of(new SiteTransferReceivedEvent.ReceivedLine(item, 4)),
                receiver(), LocalDateTime.now(), null));

        InventoryTransaction in = of(savedTransactions(1), InventoryTransactionType.TRANSFER_IN);
        assertThat(in.getOpeningStock()).isEqualTo(2.0);
        assertThat(in.getQuantityChanged()).isEqualTo(4.0);
        assertThat(in.getClosingStock()).isEqualTo(6.0);
        verify(inventoryService, never()).getCurrentStock(any(), any());
    }

    /**
     * A receipt posts only what arrived, so the two that never turned up are visible as the
     * difference between the outbound leg and the inbound one rather than being invented into
     * the receiving balance.
     */
    @Test
    void aShortReceiptRaisesStockOnlyForWhatArrived() {
        SiteTransfer transfer = transfer(null, null);
        SiteTransferItem item = transfer.getItems().get(0);
        when(inventoryService.findUnlocatedStock(MATERIAL, RECEIVING_PROJECT)).thenReturn(Optional.of(0.0));
        when(inventoryTransactionRepository.findUnitCostsForReference(
                eq("TRF-2026-000042"), eq(MATERIAL), eq(InventoryTransactionType.TRANSFER_OUT), eq(ORG), any()))
                .thenReturn(List.of(BigDecimal.TEN));

        listener.handleSiteTransferReceived(new SiteTransferReceivedEvent(
                this, transfer, List.of(new SiteTransferReceivedEvent.ReceivedLine(item, 2)),
                receiver(), LocalDateTime.now(), null));

        InventoryTransaction in = of(savedTransactions(1), InventoryTransactionType.TRANSFER_IN);
        assertThat(in.getQuantityChanged()).isEqualTo(2.0);
        verify(inventoryService).updateCurrentStock(any(), eq(receivingProject), any(), any(), eq(2.0), any());
    }

    /**
     * By the time a lorry is unloaded the sending balance has moved on and may hold nothing at
     * all. Pricing the arrival off it would value the material at zero and silently destroy the
     * stock value that left the site, so the arrival takes the cost the outbound leg carried.
     */
    @Test
    void aReceiptValuesTheArrivalAtWhatLeftTheSendingSiteNotAtWhatIsLeftThere() {
        SiteTransfer transfer = transfer(null, null);
        SiteTransferItem item = transfer.getItems().get(0);
        when(inventoryService.findUnlocatedStock(MATERIAL, RECEIVING_PROJECT)).thenReturn(Optional.of(0.0));
        when(inventoryTransactionRepository.findUnitCostsForReference(
                eq("TRF-2026-000042"), eq(MATERIAL), eq(InventoryTransactionType.TRANSFER_OUT), eq(ORG), any()))
                .thenReturn(List.of(new BigDecimal("42.50")));
        // The sending site has since run out, so its average cost is now zero.
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, null)).thenReturn(BigDecimal.ZERO);

        listener.handleSiteTransferReceived(new SiteTransferReceivedEvent(
                this, transfer, List.of(new SiteTransferReceivedEvent.ReceivedLine(item, 4)),
                receiver(), LocalDateTime.now(), null));

        InventoryTransaction in = of(savedTransactions(1), InventoryTransactionType.TRANSFER_IN);
        assertThat(in.getUnitCost()).isEqualByComparingTo("42.50");
        verify(inventoryService).updateCurrentStock(any(), eq(receivingProject), any(), any(), eq(4.0),
                eq(new BigDecimal("42.50")));
    }

    /**
     * Without this a transfer abandoned in transit would leave the sending project permanently
     * short, which would make the two-step document worse than the one-step one it replaces.
     */
    @Test
    void cancellingReturnsTheSentQuantityToTheSendingBalance() {
        SiteTransfer transfer = transfer(null, null);
        when(inventoryService.findUnlocatedStock(MATERIAL, SENDING_PROJECT)).thenReturn(Optional.of(6.0));
        when(inventoryTransactionRepository.findUnitCostsForReference(
                eq("TRF-2026-000042"), eq(MATERIAL), eq(InventoryTransactionType.TRANSFER_OUT), eq(ORG), any()))
                .thenReturn(List.of(BigDecimal.TEN));

        listener.handleSiteTransferCancelled(new SiteTransferCancelledEvent(
                this, transfer, transfer.getItems(), receiver(), "Lorry turned back at the gate"));

        InventoryTransaction reversal = of(savedTransactions(1), InventoryTransactionType.TRANSFER_IN);
        assertThat(reversal.getProject()).isSameAs(sendingProject);
        assertThat(reversal.getOpeningStock()).isEqualTo(6.0);
        assertThat(reversal.getQuantityChanged()).isEqualTo(4.0);
        assertThat(reversal.getClosingStock()).isEqualTo(10.0);
        assertThat(reversal.getRemarks()).contains("cancelled in transit").contains("Lorry turned back");
        verify(inventoryService).updateCurrentStock(any(), eq(sendingProject), any(), any(), eq(4.0), any());
        verify(inventoryService, never()).updateCurrentStock(any(), eq(receivingProject), any(), any(), any(), any());
    }

    @Test
    void aProjectWithNoUnlocatedRowAtAllOpensFromZeroRatherThanTheProjectTotal() {
        // The sending project holds its stock inside storage locations, so there is no
        // unlocated row. The debit seeds one and takes it to -4, and the entry has to say so
        // rather than report the located stock the transfer never touched.
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, null))
                .thenReturn(BigDecimal.ZERO);
        when(inventoryService.findUnlocatedStock(MATERIAL, SENDING_PROJECT)).thenReturn(Optional.empty());

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer(null, null)));

        InventoryTransaction out = of(savedTransactions(1), InventoryTransactionType.TRANSFER_OUT);
        assertThat(out.getOpeningStock()).isZero();
        assertThat(out.getClosingStock()).isEqualTo(-4.0);
    }

    @Test
    void aTransferBetweenTwoLocationsStillOpensFromEachLocationsOwnBalance() {
        StorageLocation sending = location(SENDING_LOCATION);
        StorageLocation receiving = location(RECEIVING_LOCATION);
        SiteTransfer transfer = transfer(sending, receiving);
        lenient().when(inventoryService.getAverageCost(MATERIAL, SENDING_PROJECT, SENDING_LOCATION))
                .thenReturn(BigDecimal.TEN);
        when(inventoryService.getStockAtLocation(MATERIAL, SENDING_PROJECT, SENDING_LOCATION)).thenReturn(10.0);
        when(inventoryService.getStockAtLocation(MATERIAL, RECEIVING_PROJECT, RECEIVING_LOCATION)).thenReturn(2.0);
        when(inventoryTransactionRepository.findUnitCostsForReference(
                eq("TRF-2026-000042"), eq(MATERIAL), eq(InventoryTransactionType.TRANSFER_OUT), eq(ORG), any()))
                .thenReturn(List.of(BigDecimal.TEN));

        listener.handleSiteTransferCreated(new SiteTransferCreatedEvent(this, transfer));
        listener.handleSiteTransferReceived(new SiteTransferReceivedEvent(
                this, transfer, List.of(new SiteTransferReceivedEvent.ReceivedLine(transfer.getItems().get(0), 4)),
                receiver(), LocalDateTime.now(), null));

        List<InventoryTransaction> saved = savedTransactions(2);
        assertThat(of(saved, InventoryTransactionType.TRANSFER_OUT).getOpeningStock()).isEqualTo(10.0);
        assertThat(of(saved, InventoryTransactionType.TRANSFER_IN).getOpeningStock()).isEqualTo(2.0);
        verify(inventoryService, never()).findUnlocatedStock(any(), anyLong());
    }

    private Employee receiver() {
        Employee employee = new Employee();
        employee.setId(31L);
        employee.setEmployeeName("Storekeeper");
        return employee;
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
