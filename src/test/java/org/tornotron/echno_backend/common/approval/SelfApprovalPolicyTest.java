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
 * allowed and reported back as a self-approval so the caller can record it, and a document that
 * names nobody as its raiser has nothing to compare and is let through.
 */
@ExtendWith(MockitoExtension.class)
class SelfApprovalPolicyTest {

    private static final Long DRAFTER = 11L;
    private static final Long APPROVER = 12L;
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

    @Test
    void aDocumentThatNamesNoRaiserHasNothingToCompareAndIsLetThrough() {
        assertThat(policy().checkSelfApproval(null, APPROVER, DOCUMENT)).isFalse();
        verifyNoInteractions(orgSecurity);
    }

    @Test
    void anUnresolvedApproverIsNotTreatedAsAMatchAgainstAnUnresolvedRaiser() {
        assertThat(policy().checkSelfApproval(null, null, DOCUMENT)).isFalse();
        verifyNoInteractions(orgSecurity);
    }
}
