package org.tornotron.echno_backend.common.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;

/**
 * Supplies a token bucket for a named key, so a caller counting attempts does not have to know
 * whether the count lives in this process or in the shared Redis.
 *
 * <p>Both implementations hand back a {@link Bucket}, which is the interface a local bucket and a
 * Redis-backed {@code BucketProxy} have in common. Which one is in use follows the same single
 * switch as every other piece of cross-replica state, {@code echno.cache.provider}: the default
 * {@code caffeine} keeps the counts in process, {@code redis} shares them across pods. A limit
 * held in process is still a limit, but on a multi-replica deployment it is one allowance per
 * replica rather than one allowance, so the effective ceiling is multiplied by the replica count.
 * That is the reason the redis path exists rather than a preference.
 *
 * <p>The key namespace is the caller's to choose and is expected to be prefixed, because on the
 * redis path the keys of every limiter share one keyspace.
 */
public interface AttemptBuckets {

    /**
     * The bucket for this key, created against the given configuration if it does not exist yet.
     *
     * <p>The configuration is only consulted on creation. Changing a limit therefore takes effect
     * for keys that have expired since the change, which for the windows used here is minutes.
     *
     * @param key           the bucket's identity, prefixed by the calling limiter
     * @param configuration the bandwidths to create the bucket with
     * @return the bucket, never null
     */
    Bucket bucketFor(String key, BucketConfiguration configuration);
}
