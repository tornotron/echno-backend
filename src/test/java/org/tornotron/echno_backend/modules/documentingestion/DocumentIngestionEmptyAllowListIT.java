package org.tornotron.echno_backend.modules.documentingestion;

import org.springframework.test.context.TestPropertySource;

/**
 * The same property with the operator switch on and the allow-list empty. In advisory mode the
 * switch alone would open the module and its spend to every tenant, so this organization must see
 * exactly what it sees with the switch off. Poller beans may exist here, since the operator turned
 * them on; they have no queued work to act on, which {@link #nothingIsWrittenByAnyRefusedRequest()}
 * holds.
 */
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=",
        // The extract queue's schedule stays idle here: this context is cached while other tests
        // queue jobs in the same database, and with an empty allow-list it would close them.
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
class DocumentIngestionEmptyAllowListIT extends DocumentIngestionOffByDefaultIT {

    @Override
    void noPollerOrListenerBeanExists() {
        // Allowed in this state: see the class comment.
    }
}
