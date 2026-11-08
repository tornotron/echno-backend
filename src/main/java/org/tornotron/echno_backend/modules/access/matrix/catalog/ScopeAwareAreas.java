package org.tornotron.echno_backend.modules.access.matrix.catalog;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.access.api.ScopeAwareArea;

/**
 * The areas that filter their records by scope, collected from the {@link ScopeAwareArea} beans
 * at boot. Every other area takes only grants with scope {@code ALL}.
 */
@Component
public class ScopeAwareAreas {

    private final Set<String> areas;

    public ScopeAwareAreas(List<ScopeAwareArea> registered) {
        this.areas = registered.stream().map(ScopeAwareArea::area).collect(Collectors.toUnmodifiableSet());
    }

    /** Whether the area narrows its queries by scope, so a grant on it may carry any scope. */
    public boolean isScopeAware(String area) {
        return areas.contains(area);
    }

    public Set<String> all() {
        return areas;
    }
}
