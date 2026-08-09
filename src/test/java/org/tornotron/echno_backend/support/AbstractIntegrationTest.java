package org.tornotron.echno_backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real database. Starts a single
 * CockroachDB container (the engine staging and production run) once for the
 * whole test JVM and shares it across every subclass; the Testcontainers reaper
 * removes it at the end. The application's Liquibase changelog builds the schema,
 * so subclasses exercise real SQL against the same database the app uses.
 */
public abstract class AbstractIntegrationTest {

    private static final CockroachContainer COCKROACH =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.4"));

    static {
        COCKROACH.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", COCKROACH::getJdbcUrl);
        registry.add("spring.datasource.username", COCKROACH::getUsername);
        registry.add("spring.datasource.password", COCKROACH::getPassword);
    }
}
