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
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;

import java.time.Duration;

/**
 * Per-organization cache of the active subscription, so the entitlement check on an annotated endpoint
 * does not query the subscription and its plan graph on every request.
 *
 * <p>What it holds is a {@link SubscriptionSnapshot}: an immutable copy of the row and its
 * plan, taken while the loading transaction is still open. It deliberately holds neither of
 * the two obvious alternatives.
 *
 * <p>Not the {@code Subscription} entity, which is what it used to hold. An entity in a cache
 * is detached by definition and outlives the persistence context that produced it, so anything
 * left uninitialized on it throws {@code LazyInitializationException} on a later hit, in a
 * transaction that cannot reattach it. That was survivable only as long as the loading query
 * kept fetch-joining the whole graph, which no signature enforced. It is also a mutable object
 * shared by every concurrent reader of that organization, so any write path that touched it published a
 * half-finished change to all of them at once.
 *
 * <p>Not {@code SubscriptionDto} either, which is the tempting fix. Two of its entitlement
 * flags, {@code expired} and {@code inTrial}, are comparisons against the current instant, so
 * caching them freezes them for the life of the entry and a subscription that lapses inside
 * that window keeps reporting itself unexpired. The snapshot keeps the timestamps those flags
 * are derived from and leaves the derivation to read time.
 *
 * <p>Freshness is bounded by the five minute expiry and by eviction: every write that changes
 * an organization's subscription evicts that organization's entry, and every write that changes a plan evicts
 * all of them, because a snapshot carries a copy of the plan it was taken against. The cache
 * is in process, so those evictions reach one replica. Under more than one replica the
 * remaining bound on the others is the expiry.
 */
@Component
@Slf4j
public class SubscriptionCache {

    private final Cache<@NonNull Long, SubscriptionSnapshot> cache;

    public SubscriptionCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build();
    }

    public SubscriptionSnapshot get(Long organizationId) {
        return cache.getIfPresent(organizationId);
    }

    public void put(Long organizationId, SubscriptionSnapshot subscription) {
        cache.put(organizationId, subscription);
    }

    public void evict(Long organizationId) {
        cache.invalidate(organizationId);
    }

    /**
     * Evicts an organization's entry for a write that is still in flight, both now and once the
     * surrounding transaction has finished.
     *
     * <p>Evicting only at the point of the write leaves a window: the entry is gone but the
     * change is not committed yet, so a concurrent read misses the cache, re-reads the
     * pre-change row and caches it for the rest of the TTL. An organization that had just changed plan
     * would keep the old entitlement for up to five minutes. The second eviction runs on
     * completion rather than on commit, so a rolled-back write clears anything a concurrent
     * read parked in the cache as well.
     *
     * <p>Falls back to the immediate eviction alone when no transaction is active.
     *
     * @param organizationId The ID of the organization whose entry the in-flight write invalidates.
     */
    public void evictOnWrite(Long organizationId) {
        evict(organizationId);
        againOnCompletion(() -> evict(organizationId));
    }

    /**
     * Empties the cache for a write that is still in flight, both now and once the surrounding
     * transaction has finished, on the same reasoning as {@link #evictOnWrite(Long)}.
     *
     * <p>The blunt instrument is for plan writes. A snapshot carries a copy of the plan it was
     * taken against, so changing a plan changes what every subscriber of that plan is entitled
     * to, and the entries to invalidate are not addressable by organization without a query. Plan
     * writes are rare administrative operations, so emptying the cache costs a re-read per
     * active organization rather than anything ongoing.
     */
    public void evictAllOnWrite() {
        evictAll();
        againOnCompletion(this::evictAll);
    }

    public void evictAll() {
        cache.invalidateAll();
    }

    private void againOnCompletion(Runnable eviction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                eviction.run();
            }
        });
    }

    @Scheduled(fixedRate = 60000)
    public void logStats() {
        CacheStats stats = cache.stats();
        log.debug("Subscription cache stats - Hit rate: {}, Evictions: {}",
                stats.hitRate(), stats.evictionCount());
    }
}
