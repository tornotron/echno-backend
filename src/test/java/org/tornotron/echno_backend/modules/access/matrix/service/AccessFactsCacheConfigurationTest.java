package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * On the in-process provider the access facts cache expires a few minutes after writing,
 * whatever the application's general spec says.
 */
class AccessFactsCacheConfigurationTest {

    @Test
    void theAccessFactsCacheExpiresAfterWriting() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheSpecification("expireAfterAccess=3600s");

        new AccessFactsCacheConfiguration().accessFactsCaffeineCache().customize(manager);

        @SuppressWarnings("unchecked")
        Cache<Object, Object> nativeCache =
                (Cache<Object, Object>) manager.getCache(CachedAccessFactsSource.CACHE).getNativeCache();
        assertThat(nativeCache.policy().expireAfterWrite())
                .hasValueSatisfying(policy -> assertThat(policy.getExpiresAfter()).isEqualTo(Duration.ofMinutes(5)));
        assertThat(nativeCache.policy().expireAfterAccess()).isEmpty();
    }
}
