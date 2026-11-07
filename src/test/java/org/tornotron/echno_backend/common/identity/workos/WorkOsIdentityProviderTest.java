package org.tornotron.echno_backend.common.identity.workos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.InvitationNotPendingException;
import org.tornotron.echno_backend.common.identity.InvitationState;
import org.tornotron.echno_backend.common.identity.InvitationStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the WorkOS adapter does beyond the port's contract. */
@ExtendWith(OutputCaptureExtension.class)
class WorkOsIdentityProviderTest {

    private static final EchnoOrgId ORGANIZATION = new EchnoOrgId(41L);
    private static final Duration A_WEEK = Duration.ofDays(7);

    private final WorkOsStub stub = new WorkOsStub();
    private final List<Duration> waits = new ArrayList<>();
    private final WorkOsApi api = WorkOsApi.create(stub.settings(),
            new RetryAfterInterceptor(3, Duration.ofSeconds(10), waits::add));
    private final WorkOsIdentityProvider provider = new WorkOsIdentityProvider(api, Clock.systemUTC(), d -> { });

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void anOrganizationCarriesTheEchnoIdAsItsExternalId() {
        provider.createOrganization(ORGANIZATION, "Example Organization");

        assertThat(api.organizationByExternalId("41")).get()
                .extracting(WorkOsApi.Organization::externalId).isEqualTo("41");
    }

    @Test
    void creatingAnOrganizationAgainReturnsTheFirstOne() {
        String first = provider.createOrganization(ORGANIZATION, "Example Organization").value();

        assertThat(provider.createOrganization(ORGANIZATION, "Example Organization").value()).isEqualTo(first);
    }

    @Test
    void revokingAnInvitationAlsoDeletesThePendingMembershipAndLeavesTheUser() {
        provider.createOrganization(ORGANIZATION, "Example Organization");
        ExternalInvitationId invitation = provider.invite("invited@example.test", ORGANIZATION, null, A_WEEK);
        assertThat(stub.membershipsWithStatus("pending")).isEqualTo(1);

        provider.revokeInvitation(invitation);

        assertThat(stub.membershipsWithStatus("pending")).isZero();
        assertThat(stub.userByEmail("invited@example.test")).isPresent();
        assertThat(provider.invitationState(invitation)).contains(InvitationStatus.of(InvitationState.REVOKED));
    }

    @Test
    void aSecondPendingInvitationToTheSameAddressIsADuplicate() {
        provider.createOrganization(ORGANIZATION, "Example Organization");
        provider.invite("invited@example.test", ORGANIZATION, null, A_WEEK);

        assertThatThrownBy(() -> provider.invite("invited@example.test", ORGANIZATION, null, A_WEEK))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void resendingARevokedInvitationSaysItIsRevoked() {
        provider.createOrganization(ORGANIZATION, "Example Organization");
        ExternalInvitationId invitation = provider.invite("invited@example.test", ORGANIZATION, null, A_WEEK);
        provider.revokeInvitation(invitation);

        assertThatThrownBy(() -> provider.resendInvitation(invitation))
                .isInstanceOf(InvitationNotPendingException.class)
                .hasMessageContaining("REVOKED");
    }

    @Test
    void anExpiryIsWholeDaysFromOneToThirty() {
        assertThat(WorkOsIdentityProvider.expiryDays(Duration.ofDays(30))).isEqualTo(30);
        assertThat(WorkOsIdentityProvider.expiryDays(Duration.ofHours(25))).isEqualTo(2);
        assertThatThrownBy(() -> WorkOsIdentityProvider.expiryDays(Duration.ofDays(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkOsIdentityProvider.expiryDays(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRateLimitIsWaitedOutAsRetryAfterSays() {
        stub.rateLimitNext(2, "3");

        provider.createOrganization(ORGANIZATION, "Example Organization");

        assertThat(waits).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(3));
        assertThat(api.organizationByExternalId("41")).isPresent();
    }

    @Test
    void aRateLimitLongerThanTheCapGoesBackToTheCaller() {
        stub.rateLimitNext(1, "60");

        assertThatThrownBy(() -> provider.createOrganization(ORGANIZATION, "Example Organization"))
                .isInstanceOfSatisfying(WorkOsApiException.class, refused -> {
                    assertThat(refused.status()).isEqualTo(429);
                    assertThat(refused.transientFailure()).isTrue();
                });
        assertThat(waits).isEmpty();
    }

    @Test
    void retryAfterReadsSecondsAndDates() {
        assertThat(RetryAfterInterceptor.retryAfter("5")).isEqualTo(Duration.ofSeconds(5));
        assertThat(RetryAfterInterceptor.retryAfter(null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(RetryAfterInterceptor.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isEqualTo(Duration.ZERO);
    }

    @Test
    void noBearerValueOrApiKeyReachesTheLog(CapturedOutput output) {
        provider.createOrganization(ORGANIZATION, "Example Organization");
        ExternalInvitationId invitation = provider.invite("invited@example.test", ORGANIZATION, null, A_WEEK);
        provider.resendInvitation(invitation);
        provider.invitationState(invitation);
        String user = stub.addUser("person@example.test", true, "A", "Person");
        provider.sendPasswordReset(new ExternalUserId(user));
        provider.revokeInvitation(invitation);
        assertThatThrownBy(() -> provider.revokeInvitation(invitation)).isInstanceOf(InvitationNotPendingException.class);

        assertThat(output.getAll()).doesNotContain(WorkOsStub.BEARER_SENTINEL).doesNotContain(WorkOsStub.API_KEY);
    }

    @Test
    void theHealthCheckIsUpWithTheKeyAndDownWithoutIt() {
        assertThat(new WorkOsHealthIndicator(api).health().getStatus()).isEqualTo(Status.UP);

        WorkOsApi wrongKey = WorkOsApi.create(new WorkOsSettings("sk_test_wrong", "client_stub", stub.url()),
                new RetryAfterInterceptor(0, Duration.ZERO, d -> { }));
        var health = new WorkOsHealthIndicator(wrongKey).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", 401).doesNotContainValue("sk_test_wrong");
    }

    @Test
    void anUnreachableApiIsDown() {
        WorkOsApi nowhere = WorkOsApi.create(new WorkOsSettings("k", "c", URI.create("http://127.0.0.1:1")),
                new RetryAfterInterceptor(0, Duration.ZERO, d -> { }));

        assertThat(new WorkOsHealthIndicator(nowhere).health().getStatus()).isEqualTo(Status.DOWN);
    }
}
