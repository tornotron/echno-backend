package org.tornotron.echno_backend.common.identity.workos;

import org.junit.jupiter.api.AfterEach;
import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityProviderContract;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** The contract against {@link WorkOsStub}, which answers with responses recorded from WorkOS. */
class WorkOsIdentityProviderContractTest extends IdentityProviderContract {

    private final WorkOsStub stub = new WorkOsStub();
    private final WorkOsIdentityProvider provider = new WorkOsIdentityProvider(
            WorkOsApi.create(stub.settings(), new RetryAfterInterceptor(3, Duration.ofSeconds(1), d -> { })),
            Clock.systemUTC(), d -> { });

    @AfterEach
    void stop() {
        stub.close();
    }

    @Override
    protected IdentityProvider provider() {
        return provider;
    }

    @Override
    protected IdentityTokenMapper tokenMapper() {
        return new WorkOsTokenMapper(stub.settings());
    }

    @Override
    protected ExternalUserId existingUser(String email, boolean emailVerified, String name) {
        String[] parts = name.split(" ", 2);
        return new ExternalUserId(stub.addUser(email, emailVerified, parts[0], parts.length > 1 ? parts[1] : null));
    }

    @Override
    protected boolean isMember(ExternalUserId user, EchnoOrgId organization) {
        return stub.isActiveMember(user.value(), WorkOsIdentityProvider.externalId(organization));
    }

    @Override
    protected ExternalUserId acceptInvitation(ExternalInvitationId invitation, String email) {
        return new ExternalUserId(stub.accept(invitation.value()));
    }

    @Override
    protected void letExpire(ExternalInvitationId invitation) {
        stub.expire(invitation.value());
    }

    @Override
    protected int invitationEmailsTo(String email) {
        return stub.invitationEmailsTo(email);
    }

    @Override
    protected int passwordResetEmailsTo(String email) {
        return stub.passwordResetEmailsTo(email);
    }

    @Override
    protected void startSession(ExternalUserId user, String email) {
        stub.startSession(user.value());
    }

    @Override
    protected boolean hasSession(ExternalUserId user) {
        return stub.hasActiveSession(user.value());
    }

    @Override
    protected Jwt.Builder tokenBuilder() {
        return aToken(stub.settings().defaultIssuer());
    }

    @Override
    protected Map<String, Object> multiFactorClaims() {
        return Map.of();
    }

    @Override
    protected boolean carriesTokenEmail() {
        return false;
    }
}
