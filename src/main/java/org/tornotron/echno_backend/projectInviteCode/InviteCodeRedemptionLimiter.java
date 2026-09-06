package org.tornotron.echno_backend.projectInviteCode;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.TooManyAttemptsException;
import org.tornotron.echno_backend.common.ratelimit.AttemptBuckets;

import java.time.Duration;

/**
 * Bounds how many invite codes may be offered for redemption.
 *
 * <p>An invite code is a bearer credential. Redemption resolves it with
 * {@code findByCode} and no organization qualifier, and it cannot have one, because the person
 * redeeming holds no membership yet: that is the state the flow exists to get them out of. The
 * guard on the endpoint, {@code isSelfUser}, is correct and deliberately says nothing about a
 * tenant. So the only thing standing between a value and membership of the organization it
 * belongs to is that the value has to be known, and that property only holds while the number of
 * values that can be offered is bounded. Nothing bounded it.
 *
 * <p>Two allowances, because they answer different questions.
 *
 * <ul>
 *   <li><b>Per caller.</b> The endpoint is authenticated, so there is a principal to key on, and
 *       it is a far better key than an address: {@code getRemoteAddr()} behind the Cloudflare
 *       tunnel and the reverse proxy is the proxy, so an address-keyed limit here would be one
 *       shared allowance for the whole deployment wearing the name of a per-client one.
 *   <li><b>Per deployment.</b> A per-caller limit is worth exactly what another account costs,
 *       and where self-registration is open that is close to nothing. This one does not move when
 *       someone brings more accounts. It is set well above any believable volume of genuine wrong
 *       codes, because someone who burns it delays everyone else until it refills, and that trade
 *       is only acceptable while the ceiling is far from ordinary traffic.
 * </ul>
 *
 * <p><b>A successful redemption is refunded.</b> A token is taken before the lookup, so an attempt
 * is paid for whether or not the code turns out to exist, and given back when the code was good.
 * A person redeeming the code they were sent therefore spends nothing at all, and the allowance is
 * in practice a budget for wrong codes. Taking the token first is what makes it a limit on
 * attempts rather than on failures: it is charged before anything is looked up, so there is no
 * window in which an unbounded number of lookups is already in flight.
 *
 * <p>This reduces a rate. It does not make a code strong, and it should not be read as having
 * closed the question: a credential worth guessing is still worth guessing more slowly. What
 * settles it is a code wide enough that guessing is not a strategy, and that is a contract change
 * across the published API, {@code echno-core} and the mobile client rather than a backend patch.
 */
@Slf4j
@Component
public class InviteCodeRedemptionLimiter {

    /**
     * The single deployment-wide bucket's key. A constant rather than a derived value: the point
     * of this allowance is that nothing the caller controls splits it.
     */
    private static final String DEPLOYMENT_KEY = "invite-code-redemption:deployment";

    private static final String CALLER_KEY_PREFIX = "invite-code-redemption:caller:";

    /** Used when no authentication is on the context, which on this endpoint should not happen. */
    private static final String UNAUTHENTICATED_CALLER = "unauthenticated";

    private final AttemptBuckets buckets;
    private final InviteCodeRedemptionProperties properties;

    public InviteCodeRedemptionLimiter(AttemptBuckets buckets, InviteCodeRedemptionProperties properties) {
        this.buckets = buckets;
        this.properties = properties;
    }

    /**
     * Charges one attempt to the current caller and to the deployment.
     *
     * @param callerKey the caller's identity, from {@link #currentCallerKey()}
     * @throws TooManyAttemptsException if either allowance is spent
     */
    public void chargeAttempt(String callerKey) {
        ConsumptionProbe caller = consume(CALLER_KEY_PREFIX + callerKey, properties.getAttemptsPerCaller());
        if (caller != null && !caller.isConsumed()) {
            log.warn("Invite-code redemption refused: the per-caller attempt allowance is spent");
            throw refusal(caller);
        }

        ConsumptionProbe deployment = consume(DEPLOYMENT_KEY, properties.getAttemptsPerDeployment());
        if (deployment != null && !deployment.isConsumed()) {
            // The caller's own token was already taken and their allowance is not the one that
            // refused, so give it back rather than charging them for a queue they did not cause.
            refund(CALLER_KEY_PREFIX + callerKey, properties.getAttemptsPerCaller());
            log.warn("Invite-code redemption refused: the deployment-wide attempt allowance is spent");
            throw refusal(deployment);
        }
    }

    /**
     * Gives back the attempt charged by {@link #chargeAttempt}, for a redemption that succeeded.
     *
     * <p>Called after the code has been accepted, so it never runs on a wrong code. Adding tokens
     * cannot take a bucket past its capacity, so a refund on an untouched bucket is a no-op.
     */
    public void refundAttempt(String callerKey) {
        refund(CALLER_KEY_PREFIX + callerKey, properties.getAttemptsPerCaller());
        refund(DEPLOYMENT_KEY, properties.getAttemptsPerDeployment());
    }

    /**
     * The key the current request's attempts are counted against: the authenticated principal.
     *
     * <p>Read once and passed in, rather than resolved separately by the charge and the refund,
     * so the two cannot end up reading different keys.
     */
    public String currentCallerKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return UNAUTHENTICATED_CALLER;
        }
        return authentication.getName();
    }

    /**
     * @return the probe, or null when this allowance is switched off or its store is unreachable
     */
    private ConsumptionProbe consume(String key, int capacity) {
        if (capacity <= 0) {
            return null;
        }
        try {
            return bucket(key, capacity).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            // A shared bucket store that cannot be reached leaves the limit unavailable. Refusing
            // every redemption in the deployment on that basis would turn an outage in something
            // advisory into an outage in onboarding, so the request proceeds and the failure is
            // reported rather than absorbed.
            log.error("Attempt bucket {} could not be read, redemption proceeds unlimited: {}", key, e.getMessage(), e);
            return null;
        }
    }

    private void refund(String key, int capacity) {
        if (capacity <= 0) {
            return;
        }
        try {
            bucket(key, capacity).addTokens(1);
        } catch (RuntimeException e) {
            log.error("Attempt bucket {} could not be refunded: {}", key, e.getMessage(), e);
        }
    }

    private Bucket bucket(String key, int capacity) {
        Duration window = properties.getWindow();
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window))
                .build();
        return buckets.bucketFor(key, configuration);
    }

    private TooManyAttemptsException refusal(ConsumptionProbe probe) {
        Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
        return new TooManyAttemptsException(
                "Too many invite code attempts. Try again later.", retryAfter);
    }
}
