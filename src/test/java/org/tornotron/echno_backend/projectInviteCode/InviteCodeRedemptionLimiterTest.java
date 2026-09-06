package org.tornotron.echno_backend.projectInviteCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.TooManyAttemptsException;
import org.tornotron.echno_backend.common.ratelimit.AttemptBuckets;
import org.tornotron.echno_backend.common.ratelimit.InProcessAttemptBuckets;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy the limiter enforces, against real buckets rather than a mock of them, because the
 * thing worth holding is that the allowance actually runs out and actually comes back.
 */
class InviteCodeRedemptionLimiterTest {

    private static final String CALLER = "ravi";
    private static final String ANOTHER_CALLER = "meera";

    private AttemptBuckets buckets;
    private InviteCodeRedemptionProperties properties;

    @BeforeEach
    void freshBuckets() {
        buckets = new InProcessAttemptBuckets();
        properties = new InviteCodeRedemptionProperties();
        properties.setWindow(Duration.ofHours(1));
    }

    private InviteCodeRedemptionLimiter limiter() {
        return new InviteCodeRedemptionLimiter(buckets, properties);
    }

    @Test
    void aCallerIsRefusedOnceTheirAllowanceIsSpent() {
        properties.setAttemptsPerCaller(3);
        InviteCodeRedemptionLimiter limiter = limiter();

        for (int i = 0; i < 3; i++) {
            int attempt = i;
            assertThatCode(() -> limiter.chargeAttempt(CALLER))
                    .as("attempt %d", attempt)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.chargeAttempt(CALLER))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    /**
     * The refusal has to say when it ends, or a client's only option is to keep asking, which is
     * the behaviour the limit exists to stop.
     */
    @Test
    void theRefusalCarriesHowLongToWait() {
        properties.setAttemptsPerCaller(1);
        properties.setWindow(Duration.ofHours(1));
        InviteCodeRedemptionLimiter limiter = limiter();
        limiter.chargeAttempt(CALLER);

        assertThatThrownBy(() -> limiter.chargeAttempt(CALLER))
                .isInstanceOfSatisfying(TooManyAttemptsException.class, e ->
                        assertThat(e.getRetryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofHours(1)));
    }

    /**
     * The message must not tell whoever provoked it how to pace the next run, so it says neither
     * how much allowance remains nor which of the two allowances refused.
     */
    @Test
    void theRefusalSaysNothingAboutTheAllowanceItself() {
        properties.setAttemptsPerCaller(1);
        InviteCodeRedemptionLimiter limiter = limiter();
        limiter.chargeAttempt(CALLER);

        assertThatThrownBy(() -> limiter.chargeAttempt(CALLER))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageNotContainingAny("1", "caller", "deployment", "remaining");
    }

    /**
     * One caller running their allowance down must not spend anyone else's. If it did, a single
     * account could lock out onboarding for everybody, which the per-caller allowance is not for.
     */
    @Test
    void oneCallerRunningOutDoesNotRefuseAnother() {
        properties.setAttemptsPerCaller(2);
        InviteCodeRedemptionLimiter limiter = limiter();
        limiter.chargeAttempt(CALLER);
        limiter.chargeAttempt(CALLER);
        assertThatThrownBy(() -> limiter.chargeAttempt(CALLER))
                .isInstanceOf(TooManyAttemptsException.class);

        assertThatCode(() -> limiter.chargeAttempt(ANOTHER_CALLER)).doesNotThrowAnyException();
    }

    /**
     * The point of the deployment-wide allowance: a per-caller limit is worth what another account
     * costs, so the ceiling has to hold across callers as well as within one.
     */
    @Test
    void theDeploymentAllowanceRefusesEvenACallerWithBudgetLeft() {
        properties.setAttemptsPerCaller(10);
        properties.setAttemptsPerDeployment(2);
        InviteCodeRedemptionLimiter limiter = limiter();

        limiter.chargeAttempt(CALLER);
        limiter.chargeAttempt(ANOTHER_CALLER);

        assertThatThrownBy(() -> limiter.chargeAttempt("a-third-account"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    /**
     * A caller refused by the deployment ceiling did not cause it, so their own budget is given
     * back. Otherwise a queue nobody could see would quietly spend the allowance of everyone
     * waiting in it.
     */
    @Test
    void aDeploymentRefusalDoesNotSpendTheCallersOwnAllowance() {
        properties.setAttemptsPerCaller(2);
        properties.setAttemptsPerDeployment(1);
        InviteCodeRedemptionLimiter limiter = limiter();

        limiter.chargeAttempt(CALLER);
        assertThatThrownBy(() -> limiter.chargeAttempt(ANOTHER_CALLER))
                .isInstanceOf(TooManyAttemptsException.class);

        // The second caller was refused once by the deployment ceiling, so of their own two
        // attempts none has been spent. Lifting the ceiling shows both are still there.
        properties.setAttemptsPerDeployment(0);
        assertThatCode(() -> limiter.chargeAttempt(ANOTHER_CALLER)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.chargeAttempt(ANOTHER_CALLER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.chargeAttempt(ANOTHER_CALLER))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    /**
     * A refund restores what an accepted code cost, so somebody redeeming the invitation they were
     * actually sent never approaches the limit however many times they do it.
     */
    @Test
    void anAcceptedCodeCostsNothing() {
        properties.setAttemptsPerCaller(2);
        InviteCodeRedemptionLimiter limiter = limiter();

        for (int i = 0; i < 20; i++) {
            limiter.chargeAttempt(CALLER);
            limiter.refundAttempt(CALLER);
        }

        assertThatCode(() -> limiter.chargeAttempt(CALLER)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.chargeAttempt(CALLER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.chargeAttempt(CALLER))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    /** A refund cannot lift a bucket past its capacity, so it can never manufacture allowance. */
    @Test
    void aRefundOnAnUntouchedAllowanceGrantsNothingExtra() {
        properties.setAttemptsPerCaller(1);
        InviteCodeRedemptionLimiter limiter = limiter();

        limiter.refundAttempt(CALLER);
        limiter.refundAttempt(CALLER);

        assertThatCode(() -> limiter.chargeAttempt(CALLER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.chargeAttempt(CALLER))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void zeroDisablesAnAllowanceEntirely() {
        properties.setAttemptsPerCaller(0);
        properties.setAttemptsPerDeployment(0);
        InviteCodeRedemptionLimiter limiter = limiter();

        for (int i = 0; i < 50; i++) {
            int attempt = i;
            assertThatCode(() -> limiter.chargeAttempt(CALLER))
                    .as("attempt %d", attempt)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * A bucket store that throws leaves the limit unavailable rather than the endpoint refused.
     * Failing closed here would turn an outage in something advisory into an outage in onboarding,
     * and the limit is a rate reducer over an already-authenticated endpoint, not the thing that
     * decides whether a code is valid.
     */
    @Test
    void anUnreachableBucketStoreLetsTheRequestThrough() {
        AttemptBuckets broken = (key, configuration) -> {
            throw new IllegalStateException("bucket store unreachable");
        };
        InviteCodeRedemptionLimiter limiter = new InviteCodeRedemptionLimiter(broken, properties);

        assertThatCode(() -> limiter.chargeAttempt(CALLER)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.refundAttempt(CALLER)).doesNotThrowAnyException();
    }

    /**
     * With no authentication on the context there is still a key, so the charge cannot be skipped.
     * The endpoint is authenticated, so this is the state that should not arise rather than one
     * that has to work well.
     */
    @Test
    void theCallerKeyIsNeverNull() {
        assertThat(limiter().currentCallerKey()).isNotNull().isNotBlank();
    }
}
