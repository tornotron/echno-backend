package org.tornotron.echno_backend.billing.components;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
