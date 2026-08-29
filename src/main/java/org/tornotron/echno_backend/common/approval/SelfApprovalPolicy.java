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
 * <p>A document that names nobody as its raiser cannot be compared against anything, so it is
 * allowed through. That is the case only for rows written before the raiser was stamped from the
 * session, since every path that creates one now records who did it.
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
     * @param raisedBy The user who raised the document, or null when the document does not say.
     * @param approver The user approving it now.
     * @param document How to name the document in the refusal, for example "Stock adjustment with ID 5".
     * @return true when the approver is approving their own document under the break-glass role,
     *         so the caller can record the approval as one; false when the two are different
     *         people, which is the ordinary case.
     * @throws InvalidRequestException if the approver raised the document and does not hold the
     *         break-glass role.
     */
    public boolean checkSelfApproval(Long raisedBy, Long approver, String document) {
        if (raisedBy == null || approver == null || !raisedBy.equals(approver)) {
            return false;
        }
        if (!orgSecurity.hasAnyOrgRoleForCurrentTenant(BREAK_GLASS_ROLE)) {
            throw new InvalidRequestException(document + " was raised by the same person who is now "
                    + "approving it. An approval is the second pair of eyes on the entry it posts, so it "
                    + "has to come from someone other than whoever raised the document. Ask another "
                    + "approver to review it, or have a system administrator approve it, which is "
                    + "recorded as a self-approval.");
        }
        log.warn("Self-approval: {} was raised and approved by user {}, allowed under the {} role",
                document, approver, BREAK_GLASS_ROLE);
        return true;
    }
}
