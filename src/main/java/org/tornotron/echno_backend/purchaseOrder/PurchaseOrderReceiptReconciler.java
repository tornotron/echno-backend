package org.tornotron.echno_backend.purchaseOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.grnItem.GrnItem;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Posts a goods receipt back onto the purchase order it cites.
 *
 * <p>Until this existed a receipt and its order never met. {@code receivedQuantity} on an order
 * line was written zero at creation and never again, so an order for ten bags read as fully
 * outstanding after ten thousand had been received against it, the delete guard that refuses to
 * remove a line with stock against it was permanently satisfied, and
 * {@code PARTIALLY_RECEIVED} and {@code FULLY_RECEIVED} were statuses no code could reach.
 *
 * <p>Three things happen here, in one transaction with the receipt that triggered them, so a
 * refusal takes the receipt with it and no stock is posted for a delivery the order rejected:
 *
 * <ol>
 *   <li>Each receipt line is matched to the order lines for the same material and the quantity
 *       received is added to them. The order now says what has actually arrived.</li>
 *   <li>A receipt that would take a material past the quantity ordered is refused, unless the
 *       caller said in the payload that the over-receipt is real. See
 *       {@link #applyReceipt} for why it is refused rather than silently accepted, and why the
 *       refusal has a way past it rather than being absolute.</li>
 *   <li>The order's status is worked out from its lines and moved if it has changed, and the
 *       move is written to the shared status trail as a {@code SYSTEM} transition naming the
 *       receipt that caused it.</li>
 * </ol>
 *
 * <p>A receipt line whose material is not on the order reconciles nothing, which is the truth
 * about it. It still posts stock exactly as before. Refusing it would be wrong: a lorry can
 * carry an item that was ordered separately or supplied free, and a receipt that cannot be
 * recorded leaves stock standing at a site that the ledger cannot account for.
 */
@Component
public class PurchaseOrderReceiptReconciler {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseOrderReceiptReconciler.class);

    /**
     * The statuses from which a receipt may move an order.
     *
     * <p>{@code DRAFT} is left alone because an order that was never approved was never placed,
     * and moving it straight to {@code FULLY_RECEIVED} would erase the fact that nobody approved
     * it. {@code CANCELLED} is left alone because a receipt must not quietly reinstate an order
     * somebody cancelled. In both cases the received quantities are still written: what arrived
     * is a fact and is recorded whatever state the order is in. Only the status, which is a
     * lifecycle claim, is withheld.
     */
    private static final Set<PurchaseOrderStatus> MOVABLE = EnumSet.of(
            PurchaseOrderStatus.APPROVED,
            PurchaseOrderStatus.SENT_TO_VENDOR,
            PurchaseOrderStatus.PARTIALLY_RECEIVED,
            PurchaseOrderStatus.FULLY_RECEIVED);

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final StatusTransitionRecorder statusTransitionRecorder;

    public PurchaseOrderReceiptReconciler(PurchaseOrderRepository purchaseOrderRepository,
                                          PurchaseOrderItemRepository purchaseOrderItemRepository,
                                          StatusTransitionRecorder statusTransitionRecorder) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.statusTransitionRecorder = statusTransitionRecorder;
    }

    /**
     * What the receipt did to the order, for the caller to record on the receipt itself.
     *
     * @param matchedLines      How many receipt lines found a line on the order.
     * @param overReceipt       Whether any material ended up received beyond the quantity ordered.
     * @param movedTo           The status the order was moved to, or null if it did not move.
     */
    public record ReceiptOutcome(int matchedLines, boolean overReceipt, PurchaseOrderStatus movedTo) {
    }

    /**
     * Adds a receipt's quantities to the order it cites and moves the order's status if the
     * arithmetic has changed it.
     *
     * <p><strong>Over-receipt is refused by default and recorded on request.</strong> Neither
     * extreme is right on a construction site. Accepting any quantity silently is what this
     * class replaced: a mistyped ten thousand against an order for ten raised stock by ten
     * thousand and nothing anywhere noticed. Refusing outright is worse than it looks, because
     * the supplier who delivers a hundred and five bags against an order for a hundred has left
     * a hundred and five bags on the site whether the system likes it or not, and a receipt that
     * cannot be filed puts them outside the stock ledger, where no later count can be explained.
     * So the default refuses and says exactly which line, how much was ordered, how much had
     * already arrived and how much this note adds, which is enough for the person to see a typed
     * digit; and a payload that sets {@code allowOverReceipt} records the excess and has the
     * receipt marked as an acknowledged over-receipt, so the document explains itself to whoever
     * reads it next.
     *
     * <p>The order's lines are taken under a write lock, so two receipts against the same order
     * cannot each judge themselves against the figure that stood before the other.
     *
     * @param purchaseOrder      The order the receipt cites.
     * @param goodsReceivedNote  The receipt, used to name the cause of a status move.
     * @param receivedLines      The receipt's lines.
     * @param overReceiptAllowed Whether the payload acknowledged an over-receipt in advance.
     * @return What the receipt did to the order.
     * @throws InvalidRequestException if a line would be over-received and the payload did not
     *     acknowledge it.
     */
    public ReceiptOutcome applyReceipt(PurchaseOrder purchaseOrder,
                                       GoodsReceivedNote goodsReceivedNote,
                                       List<GrnItem> receivedLines,
                                       boolean overReceiptAllowed) {
        List<PurchaseOrderItem> orderLines = purchaseOrderItemRepository
                .lockByPurchaseOrderIdAndOrganizationId(purchaseOrder.getId(), TenantContext.getCurrentOrgId());
        if (orderLines.isEmpty()) {
            return new ReceiptOutcome(0, false, null);
        }

        Map<Long, List<PurchaseOrderItem>> orderLinesByMaterial = new LinkedHashMap<>();
        for (PurchaseOrderItem line : orderLines) {
            if (line.getMaterial() == null) {
                continue;
            }
            orderLinesByMaterial
                    .computeIfAbsent(line.getMaterial().getId(), key -> new ArrayList<>())
                    .add(line);
        }
        orderLinesByMaterial.values().forEach(lines -> lines.sort(Comparator.comparing(PurchaseOrderItem::getId)));

        Map<Long, Integer> receivedByMaterial = new LinkedHashMap<>();
        for (GrnItem receivedLine : receivedLines) {
            if (receivedLine.getMaterial() == null || receivedLine.getReceivedQuantity() == null) {
                continue;
            }
            receivedByMaterial.merge(receivedLine.getMaterial().getId(), receivedLine.getReceivedQuantity(), Integer::sum);
        }

        List<String> overReceipts = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : receivedByMaterial.entrySet()) {
            List<PurchaseOrderItem> lines = orderLinesByMaterial.get(entry.getKey());
            if (lines == null) {
                continue;
            }
            int ordered = lines.stream().mapToInt(PurchaseOrderReceiptReconciler::orderedQuantity).sum();
            int already = lines.stream().mapToInt(PurchaseOrderReceiptReconciler::receivedQuantity).sum();
            int total = already + entry.getValue();
            if (total > ordered) {
                overReceipts.add(describeOverReceipt(purchaseOrder, lines.get(0), ordered, already,
                        entry.getValue(), total));
            }
        }

        boolean overReceipt = !overReceipts.isEmpty();
        if (overReceipt && !overReceiptAllowed) {
            throw new InvalidRequestException(String.join(" ", overReceipts)
                    + " If the delivery really did exceed the order, send the note again with "
                    + "allowOverReceipt set, which records the excess and marks the note as an "
                    + "acknowledged over-receipt.");
        }

        int matchedLines = 0;
        for (GrnItem receivedLine : receivedLines) {
            if (receivedLine.getMaterial() == null) {
                continue;
            }
            List<PurchaseOrderItem> lines = orderLinesByMaterial.get(receivedLine.getMaterial().getId());
            if (lines == null) {
                continue;
            }
            matchedLines++;
            // The order is the authority on how much was ordered, so the receipt's own copy of
            // that figure is taken from it rather than from whatever the client typed.
            receivedLine.setOrderedQuantity(
                    lines.stream().mapToInt(PurchaseOrderReceiptReconciler::orderedQuantity).sum());
        }

        for (Map.Entry<Long, Integer> entry : receivedByMaterial.entrySet()) {
            List<PurchaseOrderItem> lines = orderLinesByMaterial.get(entry.getKey());
            if (lines == null) {
                continue;
            }
            allocate(lines, entry.getValue());
            purchaseOrderItemRepository.saveAll(lines);
        }

        PurchaseOrderStatus movedTo = moveStatus(purchaseOrder, orderLines, goodsReceivedNote);
        return new ReceiptOutcome(matchedLines, overReceipt, movedTo);
    }

    /**
     * Spreads a received quantity across the order lines for one material.
     *
     * <p>An order may carry the same material on more than one line, and a receipt does not say
     * which of them it answers. The quantity fills the lines in id order, each taking what it
     * still has outstanding, and anything left once they are all met goes onto the last line.
     * Putting the excess somewhere specific rather than nowhere is what keeps the sum of the
     * lines equal to the quantity actually received.
     */
    private static void allocate(List<PurchaseOrderItem> lines, int quantity) {
        int remaining = quantity;
        for (PurchaseOrderItem line : lines) {
            if (remaining <= 0) {
                return;
            }
            int outstanding = orderedQuantity(line) - receivedQuantity(line);
            if (outstanding <= 0) {
                continue;
            }
            int take = Math.min(outstanding, remaining);
            line.setReceivedQuantity(receivedQuantity(line) + take);
            remaining -= take;
        }
        if (remaining > 0) {
            PurchaseOrderItem last = lines.get(lines.size() - 1);
            last.setReceivedQuantity(receivedQuantity(last) + remaining);
        }
    }

    /**
     * Works the order's status out from its lines and moves it if it has changed.
     *
     * <p>The move is derived, so it has no actor. It is written to the shared status trail as a
     * {@code SYSTEM} transition whose note names the receipt that caused it; that receipt records
     * who filed it, which is as close to a person as a derived transition honestly gets.
     *
     * @return The status moved to, or null if it did not move.
     */
    private PurchaseOrderStatus moveStatus(PurchaseOrder purchaseOrder,
                                           List<PurchaseOrderItem> orderLines,
                                           GoodsReceivedNote goodsReceivedNote) {
        PurchaseOrderStatus current = purchaseOrder.getStatus();
        if (current == null || !MOVABLE.contains(current)) {
            return null;
        }

        PurchaseOrderStatus derived = derive(orderLines);
        if (derived == null || derived == current) {
            return null;
        }

        purchaseOrder.setStatus(derived);
        purchaseOrderRepository.save(purchaseOrder);
        statusTransitionRecorder.recordSystemChange(
                PurchaseOrderService.HISTORY_ENTITY_TYPE,
                purchaseOrder.getId(),
                purchaseOrder.getOrganization(),
                current.name(),
                derived.name(),
                "Derived from the quantities received against this order, most recently by goods "
                        + "receipt " + goodsReceivedNote.getGrnNumber() + ".");
        logger.info("Purchase order {} moved from {} to {} on goods receipt {}",
                purchaseOrder.getPoNumber(), current, derived, goodsReceivedNote.getGrnNumber());
        return derived;
    }

    /**
     * The status an order's lines say it is in, or null when nothing has been received against
     * it and the lines therefore say nothing about receipt at all.
     */
    private static PurchaseOrderStatus derive(List<PurchaseOrderItem> orderLines) {
        boolean anyReceived = false;
        boolean allMet = true;
        for (PurchaseOrderItem line : orderLines) {
            int received = receivedQuantity(line);
            if (received > 0) {
                anyReceived = true;
            }
            if (received < orderedQuantity(line)) {
                allMet = false;
            }
        }
        if (!anyReceived) {
            return null;
        }
        return allMet ? PurchaseOrderStatus.FULLY_RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
    }

    private static String describeOverReceipt(PurchaseOrder purchaseOrder, PurchaseOrderItem line,
                                              int ordered, int already, int arriving, int total) {
        String material = line.getMaterial() != null && line.getMaterial().getMaterialName() != null
                ? line.getMaterial().getMaterialName()
                : "material " + (line.getMaterial() != null ? line.getMaterial().getId() : "unknown");
        return "Purchase order " + purchaseOrder.getPoNumber() + " orders " + ordered + " of "
                + material + ", " + already + " has already been received against it, and this note "
                + "receives a further " + arriving + ", which would take it to " + total + ".";
    }

    private static int orderedQuantity(PurchaseOrderItem line) {
        return line.getOrderedQuantity() != null ? line.getOrderedQuantity() : 0;
    }

    private static int receivedQuantity(PurchaseOrderItem line) {
        return line.getReceivedQuantity() != null ? line.getReceivedQuantity() : 0;
    }
}
