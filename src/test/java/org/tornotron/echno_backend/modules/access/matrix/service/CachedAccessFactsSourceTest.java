package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * Membership is asked fresh every time, holdings come from the cache after the first load, and
 * eviction makes the next call reload. The cached form survives the Redis JSON serializer.
 */
class CachedAccessFactsSourceTest {

    private static final AccessSubject ALICE = new AccessSubject(7L, 1L, 11L);
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final AccessFacts HOLDINGS = new AccessFacts(true,
            Map.of("wbs:edit", EnumSet.of(AccessScope.ASSIGNED_PROJECTS, AccessScope.OWN),
                    "finance:view", EnumSet.of(AccessScope.ALL)),
            Set.of(100L), Set.of(CUSTOMER), Set.of(900L));

    private final DatabaseAccessFactsSource database = mock(DatabaseAccessFactsSource.class);
    private final CachedAccessFactsSource source =
            new CachedAccessFactsSource(database, new ConcurrentMapCacheManager());

    @Test
    void someoneWhoIsNotAnActiveMemberHoldsNothingAndIsNeverLoaded() {
        when(database.isActiveMember(ALICE)).thenReturn(false);

        assertThat(source.load(ALICE)).isEqualTo(AccessFacts.noAccess());
        verify(database, never()).loadHoldings(ALICE);
    }

    @Test
    void holdingsAreLoadedOnceButMembershipIsAskedEveryTime() {
        when(database.isActiveMember(ALICE)).thenReturn(true);
        when(database.loadHoldings(ALICE)).thenReturn(HOLDINGS);

        assertThat(source.load(ALICE)).isEqualTo(HOLDINGS);
        assertThat(source.load(ALICE)).isEqualTo(HOLDINGS);

        verify(database, times(1)).loadHoldings(ALICE);
        verify(database, times(2)).isActiveMember(ALICE);
    }

    @Test
    void aMemberRemovedAfterCachingLosesAccessAtOnce() {
        when(database.isActiveMember(ALICE)).thenReturn(true, false);
        when(database.loadHoldings(ALICE)).thenReturn(HOLDINGS);

        assertThat(source.load(ALICE).active()).isTrue();
        assertThat(source.load(ALICE)).isEqualTo(AccessFacts.noAccess());
    }

    @Test
    void evictingMakesTheNextCallReload() {
        when(database.isActiveMember(ALICE)).thenReturn(true);
        when(database.loadHoldings(ALICE)).thenReturn(HOLDINGS);

        source.load(ALICE);
        source.evict(7L, 1L);
        source.load(ALICE);
        source.evictAll();
        source.load(ALICE);

        verify(database, times(3)).loadHoldings(ALICE);
    }

    @Test
    void theCachedFormSurvivesTheRedisJsonSerializer() {
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer()
                .configure(mapper -> mapper
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

        Object read = serializer.deserialize(serializer.serialize(CachedHoldings.of(HOLDINGS)));

        assertThat(read).isInstanceOf(CachedHoldings.class);
        assertThat(((CachedHoldings) read).toFacts()).isEqualTo(HOLDINGS);
    }
}
