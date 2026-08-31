package org.tornotron.echno_backend.siteTransfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.user.User;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The arithmetic a receipt performs on the transfer it answers, and the status that follows from
 * it, with nothing loaded from a database.
 *
 * <p>The two directions of error are deliberately not symmetrical, and the tests below pin both.
 * Receiving more than was sent is refused unless it is acknowledged, because the document would
 * otherwise assert stock the transfer never sent. Receiving less is accepted without ceremony,
 * because it asserts nothing false: refusing it would force the storekeeper either to claim the
 * full quantity arrived or to leave the part that did outside the ledger.
 */
@ExtendWith(MockitoExtension.class)
class SiteTransferReceiptReconcilerTest {

    private static final Long ORG = 100L;
    private static final Long SENDING_PROJECT = 7L;
    private static final Long RECEIVING_PROJECT = 9L;

    @Mock private StatusTransitionRecorder statusTransitionRecorder;

    private SiteTransferReceiptReconciler reconciler;
    private Organization organization;
    private User actor;

    @BeforeEach
    void setUp() {
        reconciler = new SiteTransferReceiptReconciler(statusTransitionRecorder);
        organization = new Organization();
        organization.setId(ORG);
        actor = new User();
        actor.setId(5L);
        actor.setName("Storekeeper");
    }

    private SiteTransfer transfer(SiteTransferStatus status, Long receivingProjectId) {
        SiteTransfer transfer = new SiteTransfer();
        transfer.setId(51L);
        transfer.setTransferNumber("TRF-2026-000042");
        transfer.setStatus(status);
        Project sending = new Project();
        sending.setId(SENDING_PROJECT);
        Project receiving = new Project();
        receiving.setId(receivingProjectId);
        transfer.setSendingProject(sending);
        transfer.setReceivingProject(receiving);
        transfer.setOrganization(organization);
        transfer.setItems(new ArrayList<>());
        return transfer;
    }

    private SiteTransferItem line(SiteTransfer transfer, Long id, int sent, Integer received) {
        Material material = new Material();
        material.setId(2L);
        material.setMaterialName("TMT Bar 12mm");
        SiteTransferItem item = new SiteTransferItem();
        item.setId(id);
        item.setSiteTransfer(transfer);
        item.setMaterial(material);
        item.setSentQuantity(sent);
        item.setReceivedQuantity(received);
        transfer.getItems().add(item);
        return item;
    }

    private Map<Long, Integer> receipt(Long itemId, int quantity) {
        Map<Long, Integer> requested = new LinkedHashMap<>();
        requested.put(itemId, quantity);
        return requested;
    }

    /**
     * Without this the line has no field to hold what came off the lorry, so ten sent against
     * eight arriving is two bags of stock at a site that does not have them.
     */
    @Test
    void writesWhatArrivedOntoTheLine() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);

        reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 8), false, actor, null);

        assertThat(line.getReceivedQuantity()).isEqualTo(8);
    }

    @Test
    void addsToWhatHasAlreadyArrivedRatherThanReplacingIt() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PARTIALLY_TRANSFERRED, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, 6);

        reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 3), false, actor, null);

        assertThat(line.getReceivedQuantity()).isEqualTo(9);
    }

    @Test
    void movesToCompletedWhenEveryLineIsMet() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);

        SiteTransferReceiptReconciler.ReceiptOutcome outcome =
                reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 10), false, actor, null);

        assertThat(outcome.movedTo()).isEqualTo(SiteTransferStatus.COMPLETED);
        assertThat(transfer.getStatus()).isEqualTo(SiteTransferStatus.COMPLETED);
    }

    @Test
    void movesToPartiallyTransferredWhileSomethingIsStillOutstanding() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);

        SiteTransferReceiptReconciler.ReceiptOutcome outcome =
                reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 8), false, actor, null);

        assertThat(outcome.movedTo()).isEqualTo(SiteTransferStatus.PARTIALLY_TRANSFERRED);
    }

    /**
     * A receipt confirming that nothing came is a real answer and is recorded, but it moves
     * nothing: the material is still on the road.
     */
    @Test
    void staysPendingWhenNothingArrived() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);

        SiteTransferReceiptReconciler.ReceiptOutcome outcome =
                reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 0), false, actor, null);

        assertThat(outcome.movedTo()).isNull();
        assertThat(transfer.getStatus()).isEqualTo(SiteTransferStatus.PENDING);
        assertThat(line.getReceivedQuantity()).isZero();
        verify(statusTransitionRecorder, never())
                .recordChange(anyString(), anyLong(), any(), any(), any(), any(), any());
    }

    /**
     * The transition is somebody's act, not arithmetic nobody performed, so it is filed against
     * the person who confirmed the delivery rather than as a SYSTEM entry with no actor.
     */
    @Test
    void filesTheMoveAgainstThePersonWhoConfirmedTheDelivery() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);

        reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 10), false, actor,
                "Counted at the gate");

        verify(statusTransitionRecorder).recordChange(
                eq(SiteTransferService.HISTORY_ENTITY_TYPE), eq(51L), eq(organization),
                eq("PENDING"), eq("COMPLETED"), eq(actor),
                eq("Recorded from what the receiving site confirmed had arrived. Counted at the gate"));
        verify(statusTransitionRecorder, never())
                .recordSystemChange(anyString(), anyLong(), any(), any(), any(), any());
    }

    /** Twelve against ten sent is a typed digit until somebody says otherwise. */
    @Test
    void refusesAnOverReceiptThatWasNotAcknowledged() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> reconciler.applyReceipt(
                        transfer, transfer.getItems(), receipt(84L, 12), false, actor, null))
                .withMessageContaining("TRF-2026-000042")
                .withMessageContaining("TMT Bar 12mm")
                .withMessageContaining("sent 10")
                .withMessageContaining("further 12")
                .withMessageContaining("allowOverReceipt");

        assertThat(line.getReceivedQuantity()).isNull();
        assertThat(transfer.getStatus()).isEqualTo(SiteTransferStatus.PENDING);
    }

    /**
     * The check is cumulative. Two receipts of six against a transfer of ten would each pass a
     * per-receipt check and leave the transfer holding twelve.
     */
    @Test
    void countsWhatHasAlreadyArrivedWhenJudgingAnOverReceipt() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PARTIALLY_TRANSFERRED, RECEIVING_PROJECT);
        line(transfer, 84L, 10, 6);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> reconciler.applyReceipt(
                        transfer, transfer.getItems(), receipt(84L, 6), false, actor, null))
                .withMessageContaining("would take it to 12");
    }

    /**
     * Refusing outright would leave material standing in the yard outside the ledger, which is
     * the condition that makes a later count unexplainable.
     */
    @Test
    void recordsAnOverReceiptThatWasAcknowledged() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);

        SiteTransferReceiptReconciler.ReceiptOutcome outcome =
                reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 12), true, actor, null);

        assertThat(outcome.overReceipt()).isTrue();
        assertThat(line.getReceivedQuantity()).isEqualTo(12);
        assertThat(outcome.movedTo()).isEqualTo(SiteTransferStatus.COMPLETED);
    }

    /**
     * The asymmetry, stated as a test. A short delivery asserts nothing false, so it needs no
     * flag and raises no refusal; the gap is left visible as an open variance for a stock
     * adjustment to close, and the transfer writes no loss movement of its own.
     */
    @Test
    void acceptsAShortDeliveryWithoutAnAcknowledgementAndLeavesTheGapOpen() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        SiteTransferItem line = line(transfer, 84L, 10, null);

        SiteTransferReceiptReconciler.ReceiptOutcome outcome =
                reconciler.applyReceipt(transfer, transfer.getItems(), receipt(84L, 8), false, actor, null);

        assertThat(outcome.overReceipt()).isFalse();
        assertThat(line.getSentQuantity() - line.getReceivedQuantity()).isEqualTo(2);
        assertThat(outcome.received()).singleElement()
                .extracting(l -> l.quantity()).isEqualTo(8);
    }

    /**
     * Guessing which line was meant would post stock against the wrong material, so a line that
     * is not on this transfer is refused rather than skipped.
     */
    @Test
    void refusesAReceiptNamingALineThatIsNotOnThisTransfer() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> reconciler.applyReceipt(
                        transfer, transfer.getItems(), receipt(999L, 4), false, actor, null))
                .withMessageContaining("no line with id 999");
    }

    /** A line confirmed as receiving nothing raises no movement for the ledger to post. */
    @Test
    void reportsOnlyTheLinesThatActuallyGainedAQuantity() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);
        line(transfer, 84L, 10, null);
        line(transfer, 85L, 4, null);
        Map<Long, Integer> requested = new LinkedHashMap<>();
        requested.put(84L, 10);
        requested.put(85L, 0);

        SiteTransferReceiptReconciler.ReceiptOutcome outcome =
                reconciler.applyReceipt(transfer, transfer.getItems(), requested, false, actor, null);

        assertThat(outcome.received()).singleElement()
                .extracting(l -> l.item().getId()).isEqualTo(84L);
        assertThat(outcome.movedTo()).isEqualTo(SiteTransferStatus.PARTIALLY_TRANSFERRED);
    }

    /**
     * The whole two-step decision in one line: between projects there is a lorry, within one
     * project there is a storekeeper walking across the yard.
     */
    @Test
    void crossesProjectBoundaryIsTrueOnlyWhenTheTwoProjectsDiffer() {
        assertThat(SiteTransferReceiptReconciler.crossesProjectBoundary(
                transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT))).isTrue();
        assertThat(SiteTransferReceiptReconciler.crossesProjectBoundary(
                transfer(SiteTransferStatus.COMPLETED, SENDING_PROJECT))).isFalse();
    }

    /** A transfer carrying no lines says nothing about arrival, so nothing moves. */
    @Test
    void aTransferWithNoLinesDoesNotMove() {
        SiteTransfer transfer = transfer(SiteTransferStatus.PENDING, RECEIVING_PROJECT);

        SiteTransferReceiptReconciler.ReceiptOutcome outcome = reconciler.applyReceipt(
                transfer, List.of(), Map.of(), false, actor, null);

        assertThat(outcome.movedTo()).isNull();
        assertThat(outcome.received()).isEmpty();
    }
}
