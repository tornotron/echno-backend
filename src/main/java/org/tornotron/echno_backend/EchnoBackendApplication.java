package org.tornotron.echno_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)
public class EchnoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchnoBackendApplication.class, args);
    }

}
