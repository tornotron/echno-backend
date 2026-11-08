package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.llm.ChatCompletionClient;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;

/**
 * Whether a call may be sent for an organization now: the shared endpoint is switched on and has a
 * key, and the organization's admin has not turned LLM reading off. Asked before every call, so turning
 * either off stops new calls at once and queued chunks wait. The operator switch and the allow-list
 * are checked before a job runs at all.
 */
@Component
public class LlmGate {

    public static final String READING_OFF = "Reading tender text with the AI service is turned off for this "
            + "organization. The job waits and resumes when an admin turns it back on.";
    public static final String NOT_CONFIGURED = "The AI service is switched off or not configured. The job waits "
            + "and resumes when it is available.";

    private final ChatCompletionClient client;
    private final IngestSettingsService settings;

    public LlmGate(ChatCompletionClient client, IngestSettingsService settings) {
        this.client = client;
        this.settings = settings;
    }

    /** Why no call may be sent, or empty when one may. */
    public Optional<String> blocked(Long organizationId) {
        if (!client.isAvailable()) {
            return Optional.of(NOT_CONFIGURED);
        }
        if (!settings.isLlmReadingOn(organizationId)) {
            return Optional.of(READING_OFF);
        }
        return Optional.empty();
    }
}
