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
 * Without it a multi-replica deployment multiplies every ceiling by its replica count, which for
 * a limit whose whole purpose is to bound how many values may be tried is the difference between
 * a bound and a suggestion. {@code RedisDistributedStateIT} holds the underlying property, that
 * two independent proxy managers over the same key draw on one allowance.
 *
 * <p>Redis is not authoritative state and this deliberately does not fail closed on it: if Redis
 * is unreachable the bucket4j call throws, and the limiter treats that as an unavailable limit
 * and lets the request through rather than refusing every redemption in the deployment. Availability
 * is chosen over the limit here because the limit is a rate reducer over an already-authenticated
 * endpoint, not the thing that decides whether a code is valid.
 */
@Slf4j
public class RedisAttemptBuckets implements AttemptBuckets {

    /**
     * How long Redis keeps a bucket after its last write. Past the point where the bucket has
     * refilled to full there is nothing left to remember, so the key is allowed to expire; a
     * margin is added so a bucket is never dropped while it still holds a deficit.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private final ProxyManager<String> proxyManager;

    public RedisAttemptBuckets(RedisClient redisClient, Duration longestWindow) {
        this.proxyManager = LettuceBasedProxyManager.builderFor(redisClient)
                .withExpirationStrategy(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(longestWindow.plus(EXPIRY_MARGIN)))
                .build()
                .withMapper(key -> key.getBytes(StandardCharsets.UTF_8));
        log.info("Attempt limits are backed by the shared Redis, so they are enforced across replicas");
    }

    @Override
    public Bucket bucketFor(String key, BucketConfiguration configuration) {
        return proxyManager.builder().build(key, () -> configuration);
    }
}
