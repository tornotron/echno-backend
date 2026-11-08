package org.tornotron.echno_ingestfixtures.clean.modules.documentingestion.apply;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
@PreAuthorize("isAuthenticated()")
public class GuardedApplier {
}
