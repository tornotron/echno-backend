package org.tornotron.echno_backend.modules.access.matrix.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@value CachedAccessFactsSource#CACHE} cache on the in-process provider: expired a few
 * minutes after it is written, whatever the application's general cache spec says.
 *
 * <p>The matrix screens evict what they change, but some changes happen elsewhere, such as
 * someone being added to a project, and nothing evicts for those. A short life bounds how long
 * such a change takes to show. {@code RedisConfig} holds the same bound on the shared provider.
 */
@Configuration(proxyBeanMethods = false)
public class AccessFactsCacheConfiguration {

    public static final Duration TIME_TO_LIVE = Duration.ofMinutes(5);
    static final long MAXIMUM_SIZE = 50_000;

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> accessFactsCaffeineCache() {
        return manager -> manager.registerCustomCache(CachedAccessFactsSource.CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(TIME_TO_LIVE)
                        .maximumSize(MAXIMUM_SIZE)
                        .build());
    }
}
