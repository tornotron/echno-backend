package org.tornotron.echno_backend.common.ratelimit;

import io.lettuce.core.RedisClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tornotron.echno_backend.projectInviteCode.InviteCodeRedemptionProperties;

/**
 * Chooses where attempt counts live, from the same single switch as every other piece of
 * cross-replica state: {@code echno.cache.provider}.
 *
 * <p>The two conditions are written as exact opposites rather than as a primary and a
 * {@code @ConditionalOnMissingBean} fallback, because ordering between bean methods of an
 * ordinary configuration class is not something to rest a security control on. Exactly one of
 * them matches for any value of the property.
 *
 * <p>The redis bean depends on the {@code RedisClient} that {@code RedisConfig} publishes under
 * the identical condition, so the two are created together or not at all.
 */
@Configuration(proxyBeanMethods = false)
public class AttemptBucketsConfig {

    @Bean
    @ConditionalOnProperty(name = "echno.cache.provider", havingValue = "redis")
    public AttemptBuckets redisAttemptBuckets(RedisClient bucket4jRedisClient,
                                              InviteCodeRedemptionProperties inviteCodeRedemption) {
        return new RedisAttemptBuckets(bucket4jRedisClient, inviteCodeRedemption.getWindow());
    }

    @Bean
    @ConditionalOnExpression("!'${echno.cache.provider:caffeine}'.equals('redis')")
    public AttemptBuckets inProcessAttemptBuckets() {
        return new InProcessAttemptBuckets();
    }
}
