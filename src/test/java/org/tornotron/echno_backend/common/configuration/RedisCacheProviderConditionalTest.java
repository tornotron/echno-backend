package org.tornotron.echno_backend.common.configuration;

import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the guard on the optional Redis backing: with the default cache provider (caffeine)
 * the {@link RedisConfig} beans are never created, so the application context starts with no
 * Redis connection factory, no Redis cache manager and no Lettuce client. This is what keeps the
 * single-replica deployments (which configure no Redis) behaving exactly as before.
 */
class RedisCacheProviderConditionalTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RedisConfig.class);

    @Test
    void noProviderConfigured_createsNoRedisBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RedisConfig.class);
            assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
            assertThat(context).doesNotHaveBean(RedisCacheManager.class);
            assertThat(context).doesNotHaveBean(RedisClient.class);
        });
    }

    @Test
    void caffeineProvider_createsNoRedisBeans() {
        runner.withPropertyValues("echno.cache.provider=caffeine").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RedisConfig.class);
            assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
            assertThat(context).doesNotHaveBean(RedisCacheManager.class);
            assertThat(context).doesNotHaveBean(RedisClient.class);
        });
    }
}
