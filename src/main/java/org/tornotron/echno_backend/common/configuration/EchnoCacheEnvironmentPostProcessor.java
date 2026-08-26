package org.tornotron.echno_backend.common.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Makes {@code echno.cache.provider} the single switch for cross-replica state: when it is set
 * to {@code redis}, the Bucket4j rate limiter is pointed at Redis by defaulting
 * {@code bucket4j.cache-to-use} to {@code redis-lettuce}. Together with the {@code RedisClient}
 * bean from {@code RedisConfig}, that selects the Bucket4j Lettuce (Redis) auto-configuration so
 * rate limits are enforced cluster-wide instead of per pod.
 *
 * <p>The value is added as a low-priority default, so an operator who sets
 * {@code bucket4j.cache-to-use} explicitly still wins. On the default profile
 * ({@code echno.cache.provider} unset or {@code caffeine}) this processor does nothing, leaving
 * the in-process Bucket4j buckets untouched.
 */
public class EchnoCacheEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROVIDER_PROPERTY = "echno.cache.provider";
    private static final String BUCKET4J_CACHE_PROPERTY = "bucket4j.cache-to-use";
    private static final String REDIS_LETTUCE = "redis-lettuce";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String provider = environment.getProperty(PROVIDER_PROPERTY, "caffeine");
        if (!REDIS_LETTUCE.equals(provider) && !"redis".equalsIgnoreCase(provider)) {
            return;
        }
        // Respect an explicit operator choice of Bucket4j backend.
        if (environment.getProperty(BUCKET4J_CACHE_PROPERTY) != null) {
            return;
        }
        Map<String, Object> defaults = Map.of(BUCKET4J_CACHE_PROPERTY, REDIS_LETTUCE);
        environment.getPropertySources()
                .addLast(new MapPropertySource("echnoCacheProviderDefaults", defaults));
    }

    @Override
    public int getOrder() {
        // After the standard config-data processing so echno.cache.provider is already bound.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
