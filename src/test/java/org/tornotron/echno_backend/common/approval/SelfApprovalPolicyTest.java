package org.tornotron.echno_backend.common.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rule itself, away from any document: two different people is the ordinary case and passes
 * silently, one person doing both halves is refused, a system administrator doing both halves is
 * allowed and reported back as a self-approval so the caller can record it.
 *
 * <p>The rest of these cover what the rule does when it cannot simply compare two employee ids,
 * which is where it used to fail open. A person recorded on one side by their user id and on the
 * other by their employee id is still recognised as one person. A document whose raiser shares no
 * identity with the approver is allowed through, because refusing would strand every document
 * raised before its raiser was stamped. An approval that names no approver at all is refused,
 * which is the one place the rule fails closed.
 */
@ExtendWith(MockitoExtension.class)
class SelfApprovalPolicyTest {

    private static final ApprovalParty DRAFTER = ApprovalParty.ofUser(11L);
    private static final ApprovalParty APPROVER = ApprovalParty.ofUser(12L);
    private static final String DOCUMENT = "Stock adjustment with ID 5";

    @Mock private OrganizationSecurityService orgSecurity;

    private SelfApprovalPolicy policy() {
        return new SelfApprovalPolicy(orgSecurity);
    }

    @Test
    void twoDifferentPeopleIsNotASelfApprovalAndNeedsNoRoleAtAll() {
        assertThat(policy().checkSelfApproval(DRAFTER, APPROVER, DOCUMENT)).isFalse();
        verifyNoInteractions(orgSecurity);
    }

    @Test
    void approvingYourOwnDocumentIsRefused() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> policy().checkSelfApproval(DRAFTER, DRAFTER, DOCUMENT))
                .withMessageContaining(DOCUMENT)
                .withMessageContaining("someone other than whoever raised the document");
    }

    @Test
    void aSystemAdministratorMayApproveTheirOwnDocumentAndItComesBackAsASelfApproval() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(true);

        assertThat(policy().checkSelfApproval(DRAFTER, DRAFTER, DOCUMENT)).isTrue();
    }

    /**
     * The case the attendance module was blind to: the raiser held no employee record when they
     * filed, so only their user id names them, while by the time they approve the same session
     * resolves to both ids. One shared identity is enough to recognise them.
     */
    @Test
    void oneSharedIdentityIsEnoughToRecogniseTheSamePersonAcrossTheTwoSpaces() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> policy().checkSelfApproval(
                        new ApprovalParty(70L, null), new ApprovalParty(70L, 8L), DOCUMENT))
                .withMessageContaining("someone other than whoever raised the document");
    }

    /** And the other way round: matching employee ids settle it even with no user id on the raiser. */
    @Test
    void matchingEmployeeIdsAreStillAMatchWhenTheRaiserHasNoUserId() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> policy().checkSelfApproval(
                        new ApprovalParty(null, 8L), new ApprovalParty(70L, 8L), DOCUMENT));
    }

    /** Two people are two people whichever space they are compared in. */
    @Test
    void twoDifferentPeopleWithBothIdsIsStillTheOrdinaryCase() {
        assertThat(policy().checkSelfApproval(
                new ApprovalParty(70L, 8L), new ApprovalParty(71L, 9L), DOCUMENT)).isFalse();
        verifyNoInteractions(orgSecurity);
    }

    /**
     * A raiser recorded before the user id was kept, decided by an approver who has no employee
     * record, shares no identity with them. That cannot be compared, and refusing would leave the
     * document with no route to approval at all, so it goes through. The policy logs that the
     * check was not made; what is asserted here is that it is not silently treated as a match.
     */
    @Test
    void aRaiserThatSharesNoIdentityWithTheApproverIsLetThroughRatherThanStranded() {
        assertThat(policy().checkSelfApproval(
                new ApprovalParty(null, 8L), ApprovalParty.ofUser(70L), DOCUMENT)).isFalse();
        verifyNoInteractions(orgSecurity);
    }

    @Test
    void aDocumentThatNamesNoRaiserHasNothingToCompareAndIsLetThrough() {
        assertThat(policy().checkSelfApproval(new ApprovalParty(null, null), APPROVER, DOCUMENT))
                .isFalse();
        verifyNoInteractions(orgSecurity);
    }

    @Test
    void aNullRaiserIsTreatedTheSameAsOneThatNamesNobody() {
        assertThat(policy().checkSelfApproval(null, APPROVER, DOCUMENT)).isFalse();
        verifyNoInteractions(orgSecurity);
    }

    /**
     * The one refusal that is about the approver rather than the comparison. An approval that
     * resolves to nobody would post an entry with a null approver on it, so it is not an approval,
     * and it is certainly not evidence that a second person agreed.
     */
    @Test
    void anApproverWhoResolvesToNobodyIsRefusedRatherThanWavedThrough() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> policy().checkSelfApproval(
                        DRAFTER, new ApprovalParty(null, null), DOCUMENT))
                .withMessageContaining(DOCUMENT)
                .withMessageContaining("resolves to no user of this organization");
        verifyNoInteractions(orgSecurity);
    }

    @Test
    void anUnidentifiedApproverIsRefusedEvenWhenTheRaiserIsUnknownToo() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> policy().checkSelfApproval(null, null, DOCUMENT));
        verifyNoInteractions(orgSecurity);
    }
}
