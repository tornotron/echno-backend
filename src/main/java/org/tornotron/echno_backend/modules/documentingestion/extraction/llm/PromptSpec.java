package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.Objects;

/**
 * A versioned, static system prompt. The version goes on every ledger row, so a change of wording is a
 * new version and old calls stay explained. The text is fixed in code: nothing from any tenant enters it,
 * and its examples are synthetic (design section 7.4).
 */
public record PromptSpec(String version, String system) {

    public PromptSpec {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(system, "system");
    }
}
