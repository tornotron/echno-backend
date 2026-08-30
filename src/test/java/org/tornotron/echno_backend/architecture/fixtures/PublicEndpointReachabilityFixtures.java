package org.tornotron.echno_backend.architecture.fixtures;

import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
import org.tornotron.echno_backend.category.CategoryRepository;

/**
 * Planted code for {@link org.tornotron.echno_backend.architecture.PublicEndpointTenantExposureTest}
 * to catch, and planted code for it to leave alone.
 *
 * <p>The rule it belongs to walks the production code and, today, finds nothing: the only
 * public endpoint is registration and registration reads nothing tenant-scoped. A rule in that
 * state is indistinguishable from a rule that has stopped working, and the hops it follows are
 * the kind that break silently when someone tidies the walk. So each hop gets a violation
 * written here, shaped the way the production code that uses that hop is shaped, and a test
 * that fails if the walk stops finding it.
 *
 * <p>None of this is a Spring bean. There is no {@code @RestController}, no
 * {@code @Component} and no repository interface of its own, because these classes sit under
 * the application's own package on the test classpath and anything scannable here would be
 * picked up by every {@code @SpringBootTest} in the suite. The rule's own entry point is
 * driven directly instead, which is what the walk does anyway once it has the handler.
 *
 * <p>{@link CategoryRepository} is used as the read rather than a repository declared here,
 * for the same reason: a {@code Repository} interface under this package would be found by
 * Spring Data's scan.
 */
public final class PublicEndpointReachabilityFixtures {

    private PublicEndpointReachabilityFixtures() {
    }

    /** The event the publishing fixture below constructs. */
    public static class TenantTouchingEvent {
    }

    /** An event nothing in these fixtures reads tenant data for. */
    public static class HarmlessEvent {
    }

    /**
     * The publish hop, shaped like {@code GoodsReceivedNoteService}: the handler names no
     * repository and no listener, and the only edge between it and the read is an event.
     */
    public static class PublishesAnEvent {

        private ApplicationEventPublisher publisher;

        public void handle() {
            publisher.publishEvent(new TenantTouchingEvent());
        }
    }

    /** A listener that reads tenant rows on the request thread. */
    public static class ReadsTenantRowsOnCommit {

        private CategoryRepository categoryRepository;

        @TransactionalEventListener
        public void on(TenantTouchingEvent event) {
            categoryRepository.findAll();
        }
    }

    /**
     * The same read behind an {@code @Async} listener, which is not a hazard: the hand-off
     * leaves the unscoped declaration behind, so the load boundary refuses the read rather
     * than letting it through.
     */
    public static class ReadsTenantRowsOnAnotherThread {

        private CategoryRepository categoryRepository;

        @Async
        @EventListener
        public void on(HarmlessEvent event) {
            categoryRepository.findAll();
        }
    }

    /** Publishes only the event whose listener is asynchronous. */
    public static class PublishesAnAsyncOnlyEvent {

        private ApplicationEventPublisher publisher;

        public void handle() {
            publisher.publishEvent(new HarmlessEvent());
        }
    }

    /** The {@code EntityManager} hop, shaped like {@code ReportService}. */
    public static class QueriesThroughTheEntityManager {

        private EntityManager entityManager;

        public void handle() {
            entityManager.createNativeQuery("SELECT 1 FROM category").getResultList();
        }
    }

    /** The {@code JdbcTemplate} hop, shaped like {@code DocumentNumberAllocator}. */
    public static class QueriesThroughJdbc {

        private JdbcTemplate jdbcTemplate;

        public void handle() {
            jdbcTemplate.queryForObject("SELECT 1", Long.class);
        }
    }

    /** An {@code EntityManager} call that puts no statement on the wire. */
    public static class OnlyUnwrapsTheEntityManager {

        private EntityManager entityManager;

        public void handle() {
            entityManager.getEntityManagerFactory();
        }
    }

    /** The interface hop: the caller sees this, and this reads nothing. */
    public interface TenantReadingPort {
        void read();
    }

    /** The implementation the walk has to descend into to find the read. */
    public static class TenantReadingAdapter implements TenantReadingPort {

        private CategoryRepository categoryRepository;

        @Override
        public void read() {
            categoryRepository.findAll();
        }
    }

    /** Calls the port and nothing else, so only the descent finds the read. */
    public static class CallsThroughAnInterface {

        private TenantReadingPort port;

        public void handle() {
            port.read();
        }
    }

    /** Work that reaches no database at all, and must not be reported. */
    public static class ReadsNothing {

        public void handle() {
            String.valueOf(System.nanoTime());
        }
    }
}
