package org.tornotron.echno_backend.common.configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Per-request cache for the Keycloak RPT (Requesting Party Token) exchange.
 *
 * <p>The {@link RPTExchangeFilter} mints an RPT on every authenticated request by calling
 * Keycloak's token endpoint. That is a network round trip per request and a hard availability
 * and throughput dependency on Keycloak. This cache keeps the minted RPT for a short, bounded
 * window keyed on the identity of the incoming access token, so repeated requests carrying the
 * same access token reuse the same RPT instead of re-minting it.
 *
 * <p>Design mirrors {@code billing.components.SubscriptionCache}: a size-bounded, thread-safe
 * Caffeine cache. Correctness properties:
 * <ul>
 *   <li>Entries are keyed on the access token identity (its {@code jti} claim, falling back to a
 *       SHA-256 hash of the token string), so an RPT is never shared across different access
 *       tokens.</li>
 *   <li>Every entry expires after {@code min(remaining access-token lifetime, HARD_MAX_TTL)},
 *       so a permission change is picked up within at most {@code HARD_MAX_TTL}.</li>
 *   <li>A failed mint is never cached: the exception propagates and the cache stays untouched,
 *       so behaviour on failure is identical to the un-cached path.</li>
 * </ul>
 */
@Component
@Slf4j
public class RPTCache {

    /** Hard ceiling on how long any RPT may be reused, regardless of the access token's own lifetime. */
    static final Duration HARD_MAX_TTL = Duration.ofSeconds(60);

    /** Size bound so the cache cannot grow without limit under many distinct tokens. */
    private static final long MAX_SIZE = 5_000;

    private final Cache<@NonNull String, Entry> cache;

    /** Cache value: the minted RPT plus the per-entry time-to-live derived from the access token. */
    private record Entry(String rpt, long ttlNanos) {
    }

    public RPTCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfter(new Expiry<@NonNull String, @NonNull Entry>() {
                    @Override
                    public long expireAfterCreate(String key, Entry value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                        // Reading must not extend the lifetime: an RPT ages out on its original schedule.
                        return currentDuration;
                    }
                })
                .recordStats()
                .build();
    }

    /**
     * Returns a valid RPT for {@code accessToken}, minting one via {@code minter} only on a cache miss.
     *
     * <p>On a hit the cached RPT is returned and {@code minter} is not invoked, so no Keycloak call
     * is made. On a miss {@code minter} is invoked; its result is cached (bounded TTL) and returned.
     * If {@code minter} throws, the exception propagates unchanged and nothing is cached.
     *
     * @param accessToken the incoming bearer access token (the raw compact JWT, no "Bearer " prefix)
     * @param minter      supplies a freshly minted RPT; invoked only on a miss
     * @return the RPT to use for the rest of the request
     */
    public String getOrMint(String accessToken, Supplier<String> minter) {
        String key = cacheKey(accessToken);

        Entry cached = cache.getIfPresent(key);
        if (cached != null) {
            log.debug("RPT cache hit for token key {}", key);
            return cached.rpt();
        }

        log.debug("RPT cache miss for token key {}, minting", key);
        String rpt = minter.get();

        long ttlNanos = ttlNanosFor(accessToken);
        if (ttlNanos > 0) {
            cache.put(key, new Entry(rpt, ttlNanos));
        } else {
            log.debug("Access token has no remaining lifetime; not caching RPT for key {}", key);
        }
        return rpt;
    }

    /**
     * Derives a stable cache key from the access token: its {@code jti} claim when present, otherwise
     * a SHA-256 hash of the token string. The raw token is never used as the key.
     */
    static String cacheKey(String accessToken) {
        JWTClaimsSet claims = claimSet(accessToken);
        String jti = claims == null ? null : claims.getJWTID();
        if (jti != null && !jti.isBlank()) {
            return "jti:" + jti;
        }
        return "sha256:" + sha256(accessToken);
    }

    /**
     * Per-entry TTL in nanoseconds: {@code min(remaining access-token lifetime, HARD_MAX_TTL)}, clamped
     * at zero. When the token carries no readable expiry the hard max is used.
     */
    private static long ttlNanosFor(String accessToken) {
        JWTClaimsSet claims = claimSet(accessToken);
        Duration hardMax = HARD_MAX_TTL;

        if (claims == null || claims.getExpirationTime() == null) {
            return hardMax.toNanos();
        }

        Date exp = claims.getExpirationTime();
        Duration remaining = Duration.between(Instant.now(), exp.toInstant());
        if (remaining.isNegative() || remaining.isZero()) {
            return 0L;
        }
        Duration ttl = remaining.compareTo(hardMax) < 0 ? remaining : hardMax;
        return ttl.toNanos();
    }

    /** Parses the JWT claims without verifying the signature; returns {@code null} on any parse failure. */
    @Nullable
    private static JWTClaimsSet claimSet(String accessToken) {
        try {
            return JWTParser.parse(accessToken).getJWTClaimsSet();
        } catch (Exception e) {
            log.debug("Access token is not a parseable JWT; falling back to token hash: {}", e.getMessage());
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec, so this is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void logStats() {
        CacheStats stats = cache.stats();
        log.debug("RPT cache stats - Hit rate: {}, Evictions: {}",
                stats.hitRate(), stats.evictionCount());
    }
}
