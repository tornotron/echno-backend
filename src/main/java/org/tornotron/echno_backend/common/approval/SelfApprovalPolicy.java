package org.tornotron.echno_backend.common.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

/**
 * Segregation of duties on the approvals that post an entry: whoever raised a document is not
 * the person who approves it.
 *
 * <p>An approval is the second pair of eyes on the entry it writes. Where the same account both
 * raises and approves a document, that entry has been reviewed by nobody, and the record shows
 * an approver without showing that anyone independent agreed. The role gate on the endpoint does
 * not supply this: every approver may also draft, so a single role holder can do both halves in
 * two requests.
 *
 * <p>The rule applied here is a refusal with one recorded exception. An ordinary approver is
 * refused outright. A {@value #BREAK_GLASS_ROLE} may approve their own document, because a small
 * tenant can genuinely have one person with the authority to correct anything and a rule that
 * cannot be satisfied would leave a wrong figure standing with no route back. That exception is
 * logged and stays visible on the document, where the raiser and the approver are both recorded
 * and can be compared.
 *
 * <h2>What happens when the parties cannot be compared</h2>
 *
 * <p>A control that quietly permits whatever it cannot identify is not a control. Both callers
 * have produced documents this rule was blind to: a regularization raised by a tenant member with
 * no employee record carried no employee id, and a stock adjustment raised by a service account
 * carries no user id. In each case the comparison was skipped and the approval went through as
 * though a check had been made. The rule now splits that single silent outcome into three.
 *
 * <ul>
 *   <li><b>An approval that names no approver is refused.</b> This is the one place the rule
 *       fails closed. It is not a comparison that cannot be made, it is an entry about to be
 *       posted by nobody: the approver id the document stores would be null as well, so there
 *       would be no accountability on the posting at all. Every path that reaches here is a
 *       role-gated approve endpoint on an authenticated session, so an approver who resolves to
 *       no identity is a misconfigured account, not a legitimate caller.</li>
 *   <li><b>A raiser who cannot be compared against the approver is allowed through, and logged
 *       saying so.</b> Refusing instead would strand every document raised before its raiser was
 *       stamped, and there is no second route to approving those, which is the same dead end the
 *       break-glass exception exists to avoid. The approval proceeds, but at WARN, so an approval
 *       whose segregation-of-duties check could not be made is visible rather than
 *       indistinguishable from one that passed.</li>
 *   <li><b>Anything comparable is compared,</b> on whichever identity the two parties share.
 *       See {@link ApprovalParty}.</li>
 * </ul>
 */
@Slf4j
@Component
public class SelfApprovalPolicy {

    /** The one role that may approve its own document, and only as a recorded exception. */
    public static final String BREAK_GLASS_ROLE = "system-admin";

    private final OrganizationSecurityService orgSecurity;

    public SelfApprovalPolicy(OrganizationSecurityService orgSecurity) {
        this.orgSecurity = orgSecurity;
    }

    /**
     * Applies the rule to one approval, and reports whether it went through as a self-approval.
     *
     * @param raisedBy The person who raised the document. Null, or a party carrying no id at all,
     *                 where the document does not say who raised it.
     * @param approver The person approving it now, taken from the session.
     * @param document How to name the document in the refusal, for example "Stock adjustment with ID 5".
     * @return true when the approver is approving their own document under the break-glass role,
     *         so the caller can record the approval as one; false when the two are different
     *         people, which is the ordinary case, and also when they could not be compared.
     * @throws InvalidRequestException if the approver raised the document and does not hold the
     *         break-glass role, or if the approver resolves to no identity at all.
     */
    public boolean checkSelfApproval(ApprovalParty raisedBy, ApprovalParty approver, String document) {
        if (approver == null || approver.isUnidentified()) {
            throw new InvalidRequestException(document + " cannot be approved by this session, "
                    + "because it resolves to no user of this organization. An approval has to say "
                    + "who gave it, and this one would record nobody. Sign in with an account that "
                    + "belongs to the organization, or ask an administrator to check that this "
                    + "account has been set up in it.");
        }
        if (raisedBy == null || raisedBy.isUnidentified() || !raisedBy.isComparableWith(approver)) {
            log.warn("Self-approval check not made on {}: raiser {} and approver {} share no "
                            + "identity to compare, so the approval was allowed without the check",
                    document, raisedBy, approver);
            return false;
        }
        if (!raisedBy.isSamePersonAs(approver)) {
            return false;
        }
        if (!orgSecurity.hasAnyOrgRoleForCurrentTenant(BREAK_GLASS_ROLE)) {
            throw new InvalidRequestException(document + " was raised by the same person who is now "
                    + "approving it. An approval is the second pair of eyes on the entry it posts, so it "
                    + "has to come from someone other than whoever raised the document. Ask another "
                    + "approver to review it, or have a system administrator approve it, which is "
                    + "recorded as a self-approval.");
        }
        log.warn("Self-approval: {} was raised and approved by {}, allowed under the {} role",
                document, approver, BREAK_GLASS_ROLE);
        return true;
    }
}
