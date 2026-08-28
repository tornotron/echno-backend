package org.tornotron.echno_backend.common.multitenancy;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.stereotype.Component;

/**
 * Registers {@link TenantIsolationLoadListener} with Hibernate's event system once
 * the SessionFactory is built, so tenant isolation is enforced on every entity load.
 */
@Component
@RequiredArgsConstructor
public class TenantIsolationListenerRegistrar {

    private final EntityManagerFactory entityManagerFactory;
    private final UnscopedAccessGuard unscopedAccessGuard;

    @PostConstruct
    public void registerListener() {
        SessionFactoryImplementor sessionFactory =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry registry =
                sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_LOAD,
                new TenantIsolationLoadListener(unscopedAccessGuard));
    }
}
