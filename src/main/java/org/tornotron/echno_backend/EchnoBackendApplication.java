package org.tornotron.echno_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// Spring Data Redis is on the classpath only to provide the optional cross-replica cache/
// rate-limit backing (echno.cache.provider=redis). Its auto-configuration is excluded so the
// default (caffeine) profile never creates a RedisConnectionFactory and never opens a Redis
// connection; when provider=redis, RedisConfig supplies the connection factory itself.
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class})
@EnableCaching
@EnableAsync
@EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)
public class EchnoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchnoBackendApplication.class, args);
    }

}
