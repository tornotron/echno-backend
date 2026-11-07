package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * The access facts the engine reads, in three layers.
 *
 * <ul>
 *   <li>Within one request, facts are memoized, so a per-record check in a list of any length runs
 *       its queries once. Outside a request there is nothing to hang a memo on, so background work
 *       that checks many records should take one {@code filter} and apply it.
 *   <li>Whether the person is an active member is asked fresh on every request, and never cached.
 *       That is what makes removal immediate.
 *   <li>What they hold comes from the {@value #CACHE} cache, loaded on a miss. It lives five minutes
 *       on whichever provider {@code echno.cache.provider} selects.
 * </ul>
 *
 * <p>A writer evicts through {@link #evictAfterCommit} or {@link #evictAllAfterCommit}, which run
 * after its transaction commits and not at all if it rolls back. Evicting before commit would let
 * a concurrent read put the old state back for the full five minutes. A read that loaded the old
 * state just before the commit and writes it just after the eviction can still do that, and the
 * five minutes is the bound on it; membership is unaffected, since it is never cached.
 *
 * <p>The cache only saves queries, so when it fails the answer comes from the database and the
 * failure is logged. It never turns into an allow or a refusal.
 */
@Component
@Slf4j
public class CachedAccessFactsSource implements AccessFactsSource {

    public static final String CACHE = "access-facts";

    static final String MEMO = CachedAccessFactsSource.class.getName() + ".memo";

    private final DatabaseAccessFactsSource database;
    private final CacheManager cacheManager;

    public CachedAccessFactsSource(DatabaseAccessFactsSource database, CacheManager cacheManager) {
        this.database = database;
        this.cacheManager = cacheManager;
    }

    @Override
    public AccessFacts load(AccessSubject subject) {
        Map<AccessSubject, AccessFacts> memo = requestMemo();
        if (memo == null) {
            return loadFresh(subject);
        }
        AccessFacts memoized = memo.get(subject);
        if (memoized != null) {
            return memoized;
        }
        AccessFacts facts = loadFresh(subject);
        memo.put(subject, facts);
        return facts;
    }

    private AccessFacts loadFresh(AccessSubject subject) {
        if (!database.isActiveMember(subject)) {
            return AccessFacts.noAccess();
        }
        String key = key(subject.organizationId(), subject.employeeId());
        CachedHoldings cached = read(key);
        if (cached != null) {
            return cached.toFacts();
        }
        AccessFacts facts = database.loadHoldings(subject);
        write(key, facts);
        return facts;
    }

    private CachedHoldings read(String key) {
        try {
            Cache cache = cacheManager.getCache(CACHE);
            return cache == null ? null : cache.get(key, CachedHoldings.class);
        } catch (RuntimeException e) {
            log.warn("Could not read {} from the access facts cache; loading from the database", key, e);
            return null;
        }
    }

    private void write(String key, AccessFacts facts) {
        try {
            Cache cache = cacheManager.getCache(CACHE);
            if (cache != null) {
                cache.put(key, CachedHoldings.of(facts));
            }
        } catch (RuntimeException e) {
            log.warn("Could not write {} to the access facts cache", key, e);
        }
    }

    /** Drops one person's cached holdings once the current transaction commits. */
    public void evictAfterCommit(Long organizationId, Long employeeId) {
        afterCommit(() -> {
            evictNow(key(organizationId, employeeId));
            Map<AccessSubject, AccessFacts> memo = requestMemo();
            if (memo != null) {
                memo.keySet().removeIf(subject -> subject.organizationId().equals(organizationId)
                        && subject.employeeId().equals(employeeId));
            }
        });
    }

    /**
     * Drops every cached holding once the current transaction commits, for a change that reaches
     * many people at once, such as editing a role. The cache is not partitioned by organization,
     * so this clears all of them; such edits are rare and the next request reloads.
     */
    public void evictAllAfterCommit() {
        afterCommit(() -> {
            try {
                Cache cache = cacheManager.getCache(CACHE);
                if (cache != null) {
                    cache.clear();
                }
            } catch (RuntimeException e) {
                log.error("Could not clear the access facts cache; changes may take up to {} to show",
                        AccessFactsCacheConfiguration.TIME_TO_LIVE, e);
            }
            Map<AccessSubject, AccessFacts> memo = requestMemo();
            if (memo != null) {
                memo.clear();
            }
        });
    }

    private void evictNow(String key) {
        try {
            Cache cache = cacheManager.getCache(CACHE);
            if (cache != null) {
                cache.evict(key);
            }
        } catch (RuntimeException e) {
            log.error("Could not evict {} from the access facts cache; the change may take up to {} to show",
                    key, AccessFactsCacheConfiguration.TIME_TO_LIVE, e);
        }
    }

    /** Runs the action after the current transaction commits; at once when there is none. */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<AccessSubject, AccessFacts> requestMemo() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return null;
        }
        Object memo = request.getAttribute(MEMO, RequestAttributes.SCOPE_REQUEST);
        if (memo != null) {
            return (Map<AccessSubject, AccessFacts>) memo;
        }
        Map<AccessSubject, AccessFacts> created = new ConcurrentHashMap<>();
        request.setAttribute(MEMO, created, RequestAttributes.SCOPE_REQUEST);
        return created;
    }

    static String key(Long organizationId, Long employeeId) {
        return organizationId + ":" + employeeId;
    }
}
