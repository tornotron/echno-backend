package org.tornotron.echno_backend.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.local.LocalBucketBuilder;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * {@link AttemptBuckets} held in this JVM, the default when {@code echno.cache.provider} is not
 * {@code redis}.
 *
 * <p>Backed by a bounded Caffeine cache rather than a plain map, for the reason
 * {@code UnscopedAccessGuard} bounds its own tracking map: the key is caller-influenced, so an
 * unbounded map is a leak someone can drive. Entries expire after an idle period longer than any
 * bandwidth window in use, so a bucket is only dropped once it would have refilled to full
 * anyway, and a fresh bucket starts full.
 *
 * <p>Eviction under the size cap is the one case where dropping a bucket loses something: a
 * partly spent bucket evicted early comes back full. The cap is far above the number of distinct
 * callers a deployment has, and reaching it is itself the signal that the limit wants a different
 * key rather than a larger map.
 */
@Slf4j
public class InProcessAttemptBuckets implements AttemptBuckets {

    /**
     * Distinct keys tracked at once. Each entry is one small bucket, so this bounds memory rather
     * than tuning the limit.
     */
    private static final long MAX_TRACKED_KEYS = 100_000;

    /** How long an untouched bucket is kept. */
    private static final Duration IDLE_RETENTION = Duration.ofHours(6);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterAccess(IDLE_RETENTION)
            .build();

    @Override
    public Bucket bucketFor(String key, BucketConfiguration configuration) {
        return buckets.get(key, missing -> {
            log.debug("Opening an in-process attempt bucket for {}", missing);
            LocalBucketBuilder builder = Bucket.builder();
            for (Bandwidth bandwidth : configuration.getBandwidths()) {
                builder.addLimit(bandwidth);
            }
            return builder.build();
        });
    }

    /** Visible for tests that need to observe how many keys are being tracked. */
    long trackedKeys() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
