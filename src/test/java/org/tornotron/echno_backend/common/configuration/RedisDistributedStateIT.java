package org.tornotron.echno_backend.common.configuration;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.snapshot.PlanFeatureSnapshot;
import org.tornotron.echno_backend.billing.snapshot.PlanSnapshot;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the optional Redis backing against a real Redis container. It proves the
 * two properties that make the state cross-replica:
 *
 * <ol>
 *   <li>a value written through one Redis-backed Spring cache manager is served to a second,
 *       independent cache manager (as two pods would each have) from the shared Redis;</li>
 *   <li>two independent Bucket4j proxy managers pointed at the same Redis key consume from one
 *       shared token allowance, so a rate limit is enforced cluster-wide.</li>
 * </ol>
 *
 * It also confirms {@link RedisConfig} actually creates its beans when the provider is redis.
 */
@Testcontainers
class RedisDistributedStateIT {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private final RedisConfig redisConfig = new RedisConfig();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    private String host;
    private int port;

    @BeforeEach
    void resolveEndpoint() {
        host = REDIS.getHost();
        port = REDIS.getMappedPort(6379);
    }

    @AfterEach
    void closeResources() {
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        closeables.clear();
    }

    /** A separate connection factory per manager stands in for a separate pod. */
    private RedisConnectionFactory newConnectionFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        closeables.add(factory::destroy);
        return factory;
    }

    private RedisClient newRedisClient() {
        RedisClient client = RedisClient.create(
                io.lettuce.core.RedisURI.builder().withHost(host).withPort(port).build());
        closeables.add(client::shutdown);
        return client;
    }

    @Test
    void cacheableValueIsSharedAcrossTwoCacheManagersViaRedis() {
        // Two independent Redis-backed cache managers, as two replicas would build.
        RedisCacheManager writerManager = redisConfig.cacheManager(newConnectionFactory());
        writerManager.afterPropertiesSet();
        RedisCacheManager readerManager = redisConfig.cacheManager(newConnectionFactory());
        readerManager.afterPropertiesSet();

        Cache writerCache = writerManager.getCache("subscriptions");
        Cache readerCache = readerManager.getCache("subscriptions");
        assertThat(writerCache).isNotNull();
        assertThat(readerCache).isNotNull();

        writerCache.put("user-42", "premium-plan");

        // The second manager, with its own connection, reads the value from the shared Redis.
        Cache.ValueWrapper fromReader = readerCache.get("user-42");
        assertThat(fromReader).isNotNull();
        assertThat(fromReader.get()).isEqualTo("premium-plan");
    }

    /**
     * The {@code subscriptions} cache with the value it is sized for. A subscription snapshot is
     * what the in-process cache holds, and moving it to the shared cache is what would make an
     * eviction on one replica reach the others; that only works if the value survives Redis,
     * which is the second reason the cache holds a snapshot and not the entity. Nothing about a
     * detached entity graph with lazy proxies and a bidirectional plan-to-feature relation
     * serializes.
     *
     * <p>It also pins the serializer configuration. The stock JSON value serializer has no
     * {@code JavaTimeModule}, so it cannot write the period bounds this value is mostly made of,
     * and the failure would only show the first time something real was cached.
     */
    @Test
    void aSubscriptionSnapshotIsSharedAcrossTwoCacheManagersViaRedis() {
        RedisCacheManager writerManager = redisConfig.cacheManager(newConnectionFactory());
        writerManager.afterPropertiesSet();
        RedisCacheManager readerManager = redisConfig.cacheManager(newConnectionFactory());
        readerManager.afterPropertiesSet();

        SubscriptionSnapshot snapshot = new SubscriptionSnapshot(
                101L, 27L, SubscriptionStatus.TRIALING,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-15T00:00:00Z"),
                false, null, Instant.parse("2026-01-15T09:30:00Z"), null,
                new PlanSnapshot(3L, "professional-monthly", "Professional", "For growing teams", 4,
                        new BigDecimal("4999.00"), new BigDecimal("49990.00"), "INR",
                        true, true, 14, 25, 2,
                        List.of(new PlanFeatureSnapshot(7L, 9L, "report-export", "PDF Report Export",
                                FeatureType.QUOTA, true, 500L, QuotaPeriod.MONTHLY))));

        writerManager.getCache("subscriptions").put("subscription:27", snapshot);

        Cache.ValueWrapper fromReader = readerManager.getCache("subscriptions").get("subscription:27");
        assertThat(fromReader).isNotNull();
        assertThat(fromReader.get()).isEqualTo(snapshot);
    }

    @Test
    void rateLimiterSharesOneBucketAcrossTwoProxyManagers() {
        LettuceBasedProxyManager<byte[]> proxyManagerA = lettuceProxyManager(newRedisClient());
        LettuceBasedProxyManager<byte[]> proxyManagerB = lettuceProxyManager(newRedisClient());

        Supplier<BucketConfiguration> config = () -> BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(20).refillGreedy(20, Duration.ofMinutes(1)))
                .build();

        byte[] sharedKey = "rate-limit:1.2.3.4".getBytes(StandardCharsets.UTF_8);
        BucketProxy bucketA = proxyManagerA.builder().build(sharedKey, config);
        BucketProxy bucketB = proxyManagerB.builder().build(sharedKey, config);

        // Consume 15 of the 20 tokens through the first manager.
        for (int i = 0; i < 15; i++) {
            assertThat(bucketA.tryConsume(1)).as("token %d via A", i).isTrue();
        }

        // The second manager, a separate instance, sees only the remaining 5 tokens.
        assertThat(bucketB.getAvailableTokens()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            assertThat(bucketB.tryConsume(1)).as("token %d via B", i).isTrue();
        }

        // The shared allowance is now exhausted for both managers.
        assertThat(bucketB.tryConsume(1)).isFalse();
        assertThat(bucketA.tryConsume(1)).isFalse();
    }

    @Test
    void redisProviderCreatesRedisBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(RedisConfig.class)
                .withPropertyValues(
                        "echno.cache.provider=redis",
                        "spring.data.redis.host=" + host,
                        "spring.data.redis.port=" + port)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisConfig.class);
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                    assertThat(context).hasSingleBean(RedisCacheManager.class);
                    assertThat(context).hasBean("bucket4jRedisClient");
                });
    }

    private LettuceBasedProxyManager<byte[]> lettuceProxyManager(RedisClient client) {
        return LettuceBasedProxyManager.builderFor(client)
                .withExpirationStrategy(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
                .build();
    }
}
