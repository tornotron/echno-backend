package org.tornotron.echno_backend.modules.access.matrix.service;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * The access facts the engine reads: membership fresh from the database on every call, and
 * holdings from the {@value #CACHE} cache, loaded on a miss.
 *
 * <p>Asking membership every time is what makes removal immediate; it is a single lookup by
 * primary key. The holdings are what the matrix screens change, and they evict the entries they
 * affect through {@link #evict} and {@link #evictAll}. Changes made elsewhere, such as someone
 * joining a project, are picked up when the entry expires, which the cache configuration keeps
 * short.
 */
@Component
public class CachedAccessFactsSource implements AccessFactsSource {

    public static final String CACHE = "access-facts";

    private final DatabaseAccessFactsSource database;
    private final CacheManager cacheManager;

    public CachedAccessFactsSource(DatabaseAccessFactsSource database, CacheManager cacheManager) {
        this.database = database;
        this.cacheManager = cacheManager;
    }

    @Override
    public AccessFacts load(AccessSubject subject) {
        if (!database.isActiveMember(subject)) {
            return AccessFacts.noAccess();
        }
        Cache cache = cacheManager.getCache(CACHE);
        if (cache == null) {
            return database.loadHoldings(subject);
        }
        String key = key(subject.organizationId(), subject.employeeId());
        CachedHoldings cached = cache.get(key, CachedHoldings.class);
        if (cached != null) {
            return cached.toFacts();
        }
        AccessFacts facts = database.loadHoldings(subject);
        cache.put(key, CachedHoldings.of(facts));
        return facts;
    }

    /** Drops one person's cached holdings, after their roles, groups or grants change. */
    public void evict(Long organizationId, Long employeeId) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            cache.evict(key(organizationId, employeeId));
        }
    }

    /**
     * Drops every cached holding, after a change that reaches many people at once, such as
     * editing a role or a group. The cache is not partitioned by organization, so this clears
     * all of them; such edits are rare and the next request reloads.
     */
    public void evictAll() {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            cache.clear();
        }
    }

    static String key(Long organizationId, Long employeeId) {
        return organizationId + ":" + employeeId;
    }
}
