package org.tornotron.echno_backend.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a {@code @PreAuthorize} expression back into the requirement it states.
 *
 * <p>The expressions used here are copied from real endpoints, so a change to the guard vocabulary
 * that this parser stops recognising shows up as a failure rather than as a silently generic
 * refusal in production.
 */
class AuthorizationRequirementTest {

    @Test
    void namesTheOrgScopedRolesAnEndpointAccepts() {
        // OrganizationWebController.partialUpdateAnOrganization
        AuthorizationRequirement requirement =
                AuthorizationRequirement.from("@orgSecurity.hasAnyOrgRole(#id, 'system-admin', 'hr-admin')");

        assertThat(requirement.isDescribable()).isTrue();
        assertThat(requirement.getOrganizationRoles()).containsExactly("system-admin", "hr-admin");
        assertThat(requirement.describe())
                .contains("the 'system-admin' or 'hr-admin' roles in this organization");
    }

    @Test
    void namesASingleOrgScopedRole() {
        // OrganizationWebController.deleteOrganization
        AuthorizationRequirement requirement =
                AuthorizationRequirement.from("@orgSecurity.hasOrgRole(#id, 'system-admin')");

        assertThat(requirement.getOrganizationRoles()).containsExactly("system-admin");
        assertThat(requirement.describe()).contains("the 'system-admin' role in this organization");
    }

    @Test
    void namesGlobalAuthorities() {
        AuthorizationRequirement requirement =
                AuthorizationRequirement.from("hasAuthority('organization:admin')");

        assertThat(requirement.getAuthorities()).containsExactly("organization:admin");
        assertThat(requirement.getOrganizationRoles()).isEmpty();
        assertThat(requirement.describe()).contains("the 'organization:admin' permission");
    }

    @Test
    void readsEveryAlternativeOfACompositeExpression() {
        // OrganizationController.deleteOrganization, the widest guard in the codebase, and the one
        // that matters most for this: its middle branch needs the permission AND membership
        // together. Offering them as two separate routes would send a caller holding only
        // 'organization:delete' back for a second refusal.
        AuthorizationRequirement requirement = AuthorizationRequirement.from(
                "@orgSecurity.hasOrgRole(#id, 'system-admin') or "
                        + "(hasAuthority('organization:delete') and @orgSecurity.isMember(#id)) or "
                        + "hasAuthority('organization:admin')");

        assertThat(requirement.getOrganizationRoles()).containsExactly("system-admin");
        assertThat(requirement.getAuthorities()).containsExactly("organization:delete", "organization:admin");

        assertThat(requirement.describe()).startsWith(
                "This action requires the 'system-admin' role in this organization, "
                        + "or the 'organization:delete' permission and membership of this organization, "
                        + "or the 'organization:admin' permission.");
    }

    @Test
    void keepsAConjunctionTogetherRatherThanOfferingItAsTwoRoutes() {
        // Nothing here is optional, so nothing should read as an alternative.
        String described = AuthorizationRequirement
                .from("hasAuthority('organization:delete') and @orgSecurity.isMember(#id)")
                .describe();

        assertThat(described).isEqualTo(
                "This action requires the 'organization:delete' permission and membership of this "
                        + "organization. Your roles are read from the session you signed in with, so if "
                        + "one was granted just now, sign out and back in to pick it up.");
        assertThat(described).doesNotContain(", or ");
    }

    @Test
    void doesNotSplitOnAnOrNestedInsideBrackets() {
        // The nested 'or' belongs to the bracketed clause; splitting on it would break the
        // surrounding 'and' apart and turn "both of these" into "either of these".
        String described = AuthorizationRequirement
                .from("(hasAuthority('a') or hasAuthority('b')) and @orgSecurity.isMemberOfCurrentTenant()")
                .describe();

        assertThat(described).contains("the 'a' or 'b' permissions and membership of this organization");
        assertThat(described).doesNotContain(", or ");
    }

    @Test
    void recognisesASelfOrRoleGuard() {
        AuthorizationRequirement requirement =
                AuthorizationRequirement.from("@orgSecurity.isSelfOrHasAnyOrgRole(#id, 'hr-admin')");

        assertThat(requirement.getOrganizationRoles()).containsExactly("hr-admin");
        assertThat(requirement.describe()).contains("that the record belongs to you");
    }

    @Test
    void tellsTheCallerToSignInAgainWhenAnOrgRoleIsWhatIsMissing() {
        // The case right after creating an organization: the role exists in Keycloak but the
        // token in hand was minted before it, so the fix is a fresh sign-in, not a new grant.
        String described = AuthorizationRequirement
                .from("@orgSecurity.hasOrgRole(#id, 'system-admin')")
                .describe();

        assertThat(described).contains("sign out and back in");
    }

    @Test
    void doesNotOfferTheSignInHintForAPurelyGlobalPermission() {
        String described = AuthorizationRequirement.from("hasAuthority('organization:admin')").describe();

        assertThat(described).doesNotContain("sign out and back in");
    }

    @Test
    void describesNothingForAnExpressionItDoesNotRecognise() {
        // isAuthenticated() names no permission, so there is nothing useful to say; the caller
        // falls back to the generic message rather than inventing one.
        AuthorizationRequirement requirement = AuthorizationRequirement.from("isAuthenticated()");

        assertThat(requirement.isDescribable()).isFalse();
        assertThat(requirement.describe()).isNull();
    }

    @Test
    void describesNothingForANullExpression() {
        // A denial raised by an AuthorizationManager written in Java carries no expression.
        AuthorizationRequirement requirement = AuthorizationRequirement.from(null);

        assertThat(requirement.isDescribable()).isFalse();
        assertThat(requirement.describe()).isNull();
        assertThat(requirement.getOrganizationRoles()).isEmpty();
        assertThat(requirement.getAuthorities()).isEmpty();
    }
}
