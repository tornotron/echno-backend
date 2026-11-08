package org.tornotron.echno_backend.purchaseOrder;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;

/**
 * Who may change a purchase order once it exists.
 *
 * <p>Raising an order and editing its header and lines are open to {@code system-admin} and
 * {@code project-manager} (#855). Approving it is not: every status change, approval included,
 * belongs to {@code system-admin}, the role the status endpoint is guarded on. The header update
 * also carries a status field, and the line endpoints can rewrite what an approved order buys,
 * so the controller guard alone would let a project manager approve an order by editing it, or
 * change one after somebody else approved it. This class closes both routes:
 *
 * <ul>
 *   <li>a status change through the header update needs {@code system-admin}, exactly as it does
 *       through the status endpoint; sending the status the order already holds is not a change;
 *   <li>an order that has left {@link PurchaseOrderStatus#DRAFT} can be edited, header or lines,
 *       only by {@code system-admin}.
 * </ul>
 *
 * <p>A system admin is unaffected by either rule.
 */
@Component
public class PurchaseOrderEditPolicy {

    /** The role that approves an order and moves it through its lifecycle. */
    static final String APPROVER_ROLE = "system-admin";

    private final OrganizationSecurityService orgSecurity;

    public PurchaseOrderEditPolicy(OrganizationSecurityService orgSecurity) {
        this.orgSecurity = orgSecurity;
    }

    /**
     * Refuses an edit to an order that is no longer a draft, unless the caller may approve orders.
     *
     * @throws AccessDeniedException if the order has left DRAFT and the caller is not a system admin.
     */
    public void requireEditable(PurchaseOrder order) {
        if (order.getStatus() == PurchaseOrderStatus.DRAFT || isApprover()) {
            return;
        }
        throw new AccessDeniedException("Purchase order " + order.getPoNumber() + " is "
                + order.getStatus() + ". Once an order leaves DRAFT only a system admin may change it.");
    }

    /**
     * Refuses a status change by a caller who may not approve orders.
     *
     * @throws AccessDeniedException if {@code requested} differs from the order's status and the
     *     caller is not a system admin.
     */
    public void requireStatusChangeAllowed(PurchaseOrder order, PurchaseOrderStatus requested) {
        if (requested == null || requested == order.getStatus() || isApprover()) {
            return;
        }
        throw new AccessDeniedException("Moving purchase order " + order.getPoNumber() + " from "
                + order.getStatus() + " to " + requested + " needs the system-admin role. A project "
                + "manager may raise and edit a draft order but not approve or move it.");
    }

    private boolean isApprover() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(APPROVER_ROLE);
    }
}
