package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;

/**
 * Membership is asked once per request and holdings come from the cache after the first load;
 * facts are memoized for a request; eviction runs after commit and not on rollback; a failing
 * cache falls back to the database; the cached form survives the Redis JSON serializer.
 */
class CachedAccessFactsSourceTest {

    private static final AccessSubject ALICE = new AccessSubject(7L, 1L, 11L);
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final AccessFacts HOLDINGS = new AccessFacts(true,
            Map.of("wbs:edit", EnumSet.of(AccessScope.ASSIGNED_PROJECTS, AccessScope.OWN),
                    "finance:read", EnumSet.of(AccessScope.ALL)),
            Map.of(100L, Map.of("assets:read", EnumSet.of(AccessScope.ALL))),
            Set.of(100L), Set.of(CUSTOMER), Set.of(900L));

    private final DatabaseAccessFactsSource database = mock(DatabaseAccessFactsSource.class);
    private final CachedAccessFactsSource source =
            new CachedAccessFactsSource(database, new ConcurrentMapCacheManager());

    /** A transaction manager with no resource behind it, so commit and rollback are all there is. */
    private final TransactionTemplate transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    });

    @AfterEach
    void endTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void aMember() {
        when(database.isActiveMember(ALICE)).thenReturn(true);
        when(database.loadHoldings(ALICE)).thenReturn(HOLDINGS);
    }

    private static void beginARequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test
    void someoneWhoIsNotAnActiveMemberHoldsNothingAndIsNeverLoaded() {
        when(database.isActiveMember(ALICE)).thenReturn(false);

        assertThat(source.load(ALICE)).isEqualTo(AccessFacts.noAccess());
        verify(database, never()).loadHoldings(ALICE);
    }

    @Test
    void acrossRequestsHoldingsAreLoadedOnceButMembershipIsAskedEachTime() {
        aMember();

        assertThat(source.load(ALICE)).isEqualTo(HOLDINGS);
        beginARequest();
        assertThat(source.load(ALICE)).isEqualTo(HOLDINGS);

        verify(database, times(1)).loadHoldings(ALICE);
        verify(database, times(2)).isActiveMember(ALICE);
    }

    @Test
    void aMemberRemovedAfterCachingLosesAccessOnTheNextRequest() {
        when(database.isActiveMember(ALICE)).thenReturn(true, false);
        when(database.loadHoldings(ALICE)).thenReturn(HOLDINGS);
        beginARequest();
        assertThat(source.load(ALICE).active()).isTrue();

        beginARequest();

        assertThat(source.load(ALICE)).isEqualTo(AccessFacts.noAccess());
    }

    @Test
    void aListOfOneHundredRecordsRunsTheFactsQueriesOnce() {
        aMember();
        beginARequest();
        MatrixAccessService engine = new MatrixAccessService(source);
        AccessPermission read = AccessPermission.of("finance:read");

        for (long record = 0; record < 100; record++) {
            engine.check(ALICE, read, RecordFacts.of(7L, record));
        }

        verify(database, times(1)).isActiveMember(ALICE);
        verify(database, times(1)).loadHoldings(ALICE);
    }

    @Test
    void aCommittedEvictionMakesTheNextReadReload() {
        aMember();
        source.load(ALICE);

        transactions.executeWithoutResult(status -> source.evictAfterCommit(7L, 1L));
        source.load(ALICE);

        verify(database, times(2)).loadHoldings(ALICE);
    }

    @Test
    void anEvictionWaitsForTheCommit() {
        aMember();
        source.load(ALICE);

        transactions.executeWithoutResult(status -> {
            source.evictAfterCommit(7L, 1L);
            source.load(ALICE);
            verify(database, times(1)).loadHoldings(ALICE);
        });
    }

    @Test
    void aRolledBackChangeEvictsNothing() {
        aMember();
        source.load(ALICE);

        transactions.executeWithoutResult(status -> {
            source.evictAfterCommit(7L, 1L);
            source.evictAllAfterCommit();
            status.setRollbackOnly();
        });
        source.load(ALICE);

        verify(database, times(1)).loadHoldings(ALICE);
    }

    @Test
    void evictingEveryoneMakesTheNextReadReload() {
        aMember();
        source.load(ALICE);

        transactions.executeWithoutResult(status -> source.evictAllAfterCommit());
        source.load(ALICE);

        verify(database, times(2)).loadHoldings(ALICE);
    }

    @Test
    void aCommittedEvictionAlsoDropsTheCurrentRequestsMemo() {
        aMember();
        beginARequest();
        source.load(ALICE);

        transactions.executeWithoutResult(status -> source.evictAfterCommit(7L, 1L));
        source.load(ALICE);

        verify(database, times(2)).isActiveMember(ALICE);
        verify(database, times(2)).loadHoldings(ALICE);
    }

    @Test
    void aCacheThatFailsFallsBackToTheDatabase() {
        aMember();
        CacheManager broken = mock(CacheManager.class);
        when(broken.getCache(any())).thenThrow(new IllegalStateException("cache is down"));
        CachedAccessFactsSource withBrokenCache = new CachedAccessFactsSource(database, broken);

        assertThat(withBrokenCache.load(ALICE)).isEqualTo(HOLDINGS);
        withBrokenCache.evictAfterCommit(7L, 1L);
        withBrokenCache.evictAllAfterCommit();
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
