package org.tornotron.echno_backend.compliance.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One AI decision about a candidate compliance rule, parsed from the model's JSON
 * response. {@code ruleCode} ties the decision back to the {@code ComplianceRule}
 * it refers to; {@code applies} says whether the compliance is required for the
 * project. Risk level, resolution options, rationale and phase are the model's
 * assessment; the generation service falls back to the rule's own values when the
 * model omits them. Unknown JSON fields are ignored so a model that adds keys does
 * not break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceSuggestion(
        String ruleCode,
        boolean applies,
        String riskLevel,
        List<String> resolutionOptions,
        String rationale,
        String phase
) {}
