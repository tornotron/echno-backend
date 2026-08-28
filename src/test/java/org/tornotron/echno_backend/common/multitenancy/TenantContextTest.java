package org.tornotron.echno_backend.common.multitenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The scope states, and in particular the difference between the three declared ones and the
 * fourth state that is no declaration at all. That fourth state is what #507 was: it read as
 * "no tenant" and both isolation mechanisms gave up on it.
 */
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void nothingIsDeclaredOnAFreshThread() {
        assertThat(TenantContext.isScopeDeclared()).isFalse();
        assertThat(TenantContext.isUnscopedDeclared()).isFalse();
        assertThat(TenantContext.getUnscopedReason()).isNull();
    }

    @Test
    void anOrganizationIdIsADeclaration() {
        TenantContext.setCurrentOrgId(7L);

        assertThat(TenantContext.isScopeDeclared()).isTrue();
    }

    @Test
    void aBypassIsADeclaration() {
        TenantContext.setBypass(true);

        assertThat(TenantContext.isScopeDeclared()).isTrue();
    }

    @Test
    void anUnscopedDeclarationIsADeclarationAndKeepsItsReason() {
        TenantContext.declareUnscoped("startup, before any organization exists");

        assertThat(TenantContext.isScopeDeclared()).isTrue();
        assertThat(TenantContext.isUnscopedDeclared()).isTrue();
        assertThat(TenantContext.getUnscopedReason()).isEqualTo("startup, before any organization exists");
    }

    @Test
    void anUnscopedDeclarationNeedsAReason() {
        assertThatIllegalArgumentException().isThrownBy(() -> TenantContext.declareUnscoped(null));
        assertThatIllegalArgumentException().isThrownBy(() -> TenantContext.declareUnscoped("  "));

        assertThat(TenantContext.isUnscopedDeclared()).isFalse();
    }

    @Test
    void clearingTheUnscopedDeclarationLeavesTheRestAlone() {
        TenantContext.setCurrentOrgId(7L);
        TenantContext.setBypass(true);
        TenantContext.declareUnscoped("temporary");

        TenantContext.clearUnscoped();

        assertThat(TenantContext.isUnscopedDeclared()).isFalse();
        assertThat(TenantContext.getCurrentOrgId()).isEqualTo(7L);
        assertThat(TenantContext.isBypassed()).isTrue();
    }

    @Test
    void clearRemovesEveryPartOfTheScope() {
        TenantContext.setCurrentOrgId(7L);
        TenantContext.setBypass(true);
        TenantContext.declareUnscoped("temporary");

        TenantContext.clear();

        assertThat(TenantContext.getCurrentOrgId()).isNull();
        assertThat(TenantContext.isBypassed()).isFalse();
        assertThat(TenantContext.isUnscopedDeclared()).isFalse();
        assertThat(TenantContext.isScopeDeclared()).isFalse();
    }
}
