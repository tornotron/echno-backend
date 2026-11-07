package org.tornotron.echno_backend.common.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every {@link IdentityProvider} adapter has to do. Each adapter gets one subclass, so the fake
 * the rest of the suite relies on cannot drift away from the real thing.
 *
 * <p>The abstract methods are the provider's other side: what a person does there, and what can only
 * be observed there. The port has no read for membership or sent email, on purpose, so each adapter's
 * subclass answers them from the provider directly.
 */
public abstract class IdentityProviderContract {

    private static final Duration A_WEEK = Duration.ofDays(7);
    /** Shared across subclasses and their providers' lifetimes, so no two tests reuse an organization. */
    private static final AtomicLong NEXT_ORGANIZATION = new AtomicLong(System.nanoTime() % 1_000_000 + 1);

    private EchnoOrgId organization;

    protected abstract IdentityProvider provider();

    protected abstract IdentityTokenMapper tokenMapper();

    /** An account that already exists at the provider. */
    protected abstract ExternalUserId existingUser(String email, boolean emailVerified, String name);

    protected abstract boolean isMember(ExternalUserId user, EchnoOrgId organization);

    /** The invited person follows the link and finishes, as whoever owns the invited address. */
    protected abstract ExternalUserId acceptInvitation(ExternalInvitationId invitation, String email);

    protected abstract void letExpire(ExternalInvitationId invitation);

    protected abstract int invitationEmailsTo(String email);

    protected abstract int passwordResetEmailsTo(String email);

    /** The person signs in, leaving a session behind. */
    protected abstract void startSession(ExternalUserId user, String email);

    protected abstract boolean hasSession(ExternalUserId user);

    /** A token this provider would issue for the subject, with these extra claims on it. */
    protected abstract Jwt.Builder tokenBuilder();

    /** The claims that say the person used a second factor, in this provider's own form. */
    protected abstract Map<String, Object> multiFactorClaims();

    @BeforeEach
    void createAnOrganization() {
        organization = anotherOrganization();
    }

    private EchnoOrgId anotherOrganization() {
        EchnoOrgId created = new EchnoOrgId(NEXT_ORGANIZATION.incrementAndGet());
        provider().createOrganization(created, "Organization " + created.value());
        return created;
    }

    private static String anEmail() {
        return "person-" + UUID.randomUUID() + "@example.test";
    }

    private ExternalUserId aUser() {
        return existingUser(anEmail(), true, "A Person");
    }

    @Test
    void creatingAnOrganizationReturnsTheProvidersId() {
        EchnoOrgId fresh = new EchnoOrgId(NEXT_ORGANIZATION.incrementAndGet());

        assertThat(provider().createOrganization(fresh, "Fresh").value()).isNotBlank();
    }

    @Test
    void addingAMemberMakesThemOne() {
        ExternalUserId user = aUser();

        provider().addMember(user, organization);

        assertThat(isMember(user, organization)).isTrue();
    }

    @Test
    void addingAMemberTwiceIsHarmless() {
        ExternalUserId user = aUser();

        provider().addMember(user, organization);
        provider().addMember(user, organization);

        assertThat(isMember(user, organization)).isTrue();
    }

    @Test
    void addingAMemberToAnOrganizationThatDoesNotExistIsRefused() {
        ExternalUserId user = aUser();
        EchnoOrgId missing = new EchnoOrgId(NEXT_ORGANIZATION.incrementAndGet());

        assertThatThrownBy(() -> provider().addMember(user, missing)).isInstanceOf(RuntimeException.class);
    }

    /** An organization whose id is the start of another's must never be taken for that other one. */
    @Test
    void aMissingOrganizationIsNotMistakenForOneWhoseIdStartsTheSame() {
        ExternalUserId user = aUser();
        EchnoOrgId missing = new EchnoOrgId(NEXT_ORGANIZATION.incrementAndGet());
        EchnoOrgId neighbour = new EchnoOrgId(missing.value() * 10);
        provider().createOrganization(neighbour, "Neighbour");
        provider().addMember(user, neighbour);

        assertThatThrownBy(() -> provider().addMember(aUser(), missing)).isInstanceOf(RuntimeException.class);
        provider().removeMember(user, missing);
        provider().deleteOrganization(missing);

        assertThat(isMember(user, neighbour)).isTrue();
    }

    @Test
    void removingAMemberEndsOnlyThatMembership() {
        ExternalUserId user = aUser();
        EchnoOrgId other = anotherOrganization();
        provider().addMember(user, organization);
        provider().addMember(user, other);

        provider().removeMember(user, organization);

        assertThat(isMember(user, organization)).isFalse();
        assertThat(isMember(user, other)).isTrue();
    }

    @Test
    void removingAMemberFromAnOrganizationThatDoesNotExistIsHarmless() {
        ExternalUserId user = aUser();
        EchnoOrgId missing = new EchnoOrgId(NEXT_ORGANIZATION.incrementAndGet());

        assertThatCode(() -> provider().removeMember(user, missing)).doesNotThrowAnyException();
    }

    @Test
    void deletingAnOrganizationRemovesIt() {
        ExternalUserId user = aUser();

        provider().deleteOrganization(organization);

        assertThatThrownBy(() -> provider().addMember(user, organization)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void deletingAnOrganizationThatDoesNotExistIsHarmless() {
        EchnoOrgId missing = new EchnoOrgId(NEXT_ORGANIZATION.incrementAndGet());

        assertThatCode(() -> provider().deleteOrganization(missing)).doesNotThrowAnyException();
    }

    @Test
    void anInvitationStartsPendingAndSendsOneEmail() {
        String email = anEmail();

        ExternalInvitationId invitation = provider().invite(email, organization, null, A_WEEK);

        assertThat(provider().invitationState(invitation))
                .contains(InvitationStatus.of(InvitationState.PENDING));
        assertThat(invitationEmailsTo(email)).isEqualTo(1);
    }

    @Test
    void anInvitationToAnOrganizationThatDoesNotExistIsRefused() {
        EchnoOrgId missing = new EchnoOrgId(NEXT_ORGANIZATION.incrementAndGet());

        assertThatThrownBy(() -> provider().invite(anEmail(), missing, null, A_WEEK))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void anInvitationNamesWhoSentIt() {
        ExternalUserId inviter = aUser();
        provider().addMember(inviter, organization);

        ExternalInvitationId invitation = provider().invite(anEmail(), organization, inviter, A_WEEK);

        assertThat(provider().invitationState(invitation))
                .contains(InvitationStatus.of(InvitationState.PENDING));
    }

    @Test
    void resendingAPendingInvitationSendsAnotherEmail() {
        String email = anEmail();
        ExternalInvitationId invitation = provider().invite(email, organization, null, A_WEEK);

        provider().resendInvitation(invitation);

        assertThat(invitationEmailsTo(email)).isEqualTo(2);
        assertThat(provider().invitationState(invitation))
                .contains(InvitationStatus.of(InvitationState.PENDING));
    }

    @Test
    void acceptingAnInvitationReportsThePerson() {
        String email = anEmail();
        ExternalInvitationId invitation = provider().invite(email, organization, null, A_WEEK);

        ExternalUserId accepted = acceptInvitation(invitation, email);

        assertThat(provider().invitationState(invitation)).contains(InvitationStatus.acceptedBy(accepted));
        assertThat(provider().profile(accepted)).get().satisfies(profile -> {
            assertThat(profile.email()).isEqualToIgnoringCase(email);
            assertThat(profile.emailVerified()).isTrue();
        });
    }

    @Test
    void anExistingAccountCanAcceptAnInvitationToASecondOrganization() {
        String email = anEmail();
        ExternalUserId existing = existingUser(email, true, "A Person");
        provider().addMember(existing, organization);
        EchnoOrgId second = anotherOrganization();

        ExternalInvitationId invitation = provider().invite(email, second, null, A_WEEK);

        assertThat(provider().invitationState(invitation))
                .contains(InvitationStatus.of(InvitationState.PENDING));
        assertThat(acceptInvitation(invitation, email)).isEqualTo(existing);
        assertThat(provider().invitationState(invitation)).contains(InvitationStatus.acceptedBy(existing));
    }

    @Test
    void anInvitationDoesNotMakeAnExistingAccountAMember() {
        String email = anEmail();
        ExternalUserId existing = existingUser(email, true, "A Person");

        provider().invite(email, organization, null, A_WEEK);

        assertThat(isMember(existing, organization)).isFalse();
    }

    @Test
    void revokingAnInvitationEndsIt() {
        ExternalInvitationId invitation = provider().invite(anEmail(), organization, null, A_WEEK);

        provider().revokeInvitation(invitation);

        assertThat(provider().invitationState(invitation))
                .contains(InvitationStatus.of(InvitationState.REVOKED));
    }

    @Test
    void aRevokedInvitationCannotBeResentOrRevokedAgain() {
        ExternalInvitationId invitation = provider().invite(anEmail(), organization, null, A_WEEK);
        provider().revokeInvitation(invitation);

        assertThatThrownBy(() -> provider().resendInvitation(invitation))
                .isInstanceOf(InvitationNotPendingException.class);
        assertThatThrownBy(() -> provider().revokeInvitation(invitation))
                .isInstanceOf(InvitationNotPendingException.class);
    }

    @Test
    void anAcceptedInvitationCannotBeRevoked() {
        String email = anEmail();
        ExternalInvitationId invitation = provider().invite(email, organization, null, A_WEEK);
        acceptInvitation(invitation, email);

        assertThatThrownBy(() -> provider().revokeInvitation(invitation))
                .isInstanceOf(InvitationNotPendingException.class);
    }

    @Test
    void anExpiredInvitationSaysSoAndCannotBeResent() {
        ExternalInvitationId invitation = provider().invite(anEmail(), organization, null, A_WEEK);

        letExpire(invitation);

        assertThat(provider().invitationState(invitation))
                .contains(InvitationStatus.of(InvitationState.EXPIRED));
        assertThatThrownBy(() -> provider().resendInvitation(invitation))
                .isInstanceOf(InvitationNotPendingException.class);
    }

    @Test
    void anUnknownInvitationHasNoState() {
        ExternalInvitationId unknown = new ExternalInvitationId(UUID.randomUUID().toString());

        assertThat(provider().invitationState(unknown)).isEmpty();
    }

    @Test
    void aProfileSaysWhatTheProviderHolds() {
        String email = anEmail();
        ExternalUserId user = existingUser(email, false, "Priya Raman");

        assertThat(provider().profile(user)).get().satisfies(profile -> {
            assertThat(profile.email()).isEqualToIgnoringCase(email);
            assertThat(profile.emailVerified()).isFalse();
            assertThat(profile.name()).isEqualTo("Priya Raman");
        });
    }

    @Test
    void anUnknownPersonHasNoProfile() {
        assertThat(provider().profile(new ExternalUserId(UUID.randomUUID().toString()))).isEmpty();
    }

    @Test
    void aPasswordResetSendsAnEmail() {
        String email = anEmail();
        ExternalUserId user = existingUser(email, true, "A Person");

        provider().sendPasswordReset(user);

        assertThat(passwordResetEmailsTo(email)).isEqualTo(1);
    }

    @Test
    void endingSessionsLeavesNone() {
        String email = anEmail();
        ExternalUserId user = existingUser(email, true, "A Person");
        startSession(user, email);
        assertThat(hasSession(user)).isTrue();

        provider().endSessions(user);

        assertThat(hasSession(user)).isFalse();
    }

    @Test
    void capabilitiesAreAlwaysAnswered() {
        assertThat(provider().capabilities()).isNotNull();
        assertThat(provider().capabilities().selfRegistration()).isNotNull();
    }

    @Test
    void aTokenBecomesItsSubjectAndAnIssuerToLookItUpBy() {
        Jwt token = tokenBuilder().subject("subject-1").build();

        Principal principal = tokenMapper().toPrincipal(token);

        assertThat(principal.subject()).isEqualTo("subject-1");
        assertThat(principal.issuer()).isNotBlank();
        assertThat(tokenMapper().provider()).isNotBlank();
    }

    @Test
    void anEmailInTheTokenIsCarriedAndItsAbsenceIsFine() {
        assertThat(tokenMapper().toPrincipal(tokenBuilder().subject("s").claim("email", "a@example.test").build())
                .email()).isEqualTo("a@example.test");
        assertThat(tokenMapper().toPrincipal(tokenBuilder().subject("s").build()).email()).isNull();
    }

    @Test
    void aMissingSignInMethodCountsAsTheWeakest() {
        assertThat(tokenMapper().toPrincipal(tokenBuilder().subject("s").build()).authentication())
                .isEqualTo(SignInMethod.UNKNOWN);
    }

    @Test
    void aSecondFactorIsRecognised() {
        Jwt.Builder builder = tokenBuilder().subject("s");
        multiFactorClaims().forEach(builder::claim);

        assertThat(tokenMapper().toPrincipal(builder.build()).authentication()).isEqualTo(SignInMethod.MFA);
    }

    /** A token with the claims every provider shares, for {@link #tokenBuilder()} to start from. */
    protected static Jwt.Builder aToken(String issuer) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }
}
