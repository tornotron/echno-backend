package org.tornotron.echno_backend.common.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.projectInviteCode.InviteCodeRedemptionLimiter;
import org.tornotron.echno_backend.projectInviteCode.InviteCodeRedemptionProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Redis-backed buckets must not dial Redis before a request asks them to.
 *
 * <p>The compose staging renders {@code ECHNO_CACHE_PROVIDER=redis} from the application deploy
 * while the redis container comes from a separate infra run, so "configured for redis, redis not up
 * yet" is an ordinary state. {@code RedisUnreachableBootIT} holds the whole application to booting
 * through it. This pins the same property on the one class responsible for it, without a context or
 * a container, so a regression is named here rather than showing up as a whole-application boot
 * failure.
 *
 * <p>It also pins the other half: once a request does arrive and Redis is still not there, the
 * failure surfaces from {@code bucketFor} and the limiter lets the request through. A limit that
 * cannot be consulted must not become an onboarding outage.
 */
class RedisAttemptBucketsLazinessTest {

    // Port 1 is reserved and nothing listens on it, so a connection attempt is refused at once
    // rather than hanging. The same address RedisUnreachableBootIT uses, for the same reason.
    private final RedisClient unreachable =
            RedisClient.create(RedisURI.builder().withHost("127.0.0.1").withPort(1).build());

    @AfterEach
    void shutdownClient() {
        unreachable.shutdown();
    }

    @Test
    void constructionDoesNotTouchRedis() {
        assertThatCode(() -> new RedisAttemptBuckets(unreachable, Duration.ofHours(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void aRequestAgainstAnUnreachableRedisFails() {
        RedisAttemptBuckets buckets = new RedisAttemptBuckets(unreachable, Duration.ofHours(1));
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(10).refillGreedy(10, Duration.ofHours(1)))
                .build();

        assertThatThrownBy(() -> buckets.bucketFor("some-key", configuration))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void theLimiterTurnsThatFailureIntoAnUnlimitedRequestRatherThanARefusal() {
        InviteCodeRedemptionProperties properties = new InviteCodeRedemptionProperties();
        InviteCodeRedemptionLimiter limiter = new InviteCodeRedemptionLimiter(
                new RedisAttemptBuckets(unreachable, Duration.ofHours(1)), properties);

        assertThatCode(() -> limiter.chargeAttempt("ravi")).doesNotThrowAnyException();
    }
}
