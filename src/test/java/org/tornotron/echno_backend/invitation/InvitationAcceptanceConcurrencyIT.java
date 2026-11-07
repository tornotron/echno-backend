package org.tornotron.echno_backend.invitation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Two first requests at once, as an app's parallel calls after sign-in make: one employee, one user. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InvitationAcceptanceConcurrencyIT extends InvitationITSupport {

    @Autowired
    private IdentityLinkService links;
    @Autowired
    private IdentityTokenMapper mapper;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void twoFirstRequestsMakeOneEmployee() throws Exception {
        String email = "both-at-once-" + System.nanoTime() + "@example.test";
        TransactionTemplate committed = new TransactionTemplate(transactions);
        UUID invitation = committed.execute(status -> {
            organization = anotherOrganization("Concurrent");
            TenantContext.setCurrentOrgId(organization.getId());
            signedInAs(OrgRole.SYSTEM_ADMIN);
            return invitations.invite(invitation(email)).id();
        });
        committed.executeWithoutResult(status -> delivery.send(invitation));
        ExternalUserId account = provider.acceptInvitation(new ExternalInvitationId(providerIdOf(invitation)), email);
        Principal principal = new Principal(mapper.issuer(), account.value(), null, SignInMethod.UNKNOWN);
        TenantContext.clear();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> requests = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            requests.add(pool.submit(() -> {
                start.await();
                return links.reconcile(principal);
            }));
        }
        start.countDown();
        for (Future<?> request : requests) {
            request.get();
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users_table WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM employee e JOIN users_table u ON u.id = e.user_id WHERE u.email = ?
                """, Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, invitation))
                .isEqualTo("ACCEPTED");
    }
}
