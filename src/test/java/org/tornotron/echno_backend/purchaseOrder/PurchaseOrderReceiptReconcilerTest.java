package org.tornotron.echno_backend.purchaseOrder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the reconciliation a goods receipt performs against the purchase order it cites.
 *
 * <p>Every case here fails on the code as it stood before this class existed, where
 * {@code receivedQuantity} was written zero at creation and never again and no code compared a
 * receipt with an order. The repositories and the status-trail recorder are mocked and the
 * entity graph is built in memory; the assertions read the entities the reconciler mutated and
 * the arguments it passed to the recorder.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderReceiptReconcilerTest {

    private static final Long ORG = 100L;
    private static final Long PO_ID = 204L;
    private static final Long CEMENT = 44L;
    private static final Long STEEL = 45L;

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private StatusTransitionRecorder statusTransitionRecorder;

    private PurchaseOrderReceiptReconciler reconciler;
    private Organization organization;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        reconciler = new PurchaseOrderReceiptReconciler(purchaseOrderRepository,
                purchaseOrderItemRepository, statusTransitionRecorder);
        lenient().when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void addsTheReceivedQuantityToTheMatchingOrderLine() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        PurchaseOrderItem line = line(1L, CEMENT, 100, 0);
        stubLines(line);

        reconciler.applyReceipt(order, grn("GRN-2026-000001"), List.of(received(CEMENT, 40)), false);

        assertThat(line.getReceivedQuantity()).isEqualTo(40);
    }

    @Test
    void addsToTheQuantityAlreadyReceivedRatherThanReplacingIt() {
        PurchaseOrder order = order(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        PurchaseOrderItem line = line(1L, CEMENT, 100, 40);
        stubLines(line);

        reconciler.applyReceipt(order, grn("GRN-2026-000002"), List.of(received(CEMENT, 25)), false);

        assertThat(line.getReceivedQuantity()).isEqualTo(65);
    }

    @Test
    void refusesAnOverReceiptThatWasNotAcknowledged() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        PurchaseOrderItem line = line(1L, CEMENT, 10, 0);
        stubLines(line);

        assertThatThrownBy(() -> reconciler.applyReceipt(
                order, grn("GRN-2026-000003"), List.of(received(CEMENT, 10_000)), false))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("PO-2026-000001")
                .hasMessageContaining("Portland Cement")
                .hasMessageContaining("10000")
                .hasMessageContaining("allowOverReceipt");

        assertThat(line.getReceivedQuantity()).isZero();
        verify(purchaseOrderItemRepository, never()).saveAll(any());
    }

    @Test
    void countsWhatIsAlreadyReceivedWhenJudgingAnOverReceipt() {
        PurchaseOrder order = order(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        PurchaseOrderItem line = line(1L, CEMENT, 100, 95);
        stubLines(line);

        assertThatThrownBy(() -> reconciler.applyReceipt(
                order, grn("GRN-2026-000004"), List.of(received(CEMENT, 20)), false))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("115");
    }

    @Test
    void recordsAnOverReceiptThatWasAcknowledged() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        PurchaseOrderItem line = line(1L, CEMENT, 100, 0);
        stubLines(line);

        PurchaseOrderReceiptReconciler.ReceiptOutcome outcome = reconciler.applyReceipt(
                order, grn("GRN-2026-000005"), List.of(received(CEMENT, 105)), true);

        assertThat(outcome.overReceipt()).isTrue();
        assertThat(line.getReceivedQuantity()).isEqualTo(105);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
    }

    @Test
    void movesTheOrderToPartiallyReceivedWhileSomethingIsOutstanding() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        stubLines(line(1L, CEMENT, 100, 0), line(2L, STEEL, 50, 0));

        PurchaseOrderReceiptReconciler.ReceiptOutcome outcome = reconciler.applyReceipt(
                order, grn("GRN-2026-000006"), List.of(received(CEMENT, 100)), false);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(outcome.movedTo()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void movesTheOrderToFullyReceivedWhenEveryLineIsMet() {
        PurchaseOrder order = order(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        stubLines(line(1L, CEMENT, 100, 100), line(2L, STEEL, 50, 30));

        reconciler.applyReceipt(order, grn("GRN-2026-000007"), List.of(received(STEEL, 20)), false);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
    }

    @Test
    void recordsTheDerivedMoveAsASystemTransitionNamingTheReceipt() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        stubLines(line(1L, CEMENT, 100, 0));

        reconciler.applyReceipt(order, grn("GRN-2026-000008"), List.of(received(CEMENT, 100)), false);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(statusTransitionRecorder).recordSystemChange(
                eq("PURCHASE_ORDER"), eq(PO_ID), eq(organization),
                eq("SENT_TO_VENDOR"), eq("FULLY_RECEIVED"), note.capture());
        assertThat(note.getValue()).contains("GRN-2026-000008");
    }

    @Test
    void leavesADraftOrderWhereItIsWhileStillRecordingWhatArrived() {
        PurchaseOrder order = order(PurchaseOrderStatus.DRAFT);
        PurchaseOrderItem line = line(1L, CEMENT, 100, 0);
        stubLines(line);

        reconciler.applyReceipt(order, grn("GRN-2026-000009"), List.of(received(CEMENT, 100)), false);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(line.getReceivedQuantity()).isEqualTo(100);
        verify(statusTransitionRecorder, never())
                .recordSystemChange(anyString(), anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void leavesACancelledOrderWhereItIs() {
        PurchaseOrder order = order(PurchaseOrderStatus.CANCELLED);
        stubLines(line(1L, CEMENT, 100, 0));

        reconciler.applyReceipt(order, grn("GRN-2026-000010"), List.of(received(CEMENT, 100)), false);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    }

    @Test
    void reconcilesNothingForAReceiptLineThatIsNotOnTheOrder() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        PurchaseOrderItem line = line(1L, CEMENT, 100, 0);
        stubLines(line);

        PurchaseOrderReceiptReconciler.ReceiptOutcome outcome = reconciler.applyReceipt(
                order, grn("GRN-2026-000011"), List.of(received(STEEL, 500)), false);

        assertThat(outcome.matchedLines()).isZero();
        assertThat(outcome.overReceipt()).isFalse();
        assertThat(line.getReceivedQuantity()).isZero();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SENT_TO_VENDOR);
    }

    @Test
    void takesTheOrderedQuantityOnTheReceiptLineFromTheOrder() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        stubLines(line(1L, CEMENT, 100, 0));
        GrnItem receiptLine = received(CEMENT, 40);
        receiptLine.setOrderedQuantity(7);

        reconciler.applyReceipt(order, grn("GRN-2026-000012"), List.of(receiptLine), false);

        assertThat(receiptLine.getOrderedQuantity()).isEqualTo(100);
    }

    @Test
    void spreadsAQuantityAcrossTwoOrderLinesForTheSameMaterial() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        PurchaseOrderItem first = line(1L, CEMENT, 30, 0);
        PurchaseOrderItem second = line(2L, CEMENT, 70, 0);
        stubLines(first, second);

        reconciler.applyReceipt(order, grn("GRN-2026-000013"), List.of(received(CEMENT, 50)), false);

        assertThat(first.getReceivedQuantity()).isEqualTo(30);
        assertThat(second.getReceivedQuantity()).isEqualTo(20);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void putsAnAcknowledgedExcessOnTheLastLineSoTheLinesStillSumToWhatArrived() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        PurchaseOrderItem first = line(1L, CEMENT, 30, 0);
        PurchaseOrderItem second = line(2L, CEMENT, 70, 0);
        stubLines(first, second);

        reconciler.applyReceipt(order, grn("GRN-2026-000014"), List.of(received(CEMENT, 120)), true);

        assertThat(first.getReceivedQuantity() + second.getReceivedQuantity()).isEqualTo(120);
        assertThat(second.getReceivedQuantity()).isEqualTo(90);
    }

    @Test
    void sumsTwoReceiptLinesForTheSameMaterialBeforeJudgingTheOverReceipt() {
        PurchaseOrder order = order(PurchaseOrderStatus.SENT_TO_VENDOR);
        stubLines(line(1L, CEMENT, 100, 0));

        assertThatThrownBy(() -> reconciler.applyReceipt(order, grn("GRN-2026-000015"),
                List.of(received(CEMENT, 60), received(CEMENT, 60)), false))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("120");
    }

    private void stubLines(PurchaseOrderItem... lines) {
        when(purchaseOrderItemRepository.lockByPurchaseOrderIdAndOrganizationId(PO_ID, ORG))
                .thenReturn(new ArrayList<>(List.of(lines)));
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setPoNumber("PO-2026-000001");
        order.setStatus(status);
        order.setOrganization(organization);
        return order;
    }

    private PurchaseOrderItem line(Long id, Long materialId, int ordered, int received) {
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setId(id);
        line.setMaterial(material(materialId));
        line.setOrderedQuantity(ordered);
        line.setReceivedQuantity(received);
        line.setOrganization(organization);
        return line;
    }

    private GrnItem received(Long materialId, int quantity) {
        GrnItem item = new GrnItem();
        item.setMaterial(material(materialId));
        item.setOrderedQuantity(quantity);
        item.setReceivedQuantity(quantity);
        item.setOrganization(organization);
        return item;
    }

    private static Material material(Long id) {
        Material material = new Material();
        material.setId(id);
        material.setMaterialName(CEMENT.equals(id) ? "Portland Cement 53 grade" : "TMT Bar Fe 500D, 12mm");
        return material;
    }

    private static GoodsReceivedNote grn(String number) {
        GoodsReceivedNote grn = new GoodsReceivedNote();
        grn.setGrnNumber(number);
        return grn;
    }
}
