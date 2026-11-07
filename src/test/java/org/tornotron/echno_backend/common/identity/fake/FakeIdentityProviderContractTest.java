package org.tornotron.echno_backend.common.identity.fake;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityProviderContract;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.InvitationStatus;
import org.tornotron.echno_backend.common.identity.SignInMethod;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FakeIdentityProviderContractTest extends IdentityProviderContract {

    private final FakeIdentityProvider fake = new FakeIdentityProvider();

    @Override
    protected IdentityProvider provider() {
        return fake;
    }

    @Override
    protected IdentityTokenMapper tokenMapper() {
        return new FakeIdentityTokenMapper();
    }

    @Override
    protected ExternalUserId existingUser(String email, boolean emailVerified, String name) {
        return fake.addUser(email, emailVerified, name);
    }

    @Override
    protected boolean isMember(ExternalUserId user, EchnoOrgId organization) {
        return fake.isMember(user, organization);
    }

    @Override
    protected ExternalUserId acceptInvitation(ExternalInvitationId invitation, String email) {
        return fake.acceptInvitation(invitation, email);
    }

    @Override
    protected void letExpire(ExternalInvitationId invitation) {
        fake.expireInvitation(invitation);
    }

    @Override
    protected int invitationEmailsTo(String email) {
        return fake.invitationEmailsTo(email);
    }

    @Override
    protected int passwordResetEmailsTo(String email) {
        return fake.passwordResetEmailsTo(fake.findUserByEmail(email).orElseThrow());
    }

    @Override
    protected void startSession(ExternalUserId user, String email) {
        fake.startSession(user);
    }

    @Override
    protected boolean hasSession(ExternalUserId user) {
        return fake.hasSession(user);
    }

    @Override
    protected Jwt.Builder tokenBuilder() {
        return aToken("https://fake.test/issuer");
    }

    @Override
    protected Map<String, Object> multiFactorClaims() {
        return Map.of(FakeIdentityTokenMapper.SIGN_IN_METHOD_CLAIM, SignInMethod.MFA.name());
    }

    /** Only the fake can show this: a real provider's accept flow needs a real inbox. */
    @Test
    void aDifferentAddressCanAcceptSoEchnoHasToCheckIt() {
        EchnoOrgId organization = new EchnoOrgId(1L);
        fake.createOrganization(organization, "Organization");
        ExternalInvitationId invitation =
                fake.invite("invited@corp.test", organization, null, Duration.ofDays(7));

        ExternalUserId accepted = fake.acceptInvitation(invitation, "colleague@corp.test");

        assertThat(fake.invitationState(invitation)).contains(InvitationStatus.acceptedBy(accepted));
        assertThat(fake.profile(accepted)).get()
                .satisfies(profile -> assertThat(profile.email()).isEqualTo("colleague@corp.test"));
    }

    @Test
    void anUnknownSignInMethodCountsAsTheWeakest() {
        Jwt token = tokenBuilder().subject("s").claim(FakeIdentityTokenMapper.SIGN_IN_METHOD_CLAIM, "psychic").build();

        assertThat(tokenMapper().toPrincipal(token).authentication()).isEqualTo(SignInMethod.UNKNOWN);
    }
}
