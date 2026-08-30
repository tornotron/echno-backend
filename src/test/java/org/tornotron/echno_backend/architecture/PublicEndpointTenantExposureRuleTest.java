package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.architecture.fixtures.PublicEndpointReachabilityFixtures;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the gate, not for the code the gate guards.
 *
 * <p>{@link PublicEndpointTenantExposureTest} finds nothing in the production code, which is
 * the answer everybody wants and also the answer a broken rule gives. Its walk follows three
 * hops a plain call graph does not have, each added because this codebase routes tenant reads
 * through it, and each is the kind of thing a later tidy-up removes without anything going
 * red. So each hop is driven here over a planted violation shaped like the production code
 * that uses it, and each is paired with the case it must not report, because a walk that says
 * yes to everything is no more useful than one that says no to everything.
 *
 * <p>The fixtures are not Spring beans and the walk is driven directly rather than through
 * {@code publicEndpoints}, so nothing here can be picked up by a component scan. What
 * {@code permitAll} means is a separate, pure decision and is tested as one below.
 */
class PublicEndpointTenantExposureRuleTest {

    /**
     * Instance fields rather than statics, so the imported graph is collectable as soon as the
     * test that used it is done. It is one small package and the import is cheap; holding a
     * class graph in a {@code static final} for the life of the JVM is what the architecture
     * tests use ArchUnit's own cache to avoid.
     */
    private final JavaClasses fixtures = new ClassFileImporter()
            .importPackages("org.tornotron.echno_backend.architecture.fixtures");

    private final PublicEndpointTenantExposureTest.Reachability reachability =
            new PublicEndpointTenantExposureTest.Reachability(fixtures);

    // -------------------------------------------------------------------------------
    // Hole 1: the event-publish hop
    // -------------------------------------------------------------------------------

    /**
     * The hop the gate was blind to. The handler names neither the repository nor the listener;
     * the only edge between them is an event, and {@code publishEvent} belongs to Spring, so a
     * walk that stops at the framework boundary drops it and reports green.
     */
    @Test
    void followsAPublishedEventIntoItsListener() {
        List<String> path = pathFrom(PublicEndpointReachabilityFixtures.PublishesAnEvent.class, "handle");

        assertThat(path)
                .as("the walk has to cross publishEvent and land in the listener that reads")
                .isNotEmpty();
        assertThat(path).last().asString().contains("findAll");
        assertThat(String.join(" -> ", path)).contains("ReadsTenantRowsOnCommit.on");
    }

    /**
     * And the listener it must not follow. An {@code @Async} listener runs on a thread the
     * unscoped declaration never reaches, so the load boundary refuses the read rather than
     * letting it through: reporting it would be a false alarm, and false alarms are how a
     * ratchet gets switched off.
     */
    @Test
    void doesNotFollowAnAsynchronousListener() {
        assertThat(pathFrom(PublicEndpointReachabilityFixtures.PublishesAnAsyncOnlyEvent.class, "handle"))
                .as("an @Async listener is behind a thread hand-off, where the load boundary is "
                        + "back on and the read is refused")
                .isEmpty();
    }

    // -------------------------------------------------------------------------------
    // Hole 2: reads that are not Spring Data repositories
    // -------------------------------------------------------------------------------

    @Test
    void countsAnEntityManagerQueryAsAReadOfTenantData() {
        assertThat(pathFrom(PublicEndpointReachabilityFixtures.QueriesThroughTheEntityManager.class, "handle"))
                .last().asString().contains("createNativeQuery");
    }

    @Test
    void countsAJdbcTemplateStatementAsAReadOfTenantData() {
        assertThat(pathFrom(PublicEndpointReachabilityFixtures.QueriesThroughJdbc.class, "handle"))
                .last().asString().contains("queryForObject");
    }

    /**
     * Holding an {@code EntityManager} is not the same as querying through one. Without this,
     * every class that touches the persistence context for configuration reads as a database
     * access and the rule reports paths nobody can act on.
     */
    @Test
    void doesNotCountAnEntityManagerCallThatIssuesNoStatement() {
        assertThat(pathFrom(PublicEndpointReachabilityFixtures.OnlyUnwrapsTheEntityManager.class, "handle"))
                .isEmpty();
    }

    // -------------------------------------------------------------------------------
    // The minor gap: a call typed as an interface
    // -------------------------------------------------------------------------------

    /**
     * A call through an interface resolves to the interface's own declaration, which has no
     * body. Without the descent the walk stops there, one hop short of the class that reads.
     */
    @Test
    void descendsFromAnInterfaceIntoItsImplementation() {
        List<String> path = pathFrom(PublicEndpointReachabilityFixtures.CallsThroughAnInterface.class, "handle");

        assertThat(path).isNotEmpty();
        assertThat(String.join(" -> ", path)).contains("TenantReadingAdapter.read");
    }

    // -------------------------------------------------------------------------------
    // The rule is not vacuous in the other direction either
    // -------------------------------------------------------------------------------

    @Test
    void reportsNothingForWorkThatReachesNoDatabase() {
        assertThat(pathFrom(PublicEndpointReachabilityFixtures.ReadsNothing.class, "handle")).isEmpty();
    }

    // -------------------------------------------------------------------------------
    // What counts as public
    // -------------------------------------------------------------------------------

    /**
     * A compound grant is reachable without authentication by its first term. Reading it as
     * protected because it is not the exact string {@code permitAll()} would shrink what the
     * rule looks at without anyone noticing, which is the one thing a ratchet cannot survive.
     */
    @Test
    void treatsACompoundOrAnonymousGrantAsPublic() {
        assertThat(PublicEndpointTenantExposureTest.isPermitAllExpression("permitAll()")).isTrue();
        assertThat(PublicEndpointTenantExposureTest.isPermitAllExpression("permitAll")).isTrue();
        assertThat(PublicEndpointTenantExposureTest.isPermitAllExpression(" permitAll( ) ")).isTrue();
        assertThat(PublicEndpointTenantExposureTest
                .isPermitAllExpression("permitAll() or hasRole('ADMIN')")).isTrue();
        assertThat(PublicEndpointTenantExposureTest.isPermitAllExpression("isAnonymous()")).isTrue();

        assertThat(PublicEndpointTenantExposureTest.isPermitAllExpression("isAuthenticated()")).isFalse();
        assertThat(PublicEndpointTenantExposureTest
                .isPermitAllExpression("hasRole('ORG_ADMIN')")).isFalse();
        assertThat(PublicEndpointTenantExposureTest
                .isPermitAllExpression("@orgSecurity.isMemberOfCurrentTenant()")).isFalse();
    }

    private List<String> pathFrom(Class<?> owner, String method) {
        JavaMethod start = fixtures.get(owner).getMethod(method);
        Optional<List<String>> path = reachability.pathToTenantData(start);
        return path.orElseGet(List::of);
    }
}
