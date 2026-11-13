package org.tornotron.echno_backend.modules.assistant.provider;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;

/**
 * Every {@link AssistantProvider} bean, by id. Registration is by component scan: a new provider is
 * a new class and nothing else, and the planner reads {@link #all()} rather than a hard-coded list.
 * Mirrors {@code ModuleRegistry}: two providers claiming one id stop the application at startup
 * rather than letting one silently shadow the other.
 *
 * <p>Ordered by id, so the order the planner and the prompt see providers in does not depend on
 * bean registration order.
 */
@Component
public class ProviderRegistry {

    private final SortedMap<String, AssistantProvider> byId;

    @Autowired
    public ProviderRegistry(ObjectProvider<AssistantProvider> providers) {
        this(providers.orderedStream().toList());
    }

    public ProviderRegistry(List<AssistantProvider> providers) {
        SortedMap<String, AssistantProvider> registered = new TreeMap<>();
        for (AssistantProvider provider : providers) {
            if (provider.describe() == null) {
                throw new IllegalStateException(
                        "Provider bean " + provider.getClass().getName() + " described itself as null");
            }
            String id = provider.describe().id();
            AssistantProvider previous = registered.put(id, provider);
            if (previous != null) {
                throw new IllegalStateException("Two providers claim the id '" + id + "': "
                        + previous.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
        this.byId = Collections.unmodifiableSortedMap(registered);
    }

    /** Every provider, ordered by id. */
    public List<AssistantProvider> all() {
        return List.copyOf(byId.values());
    }

    public Optional<AssistantProvider> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }
}
