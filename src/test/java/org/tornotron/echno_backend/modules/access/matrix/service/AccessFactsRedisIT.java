package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.tornotron.echno_backend.common.configuration.RedisConfig;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * The access facts cache on a real Redis, through the application's own cache manager: holdings
 * come back whole, with their project grants, a committed eviction reaches it, and an entry
 * expires within the five minutes.
 */
@Testcontainers
class AccessFactsRedisIT {

    private static final AccessSubject ALICE = new AccessSubject(7L, 1L, 11L);
    private static final AccessFacts HOLDINGS = new AccessFacts(true,
            Map.of("wbs:edit", EnumSet.of(AccessScope.ASSIGNED_PROJECTS, AccessScope.OWN)),
            Map.of(100L, Map.of("assets:read", EnumSet.of(AccessScope.ALL))),
            Set.of(100L),
            Set.of(UUID.fromString("00000000-0000-0000-0000-00000000c001")),
            Set.of(900L));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static CachedAccessFactsSource source;

    private static final DatabaseAccessFactsSource DATABASE = mock(DatabaseAccessFactsSource.class);

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        var cacheManager = new RedisConfig().cacheManager(connections);
        cacheManager.afterPropertiesSet();
        source = new CachedAccessFactsSource(DATABASE, cacheManager);
        when(DATABASE.isActiveMember(ALICE)).thenReturn(true);
        when(DATABASE.loadHoldings(ALICE)).thenReturn(HOLDINGS);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @Test
    void holdingsSurviveRedisExpireAndCanBeEvicted() {
        assertThat(source.load(ALICE)).as("first read loads").isEqualTo(HOLDINGS);
        assertThat(source.load(ALICE)).as("second read comes back from Redis whole").isEqualTo(HOLDINGS);
        verify(DATABASE, times(1)).loadHoldings(ALICE);

        byte[] key = ("access-facts::" + CachedAccessFactsSource.key(7L, 1L)).getBytes(StandardCharsets.UTF_8);
        try (RedisConnection connection = connections.getConnection()) {
            assertThat(connection.keyCommands().ttl(key, TimeUnit.SECONDS))
                    .isPositive()
                    .isLessThanOrEqualTo(AccessFactsCacheConfiguration.TIME_TO_LIVE.toSeconds());
        }

        source.evictAfterCommit(7L, 1L);
        source.load(ALICE);
        verify(DATABASE, times(2)).loadHoldings(ALICE);
    }
}
