package org.tornotron.echno_backend.common.multitenancy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.UnscopedTenantAccessException;
import org.tornotron.echno_backend.organization.Organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The policy the two isolation mechanisms now share. The point of the counter is that it is not
 * rate limited: the log is sampled so a loop cannot bury the report, and the count has to stay
 * true anyway or the dashboard that decides when the transaction boundary can move to DENY is
 * reading a sampled number as a total.
 */
class UnscopedAccessGuardTest {

    private SimpleMeterRegistry registry;

    private UnscopedAccessGuard guard(String loadBoundary, String transactionBoundary) {
        return guard(loadBoundary, transactionBoundary, 60);
    }

    private UnscopedAccessGuard guard(String loadBoundary, String transactionBoundary, long warnInterval) {
        registry = new SimpleMeterRegistry();
        return new UnscopedAccessGuard(registry, loadBoundary, transactionBoundary, warnInterval);
    }

    private double count(String boundary) {
        return registry.find("echno.multitenancy.unscoped").tag("boundary", boundary).counters()
                .stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @Test
    void allowProceedsAndRecordsNothing() {
        UnscopedAccessGuard guard = guard("ALLOW", "ALLOW");

        assertThatCode(() -> guard.onUnscopedLoad(Organization.class)).doesNotThrowAnyException();
        assertThatCode(() -> guard.onUnscopedTransaction("Svc.method()")).doesNotThrowAnyException();

        assertThat(registry.find("echno.multitenancy.unscoped").counters()).isEmpty();
    }

    @Test
    void warnProceedsButCounts() {
        UnscopedAccessGuard guard = guard("WARN", "WARN");

        assertThatCode(() -> guard.onUnscopedLoad(Organization.class)).doesNotThrowAnyException();
        assertThatCode(() -> guard.onUnscopedTransaction("Svc.method()")).doesNotThrowAnyException();

        assertThat(count("load")).isEqualTo(1);
        assertThat(count("transaction")).isEqualTo(1);
    }

    @Test
    void denyRefusesAndNamesTheEntity() {
        UnscopedAccessGuard guard = guard("DENY", "DENY");

        assertThatExceptionOfType(UnscopedTenantAccessException.class)
                .isThrownBy(() -> guard.onUnscopedLoad(Organization.class))
                .withMessageContaining("Organization")
                .withMessageContaining("no tenant scope declared");

        assertThatExceptionOfType(UnscopedTenantAccessException.class)
                .isThrownBy(() -> guard.onUnscopedTransaction("Svc.method()"))
                .withMessageContaining("Svc.method()");
    }

    @Test
    void theTwoBoundariesAreConfiguredIndependently() {
        UnscopedAccessGuard guard = guard("DENY", "WARN");

        assertThatExceptionOfType(UnscopedTenantAccessException.class)
                .isThrownBy(() -> guard.onUnscopedLoad(Organization.class));
        assertThatCode(() -> guard.onUnscopedTransaction("Svc.method()")).doesNotThrowAnyException();

        assertThat(guard.getLoadBoundaryPolicy()).isEqualTo(UnscopedAccessPolicy.DENY);
        assertThat(guard.getTransactionBoundaryPolicy()).isEqualTo(UnscopedAccessPolicy.WARN);
    }

    @Test
    void theCounterIsNotRateLimitedEvenThoughTheLogIs() {
        UnscopedAccessGuard guard = guard("WARN", "WARN", 3600);

        for (int i = 0; i < 25; i++) {
            guard.onUnscopedLoad(Organization.class);
        }

        // A one-hour suppression window means one log line for those 25 reads. The count is what
        // says there were 25, and it is the number the decision to tighten the policy rests on.
        assertThat(count("load")).isEqualTo(25);
    }

    @Test
    void distinctCallSitesAreCountedSeparately() {
        UnscopedAccessGuard guard = guard("WARN", "WARN");

        guard.onUnscopedTransaction("First.method()");
        guard.onUnscopedTransaction("Second.method()");

        assertThat(registry.find("echno.multitenancy.unscoped")
                .tag("call_site", "First.method()").counter().count()).isEqualTo(1);
        assertThat(registry.find("echno.multitenancy.unscoped")
                .tag("call_site", "Second.method()").counter().count()).isEqualTo(1);
    }

    @Test
    void aMistypedPolicyFailsAtStartupAndNamesTheProperty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard("DNEY", "WARN"))
                .withMessageContaining("echno.multitenancy.load-boundary")
                .withMessageContaining("DNEY")
                .withMessageContaining("ALLOW, WARN, DENY");
    }

    @Test
    void aPolicyIsReadInAnyCaseSoAnEnvironmentVariableCanBeWrittenEitherWay() {
        UnscopedAccessGuard guard = guard(" deny ", "warn");

        assertThat(guard.getLoadBoundaryPolicy()).isEqualTo(UnscopedAccessPolicy.DENY);
        assertThat(guard.getTransactionBoundaryPolicy()).isEqualTo(UnscopedAccessPolicy.WARN);
    }
}
