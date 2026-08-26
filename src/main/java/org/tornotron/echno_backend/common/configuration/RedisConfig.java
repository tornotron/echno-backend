package org.tornotron.echno_backend.common.configuration;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Optional Redis backing for cross-replica shared state, activated only when
 * {@code echno.cache.provider=redis} (env {@code ECHNO_CACHE_PROVIDER}). It supplies the
 * Redis connection and turns the Spring cache abstraction into a Redis-backed
 * {@link RedisCacheManager} so {@code @Cacheable} caches are shared by every replica instead
 * of each pod holding its own copy.
 *
 * <p>When the property is absent or set to {@code caffeine} (the default) none of these beans
 * exist: Spring Boot's cache auto-configuration keeps the in-process Caffeine cache manager
 * and no Redis connection is opened, so single-replica deployments behave exactly as before.
 *
 * <p>The {@link RedisClient} bean is the Lettuce client the Bucket4j starter picks up for its
 * Redis-backed rate limiter (see {@code bucket4j.cache-to-use=redis-lettuce}, set from the same
 * single switch by {@code EchnoCacheEnvironmentPostProcessor}). The distributed rate limiter is
 * therefore driven by the same {@code echno.cache.provider} property.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "echno.cache.provider", havingValue = "redis")
@EnableConfigurationProperties(RedisProperties.class)
@Slf4j
public class RedisConfig {

    /**
     * Standalone Lettuce connection factory built from {@code spring.data.redis.*}. Provided
     * explicitly because Spring Boot's Redis auto-configuration is excluded in
     * {@code EchnoBackendApplication}, so this bean exists only on the redis profile.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties props) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(props.getHost(), props.getPort());
        standalone.setDatabase(props.getDatabase());
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            standalone.setUsername(props.getUsername());
        }
        if (props.getPassword() != null && !props.getPassword().isBlank()) {
            standalone.setPassword(RedisPassword.of(props.getPassword()));
        }
        log.info("Redis cache provider active: connecting Spring cache to {}:{} (db {})",
                props.getHost(), props.getPort(), props.getDatabase());
        return new LettuceConnectionFactory(standalone);
    }

    /**
     * Redis-backed Spring cache manager. Keys are stored as plain strings and values as JSON.
     * The default entry TTL mirrors the previous Caffeine global spec (expireAfterAccess=3600s);
     * per-cache TTLs preserve the intent of the domain caches (the subscription cache's 5-minute
     * window) so a future {@code @Cacheable("subscriptions")} inherits the same freshness bound.
     * TTLs are expire-after-write under Redis.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put("subscriptions", base.entryTtl(Duration.ofMinutes(5)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    /**
     * Lettuce {@link RedisClient} consumed by the Bucket4j Redis (lettuce) auto-configuration so
     * rate-limit buckets live in Redis and are enforced cluster-wide. Shut down with the context.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(RedisProperties props) {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withDatabase(props.getDatabase());
        if (props.getUsername() != null && !props.getUsername().isBlank()
                && props.getPassword() != null && !props.getPassword().isBlank()) {
            uri.withAuthentication(props.getUsername(), props.getPassword().toCharArray());
        } else if (props.getPassword() != null && !props.getPassword().isBlank()) {
            uri.withPassword(props.getPassword().toCharArray());
        }
        return RedisClient.create(uri.build());
    }
}
