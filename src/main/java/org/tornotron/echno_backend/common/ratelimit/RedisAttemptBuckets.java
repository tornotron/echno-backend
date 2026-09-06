package org.tornotron.echno_backend.common.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link AttemptBuckets} held in the shared Redis, active when {@code echno.cache.provider} is
 * {@code redis}.
 *
 * <p>This is what makes an attempt limit mean one allowance rather than one allowance per pod.
 * Without it a multi-replica deployment multiplies every ceiling by its replica count, which for a
 * limit whose purpose is to bound how many values may be tried is the difference between a bound
 * and a suggestion. {@code RedisDistributedStateIT} holds the underlying property, that two
 * independent proxy managers over the same key draw on one allowance.
 *
 * <p><b>Nothing here touches Redis until a request does.</b> The proxy manager opens a connection
 * when it is built, so building it in the constructor would make an unreachable Redis a context
 * that never refreshes: the compose staging renders {@code ECHNO_CACHE_PROVIDER=redis} from the app
 * deploy while the redis container itself comes from a separate infra run, so "configured for redis,
 * redis not up yet" is an ordinary state rather than a fault. {@code RedisUnreachableBootIT} is the
 * test that holds the whole application to that, and it is what caught this being built eagerly.
 *
 * <p>Redis is not authoritative state and this deliberately does not fail closed on it. A failure
 * to reach it propagates out of {@link #bucketFor}, and {@code InviteCodeRedemptionLimiter} treats
 * that as an unavailable limit and lets the request through rather than refusing every redemption
 * in the deployment. Availability is chosen over the limit because the limit reduces a rate over an
 * already-authenticated endpoint; it is not what decides whether a code is valid.
 */
@Slf4j
public class RedisAttemptBuckets implements AttemptBuckets {

    /**
     * How long Redis keeps a bucket after its last write. Past the point where the bucket has
     * refilled to full there is nothing left to remember, so the key is allowed to expire; a margin
     * is added so a bucket is never dropped while it still holds a deficit.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private final RedisClient redisClient;
    private final Duration longestWindow;

    private volatile ProxyManager<String> proxyManager;

    public RedisAttemptBuckets(RedisClient redisClient, Duration longestWindow) {
        this.redisClient = redisClient;
        this.longestWindow = longestWindow;
        log.info("Attempt limits will be backed by the shared Redis, so they are enforced across replicas");
    }

    @Override
    public Bucket bucketFor(String key, BucketConfiguration configuration) {
        return proxyManager().builder().build(key, () -> configuration);
    }

    /**
     * The proxy manager, built on first use.
     *
     * <p>A build that fails leaves the field null, so the next request tries again rather than
     * writing off Redis for the life of the process. That matters for the case this laziness exists
     * for: Redis arriving a few minutes after the application did.
     */
    private ProxyManager<String> proxyManager() {
        ProxyManager<String> existing = proxyManager;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (proxyManager == null) {
                proxyManager = LettuceBasedProxyManager.builderFor(redisClient)
                        .withExpirationStrategy(ExpirationAfterWriteStrategy
                                .basedOnTimeForRefillingBucketUpToMax(longestWindow.plus(EXPIRY_MARGIN)))
                        .build()
                        .withMapper(key -> key.getBytes(StandardCharsets.UTF_8));
                log.info("Attempt-limit buckets connected to the shared Redis");
            }
            return proxyManager;
        }
    }
}
