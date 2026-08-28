package org.tornotron.echno_backend.billing.components;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tornotron.echno_backend.billing.Subscription;

import java.time.Duration;

@Component
@Slf4j
public class SubscriptionCache {

    private final Cache<@NonNull Long, Subscription> cache;

    public SubscriptionCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build();
    }

    public Subscription get(Long userId) {
        return cache.getIfPresent(userId);
    }

    public void put(Long userId, Subscription subscription) {
        cache.put(userId, subscription);
    }

    public void evict(Long userId) {
        cache.invalidate(userId);
    }

    /**
     * Evicts a user's entry for a write that is still in flight, both now and once the
     * surrounding transaction has finished.
     *
     * <p>Evicting only at the point of the write leaves a window: the entry is gone but the
     * change is not committed yet, so a concurrent read misses the cache, re-reads the
     * pre-change row and caches it for the rest of the TTL. A user who had just changed plan
     * would keep the old entitlement for up to five minutes. The second eviction runs on
     * completion rather than on commit, so a rolled-back write clears anything a concurrent
     * read parked in the cache as well.
     *
     * <p>Falls back to the immediate eviction alone when no transaction is active.
     *
     * @param userId The ID of the user whose entry the in-flight write invalidates.
     */
    public void evictOnWrite(Long userId) {
        evict(userId);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                evict(userId);
            }
        });
    }

    public void evictAll() {
        cache.invalidateAll();
    }

    @Scheduled(fixedRate = 60000)
    public void logStats() {
        CacheStats stats = cache.stats();
        log.debug("Subscription cache stats - Hit rate: {}, Evictions: {}",
                stats.hitRate(), stats.evictionCount());
    }
}
