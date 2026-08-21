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

    // Cap the container at 2 GB so CockroachDB sizes its cache and SQL memory to a
    // fraction of that (it reads the cgroup limit) instead of a quarter of the whole
    // host. On the small CI runner an unbounded container competes with the test JVM
    // for memory; the tests use tiny data, so 2 GB is ample.
    private static final CockroachContainer COCKROACH =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.4"))
                    .withCreateContainerCmdModifier(cmd ->
                            cmd.getHostConfig().withMemory(2L * 1024 * 1024 * 1024));

    static {
        COCKROACH.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", COCKROACH::getJdbcUrl);
        registry.add("spring.datasource.username", COCKROACH::getUsername);
        registry.add("spring.datasource.password", COCKROACH::getPassword);
        // Bound every statement so a lock-wait cannot hang the suite. The tests share one
        // container and run sequentially, so a single test that blocks on a row lock (for
        // example a pessimistic SELECT ... FOR UPDATE held open by a rolled-back test
        // transaction while a REQUIRES_NEW call waits on it) would otherwise stall the whole
        // run for hours. With this it aborts after 30s and that one test fails fast instead.
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '30s'");
    }
}
