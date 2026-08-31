package org.tornotron.echno_backend.siteTransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.events.SiteTransferReceivedEvent.ReceivedLine;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;
import org.tornotron.echno_backend.user.User;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Works out what a receipt does to the transfer it answers, and moves the transfer's status.
 *
 * <p>A transfer used to write both of its inventory legs the moment it was created, so stock
 * arrived at a site before anybody there had seen the lorry. That is right within one project,
 * where the material never leaves the site's custody, and wrong between two, where there is a
 * lorry and a road and a gap of hours or days. A transfer that crosses a project boundary now
 * writes only its outbound leg at creation and gains its inbound leg here, when somebody at the
 * receiving site says what turned up.
 *
 * <p>The arithmetic is deliberately separate from the loading, locking and event publishing in
 * {@link SiteTransferService}, so the rules below can be read and tested without a transfer
 * having to exist in a database.
 */
@Component
public class SiteTransferReceiptReconciler {

    private static final Logger logger = LoggerFactory.getLogger(SiteTransferReceiptReconciler.class);

    private final StatusTransitionRecorder statusTransitionRecorder;

    public SiteTransferReceiptReconciler(StatusTransitionRecorder statusTransitionRecorder) {
        this.statusTransitionRecorder = statusTransitionRecorder;
    }

    /**
     * What a receipt did to the transfer.
     *
     * @param received    One entry per line that gained a quantity, for the ledger to post.
     * @param overReceipt Whether any line ended up received beyond what was sent.
     * @param movedTo     The status the transfer was moved to, or null if it did not move.
     */
    public record ReceiptOutcome(List<ReceivedLine> received, boolean overReceipt,
                                 SiteTransferStatus movedTo) {
    }

    /**
     * Whether a transfer moves stock out of one project and into another.
     *
     * <p>This is the whole of the two-step decision. Between projects there is a lorry and a
     * road, so the inbound leg waits for somebody to confirm it. Within one project the two
     * sides are two stores on a site whose storekeeper hands the material from one to the other,
     * with no window in which nobody is accountable for it, so both legs are written at creation
     * and the transfer is complete from the moment it exists. A same-project transfer always
     * names two different storage locations, because one that named the same location on both
     * sides would move nothing and is refused at creation.
     */
    public static boolean crossesProjectBoundary(SiteTransfer transfer) {
        Long sending = transfer.getSendingProject() != null ? transfer.getSendingProject().getId() : null;
        Long receiving = transfer.getReceivingProject() != null ? transfer.getReceivingProject().getId() : null;
        return !Objects.equals(sending, receiving);
    }

    /**
     * Adds a receipt's quantities to a transfer's lines and moves the transfer's status.
     *
     * <p><strong>An over-receipt is refused by default and recorded on request</strong>, on the
     * purchase order's reasoning and for the same reason: refusing outright would leave material
     * that is standing in the yard outside the stock ledger, which is the condition that makes a
     * later count unexplainable, while accepting it silently is the behaviour being replaced. So
     * the refusal names the line, what was sent, what has already arrived and what this receipt
     * adds, and a payload that acknowledges the excess records it.
     *
     * <p><strong>A shortfall is not refused and needs no acknowledgement</strong>, which is not
     * an inconsistency with the rule above but the same rule applied to the other direction. The
     * over-receipt guard exists because the document would otherwise assert stock the transfer
     * never sent. Eight bags arriving against ten sent asserts nothing false: the ledger holds an
     * honest outbound leg for ten and an honest inbound leg for eight, and the two missing bags
     * are visible as the difference. Refusing the short receipt would force the storekeeper
     * either to claim ten arrived or to leave the eight that did outside the ledger, which is the
     * failure the over-receipt escape hatch exists to prevent. The gap is left as an open
     * variance on the transfer and closed by a stock adjustment naming it. The transfer does not
     * write a loss movement of its own: a loss written automatically is a stock correction
     * nobody authorised, and putting an approver in front of stock corrections is what #651 is.
     *
     * @param transfer          The transfer being received.
     * @param lines             The transfer's lines, already taken under a write lock.
     * @param requested         How much each line id is being recorded as receiving now.
     * @param overReceiptAllowed Whether the payload acknowledged an over-receipt in advance.
     * @param actor             The user confirming the delivery, from the session.
     * @param remarks           An optional note, carried onto the status trail.
     * @return What the receipt did.
     * @throws InvalidRequestException if a line would be over-received without acknowledgement,
     *     or if the payload names a line that is not on this transfer.
     */
    public ReceiptOutcome applyReceipt(SiteTransfer transfer, List<SiteTransferItem> lines,
                                       Map<Long, Integer> requested, boolean overReceiptAllowed,
                                       User actor, String remarks) {
        Map<Long, SiteTransferItem> linesById = new LinkedHashMap<>();
        for (SiteTransferItem line : lines) {
            linesById.put(line.getId(), line);
        }

        List<String> overReceipts = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
            SiteTransferItem line = linesById.get(entry.getKey());
            if (line == null) {
                // Naming a line that is not on this transfer is a mistake about which document
                // is being received, and guessing which line was meant would post stock against
                // the wrong material.
                throw new InvalidRequestException(
                        "Site transfer " + transfer.getTransferNumber() + " has no line with id "
                                + entry.getKey() + ". Read the transfer to see the line ids it "
                                + "carries, and send a receipt against those.");
            }
            int sent = sentQuantity(line);
            int already = receivedQuantity(line);
            int total = already + entry.getValue();
            if (total > sent) {
                overReceipts.add(describeOverReceipt(transfer, line, sent, already, entry.getValue(), total));
            }
        }

        boolean overReceipt = !overReceipts.isEmpty();
        if (overReceipt && !overReceiptAllowed) {
            throw new InvalidRequestException(String.join(" ", overReceipts)
                    + " If more really did arrive than was sent, send the receipt again with "
                    + "allowOverReceipt set, which records what arrived and posts it to the "
                    + "receiving site. Receiving less than was sent needs no such flag: the "
                    + "shortfall is left as an open variance for a stock adjustment to close.");
        }

        List<ReceivedLine> received = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
            SiteTransferItem line = linesById.get(entry.getKey());
            // A line recorded as receiving nothing still has its receivedQuantity written, so
            // the transfer says the line was looked at and nothing came, rather than staying
            // null and reading as never confirmed. It raises no movement.
            line.setReceivedQuantity(receivedQuantity(line) + entry.getValue());
            if (entry.getValue() > 0) {
                received.add(new ReceivedLine(line, entry.getValue()));
            }
        }

        SiteTransferStatus movedTo = moveStatus(transfer, lines, actor, remarks);
        return new ReceiptOutcome(received, overReceipt, movedTo);
    }

    /**
     * Works the transfer's status out from its lines and moves it if it has changed.
     *
     * <p>Unlike the purchase order's derived move, this transition is somebody's act: a person
     * at the receiving site looked at what came off the lorry and said so. It is filed as an
     * ordinary {@code UPDATE} against that person rather than as a {@code SYSTEM} transition,
     * because there is an actor to name and naming them is the point of the two-step document.
     *
     * @return The status moved to, or null if it did not move.
     */
    private SiteTransferStatus moveStatus(SiteTransfer transfer, List<SiteTransferItem> lines,
                                          User actor, String remarks) {
        SiteTransferStatus current = transfer.getStatus();
        SiteTransferStatus derived = derive(lines);
        if (derived == null || derived == current) {
            return null;
        }

        transfer.setStatus(derived);
        String note = "Recorded from what the receiving site confirmed had arrived."
                + (remarks != null && !remarks.isBlank() ? " " + remarks.trim() : "");
        statusTransitionRecorder.recordChange(
                SiteTransferService.HISTORY_ENTITY_TYPE,
                transfer.getId(),
                transfer.getOrganization(),
                current != null ? current.name() : null,
                derived.name(),
                actor,
                note);
        logger.info("Site transfer {} moved from {} to {} on a recorded receipt",
                transfer.getTransferNumber(), current, derived);
        return derived;
    }

    /**
     * The status a transfer's lines say it is in, or null while nothing has been received and
     * the lines therefore say nothing about arrival at all.
     */
    private static SiteTransferStatus derive(List<SiteTransferItem> lines) {
        boolean anyReceived = false;
        boolean allMet = true;
        for (SiteTransferItem line : lines) {
            int received = receivedQuantity(line);
            if (received > 0) {
                anyReceived = true;
            }
            if (received < sentQuantity(line)) {
                allMet = false;
            }
        }
        if (!anyReceived) {
            return null;
        }
        return allMet ? SiteTransferStatus.COMPLETED : SiteTransferStatus.PARTIALLY_TRANSFERRED;
    }

    private static String describeOverReceipt(SiteTransfer transfer, SiteTransferItem line,
                                              int sent, int already, int arriving, int total) {
        String material = line.getMaterial() != null && line.getMaterial().getMaterialName() != null
                ? line.getMaterial().getMaterialName()
                : "material " + (line.getMaterial() != null ? line.getMaterial().getId() : "unknown");
        return "Site transfer " + transfer.getTransferNumber() + " sent " + sent + " of " + material
                + ", " + already + " has already been received against it, and this receipt records "
                + "a further " + arriving + ", which would take it to " + total + ".";
    }

    static int sentQuantity(SiteTransferItem line) {
        return line.getSentQuantity() != null ? line.getSentQuantity() : 0;
    }

    static int receivedQuantity(SiteTransferItem line) {
        return line.getReceivedQuantity() != null ? line.getReceivedQuantity() : 0;
    }
}
