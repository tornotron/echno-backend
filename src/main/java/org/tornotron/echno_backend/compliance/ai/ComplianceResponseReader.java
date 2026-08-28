package org.tornotron.echno_backend.compliance.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
@Slf4j
final class ComplianceResponseReader {

    /** Stop reasons that mean the model was cut off at the token cap, lower-cased. */
    private static final Set<String> TRUNCATED_STOP_REASONS = Set.of("length", "max_tokens");

    /** How many rule codes to name in a coverage failure before trailing off. */
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
        List<ComplianceSuggestion> suggestions =
                canonicaliseRuleCodes(parseSuggestions(envelope), candidateRules);
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
            log.warn("Compliance AI response body was not JSON: {}", e.getMessage());
            throw new ComplianceAiException(
                    "The compliance AI service returned a response that is not JSON, so no "
                            + "compliances were generated.", e);
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
     *
     * <p>Every {@code [} in the text is tried in turn, not just the first, because the prose
     * a model wraps its answer in can contain one of its own ("based on the rules [1] above")
     * and anchoring on that would slice out the citation and refuse a perfectly complete
     * answer. The first candidate whose matched slice actually parses is the answer.
     *
     * <p>A candidate with no matching bracket ends the search rather than being skipped: the
     * text ran out inside that array, so anything after it is nested within it and cannot be
     * the answer either. That is the truncation, and it is reported as such.
     */
    private List<ComplianceSuggestion> parseSuggestions(Envelope envelope) {
        String responseText = envelope.text();
        if (responseText == null || responseText.isBlank()) {
            throw new ComplianceAiException(
                    "The compliance AI returned no text at all, so no compliances were generated. "
                            + tokenBudgetHint(envelope));
        }

        Exception lastParseFailure = null;
        for (int start = responseText.indexOf('['); start >= 0; start = responseText.indexOf('[', start + 1)) {
            int end = findMatchingArrayEnd(responseText, start);
            if (end < 0) {
                throw new ComplianceAiException(
                        "The compliance AI response opened a JSON array that never closes, so it was "
                                + "cut off before it finished and no compliances were generated. "
                                + tokenBudgetHint(envelope));
            }
            try {
                ComplianceSuggestion[] parsed = objectMapper.readValue(
                        responseText.substring(start, end + 1), ComplianceSuggestion[].class);
                return List.of(parsed);
            } catch (Exception e) {
                lastParseFailure = e;
            }
        }

        if (lastParseFailure == null) {
            throw new ComplianceAiException(
                    "The compliance AI response contained no JSON array of assessments, so no "
                            + "compliances were generated. The model answered: " + excerpt(responseText));
        }
        // The parser's own message quotes the fragment it choked on, which belongs in the log
        // rather than in an API response body.
        log.warn("Compliance AI response held no readable array of assessments: {}",
                lastParseFailure.getMessage());
        throw new ComplianceAiException(
                "The compliance AI response was not a readable array of assessments, so no "
                        + "compliances were generated. " + tokenBudgetHint(envelope), lastParseFailure);
    }

    /**
     * Puts a rule code the model echoed back in a different case, or with whitespace around
     * it, onto the candidate's exact spelling.
     *
     * <p>Both checks downstream match codes by exact string equality: coverage here, and the
     * generation service's own {@code rulesByCode} lookup when it decides what to create. A
     * code of " tn-bpa " would therefore fail coverage as an unassessed rule, and if coverage
     * were relaxed instead it would sail through and then be dropped without a word when the
     * inspection was built. Normalising once, at the edge, keeps both from happening.
     *
     * <p>Only spelling is normalised. A code that matches no candidate at all is left as it
     * is, so an invented compliance stays visibly invented.
     */
    private List<ComplianceSuggestion> canonicaliseRuleCodes(List<ComplianceSuggestion> suggestions,
                                                             List<ComplianceRule> candidateRules) {
        Map<String, String> canonicalByNormalised = new LinkedHashMap<>();
        for (ComplianceRule rule : candidateRules) {
            String code = rule.getCode();
            if (code != null) {
                canonicalByNormalised.putIfAbsent(normalise(code), code);
            }
        }

        List<ComplianceSuggestion> canonicalised = new ArrayList<>(suggestions.size());
        for (ComplianceSuggestion suggestion : suggestions) {
            String canonical = suggestion.ruleCode() == null
                    ? null
                    : canonicalByNormalised.get(normalise(suggestion.ruleCode()));
            if (canonical == null || canonical.equals(suggestion.ruleCode())) {
                canonicalised.add(suggestion);
                continue;
            }
            log.debug("Compliance AI returned rule code '{}' for candidate '{}'; using the candidate's",
                    suggestion.ruleCode(), canonical);
            canonicalised.add(new ComplianceSuggestion(canonical, suggestion.applies(),
                    suggestion.riskLevel(), suggestion.resolutionOptions(), suggestion.rationale(),
                    suggestion.phase()));
        }
        return List.copyOf(canonicalised);
    }

    private static String normalise(String code) {
        return code.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a response that did not assess each candidate rule exactly once.
     *
     * <p>Missing candidates catch the truncation the other two checks cannot see: an answer
     * that happens to stop on a valid closing bracket, from a provider that reports no stop
     * reason. Codes the model invented are ignored, since the generation service already
     * drops them; only unassessed candidates are a failure.
     *
     * <p>Repeated candidates are refused too, and for a different reason. Counting distinct
     * codes would let a response that says {@code TN-1} does not apply and then that
     * {@code TN-1} does apply satisfy coverage, and the generation service, which stops at
     * the first element it can use, would create an inspection off one half of a
     * contradiction without anyone seeing the other half. An answer that cannot make up its
     * mind is not a result to act on.
     */
    private void rejectIncompleteCoverage(List<ComplianceSuggestion> suggestions,
                                          List<ComplianceRule> candidateRules,
                                          Envelope envelope) {
        Map<String, Integer> assessmentsByCode = new LinkedHashMap<>();
        for (ComplianceSuggestion suggestion : suggestions) {
            if (suggestion.ruleCode() != null) {
                assessmentsByCode.merge(suggestion.ruleCode(), 1, Integer::sum);
            }
        }

        List<String> missing = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        for (ComplianceRule rule : candidateRules) {
            int count = assessmentsByCode.getOrDefault(rule.getCode(), 0);
            if (count == 0) {
                missing.add(rule.getCode());
            } else if (count > 1) {
                repeated.add(rule.getCode());
            }
        }

        if (!repeated.isEmpty()) {
            throw new ComplianceAiException(
                    "The compliance AI returned more than one assessment for " + repeated.size()
                            + " rule(s) (" + nameSome(repeated) + "), so its answer contradicts itself "
                            + "and no compliances were generated.");
        }

        if (missing.isEmpty()) {
            return;
        }

        int covered = candidateRules.size() - missing.size();
        throw new ComplianceAiException(
                "The compliance AI assessed only " + covered + " of " + candidateRules.size()
                        + " rules, leaving " + missing.size() + " unassessed (" + nameSome(missing)
                        + "), so the result is incomplete and no compliances were generated. "
                        + tokenBudgetHint(envelope));
    }

    /** Names the first few codes and counts the rest, so a big catalogue does not fill the message. */
    private static String nameSome(List<String> codes) {
        String named = String.join(", ", codes.subList(0, Math.min(codes.size(), MAX_MISSING_CODES_REPORTED)));
        if (codes.size() > MAX_MISSING_CODES_REPORTED) {
            named = named + ", and " + (codes.size() - MAX_MISSING_CODES_REPORTED) + " more";
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
