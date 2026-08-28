package org.tornotron.echno_backend.compliance.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns one chat-completions response body into the list of compliance decisions it was
 * asked for, or refuses it.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>Everything here is a pure function of the response text and the rules that were sent,
 * so it can be tested against canned provider responses with no network, no key and no
 * Spring context. The behaviour being tested (recognising an answer the token cap cut
 * short) is exactly the behaviour that is otherwise only observable by making a real call
 * with a large enough rule catalogue.
 *
 * <h2>The three truncation checks</h2>
 *
 * <p>None of them needs to know what the token ceiling actually is, which matters because
 * that number has not been measured and measuring it needs a key that is not configured
 * yet. They read the failure out of the response instead:
 *
 * <ol>
 *   <li><b>The stop reason.</b> {@code choices[0].finish_reason} of {@code length} (or
 *       {@code max_tokens}) is the provider stating that it stopped at the cap rather
 *       than at the end of its answer. This signal was previously not read at all.</li>
 *   <li><b>The shape of the JSON.</b> An array that opens and never closes, or one that
 *       closes but does not parse. Finding the <em>matching</em> bracket rather than the
 *       last bracket in the text is what makes this visible: in a response cut off
 *       mid-element the last {@code ]} closes some element's inner
 *       {@code resolutionOptions} array, so a slice ending there is a fragment that looks
 *       plausible and is not.</li>
 *   <li><b>Coverage.</b> The prompt asks for one element per candidate rule, so a rule
 *       that comes back unassessed means the answer stopped early even when it happened
 *       to stop on a valid bracket and the provider reported no stop reason.</li>
 * </ol>
 *
 * <p>Each refusal quotes the configured cap and the reported {@code completion_tokens},
 * so the per-rule token cost can be read off a real failure rather than measured
 * separately.
 */
final class ComplianceResponseReader {

    /** Stop reasons that mean the model was cut off at the token cap, lower-cased. */
    private static final Set<String> TRUNCATED_STOP_REASONS = Set.of("length", "max_tokens");

    /** How many missing rule codes to name in a coverage failure before trailing off. */
    private static final int MAX_MISSING_CODES_REPORTED = 5;

    /** Longest excerpt of the model's own words to put in an error message. */
    private static final int MAX_EXCERPT_LENGTH = 200;

    private final ObjectMapper objectMapper;
    private final int maxTokens;

    ComplianceResponseReader(ObjectMapper objectMapper, int maxTokens) {
        this.objectMapper = objectMapper;
        this.maxTokens = maxTokens;
    }

    /** What one chat completion said, beyond the assistant text itself. */
    private record Envelope(String text, String finishReason, Integer completionTokens) {}

    /**
     * Reads the decisions out of a chat-completions response body.
     *
     * @param responseJson  the raw response body from the endpoint
     * @param candidateRules the rules the prompt asked about, used to check coverage
     * @return one suggestion per candidate rule
     * @throws ComplianceAiException when the answer was cut short, unparseable, or did
     *                               not cover every candidate rule
     */
    List<ComplianceSuggestion> read(String responseJson, List<ComplianceRule> candidateRules) {
        Envelope envelope = parseEnvelope(responseJson);
        rejectTruncatedStopReason(envelope, candidateRules.size());
        List<ComplianceSuggestion> suggestions = parseSuggestions(envelope);
        rejectIncompleteCoverage(suggestions, candidateRules, envelope);
        return suggestions;
    }

    private Envelope parseEnvelope(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            throw new ComplianceAiException(
                    "The compliance AI service returned an empty response body, so no compliances "
                            + "were generated.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception e) {
            throw new ComplianceAiException(
                    "The compliance AI service returned a response that is not JSON, so no "
                            + "compliances were generated. (" + e.getMessage() + ")", e);
        }

        JsonNode choice = root.path("choices").path(0);
        JsonNode content = choice.path("message").path("content");

        // Providers are not consistent about the field name: OpenAI and the DigitalOcean
        // Gradient endpoint report finish_reason, some gateways relay stop_reason instead.
        JsonNode finishReason = choice.path("finish_reason");
        if (!finishReason.isTextual()) {
            finishReason = choice.path("stop_reason");
        }

        JsonNode completionTokens = root.path("usage").path("completion_tokens");

        return new Envelope(
                content.isTextual() ? content.asText() : "",
                finishReason.isTextual() ? finishReason.asText() : null,
                completionTokens.isInt() ? completionTokens.asInt() : null);
    }

    /**
     * Refuses a response the provider itself says it truncated. This is the signal that
     * used to be dropped: the old reader took {@code choices[0].message.content} and never
     * looked at why generation ended, so a cap-truncated answer was indistinguishable from
     * a complete one until Jackson choked on it and the throw was swallowed.
     */
    private void rejectTruncatedStopReason(Envelope envelope, int ruleCount) {
        String reason = envelope.finishReason();
        if (reason == null || !TRUNCATED_STOP_REASONS.contains(reason.toLowerCase(Locale.ROOT))) {
            return;
        }
        throw new ComplianceAiException(
                "The compliance AI response was cut short by its token limit (stop reason '" + reason
                        + "'), so the assessment of " + ruleCount + " rule(s) is incomplete and no "
                        + "compliances were generated. " + tokenBudgetHint(envelope));
    }

    /**
     * Parses the assistant text into suggestions, still tolerating the model wrapping its
     * array in prose or a code fence. The tolerance now stops at the array's own closing
     * bracket instead of the last bracket anywhere in the text, and a parse failure is
     * reported rather than absorbed.
     */
    private List<ComplianceSuggestion> parseSuggestions(Envelope envelope) {
        String responseText = envelope.text();
        if (responseText == null || responseText.isBlank()) {
            throw new ComplianceAiException(
                    "The compliance AI returned no text at all, so no compliances were generated. "
                            + tokenBudgetHint(envelope));
        }

        int start = responseText.indexOf('[');
        if (start < 0) {
            throw new ComplianceAiException(
                    "The compliance AI response contained no JSON array of assessments, so no "
                            + "compliances were generated. The model answered: " + excerpt(responseText));
        }

        int end = findMatchingArrayEnd(responseText, start);
        if (end < 0) {
            throw new ComplianceAiException(
                    "The compliance AI response opened a JSON array that never closes, so it was cut "
                            + "off before it finished and no compliances were generated. "
                            + tokenBudgetHint(envelope));
        }

        String json = responseText.substring(start, end + 1);
        try {
            ComplianceSuggestion[] parsed = objectMapper.readValue(json, ComplianceSuggestion[].class);
            return List.of(parsed);
        } catch (Exception e) {
            throw new ComplianceAiException(
                    "The compliance AI response was not a readable array of assessments, so no "
                            + "compliances were generated. " + tokenBudgetHint(envelope)
                            + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Refuses a response that left some candidate rule unassessed. This catches the
     * truncation the other two checks cannot see: an answer that happens to stop on a
     * valid closing bracket, from a provider that reports no stop reason. Codes the model
     * invented are ignored (the generation service already drops them); only unassessed
     * candidates are a failure.
     */
    private void rejectIncompleteCoverage(List<ComplianceSuggestion> suggestions,
                                          List<ComplianceRule> candidateRules,
                                          Envelope envelope) {
        Set<String> assessed = new LinkedHashSet<>();
        for (ComplianceSuggestion suggestion : suggestions) {
            if (suggestion.ruleCode() != null) {
                assessed.add(suggestion.ruleCode());
            }
        }

        List<String> missing = candidateRules.stream()
                .map(ComplianceRule::getCode)
                .filter(code -> !assessed.contains(code))
                .toList();
        if (missing.isEmpty()) {
            return;
        }

        int covered = candidateRules.size() - missing.size();
        throw new ComplianceAiException(
                "The compliance AI assessed only " + covered + " of " + candidateRules.size()
                        + " rules, leaving " + missing.size() + " unassessed (" + nameMissing(missing)
                        + "), so the result is incomplete and no compliances were generated. "
                        + tokenBudgetHint(envelope));
    }

    private static String nameMissing(List<String> missing) {
        String named = String.join(", ", missing.subList(0, Math.min(missing.size(), MAX_MISSING_CODES_REPORTED)));
        if (missing.size() > MAX_MISSING_CODES_REPORTED) {
            named = named + ", and " + (missing.size() - MAX_MISSING_CODES_REPORTED) + " more";
        }
        return named;
    }

    /**
     * Index of the {@code ]} that closes the array opened at {@code start}, or -1 when the
     * text ends before it. Brackets inside string literals are skipped, so a rationale
     * mentioning "section 3[b]" cannot close the array early, and a {@code \"} escape does
     * not end the string it sits in.
     */
    private static int findMatchingArrayEnd(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Names the budget and what was spent against it. This is what turns a failure into
     * the per-rule token figure, so the ceiling can be sized from a run that actually hit
     * it rather than from a separate measurement.
     */
    private String tokenBudgetHint(Envelope envelope) {
        StringBuilder sb = new StringBuilder("The limit (compliance.ai.max-tokens) is ")
                .append(maxTokens)
                .append(" tokens");
        if (envelope.completionTokens() != null) {
            sb.append(" and the model reported using ").append(envelope.completionTokens());
        }
        sb.append('.');
        return sb.toString();
    }

    /** Keeps the model's own prose out of the log and the error body at full length. */
    private static String excerpt(String text) {
        String collapsed = text.strip().replace('\n', ' ');
        return collapsed.length() <= MAX_EXCERPT_LENGTH
                ? collapsed
                : collapsed.substring(0, MAX_EXCERPT_LENGTH) + "...";
    }
}
