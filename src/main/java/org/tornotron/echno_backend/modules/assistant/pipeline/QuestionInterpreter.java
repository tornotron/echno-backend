package org.tornotron.echno_backend.modules.assistant.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient;
import org.tornotron.echno_backend.modules.assistant.llm.LlmResponse;
import org.tornotron.echno_backend.modules.assistant.llm.PromptMarkers;
import org.tornotron.echno_backend.modules.assistant.time.AssistantClock;

/**
 * Step one: one model call that turns a question into an {@link Interpretation}. It decides
 * nothing about data. Which project is meant is resolved afterward in Java against the projects
 * the caller can list, so a model that names a project the caller cannot see gets no further.
 *
 * <p>A reply that is not the expected JSON is not a failure of the model's availability; it is a
 * question that was not understood, and the person is asked to put it another way.
 */
@Component
@AssistantModuleEnabled
public class QuestionInterpreter {

    static final String NOT_UNDERSTOOD = "I could not understand that question. Try asking it another way.";

    private static final String SYSTEM = PromptMarkers.INTERPRET + " You read a question from a construction "
            + "site manager and say what it asks. You never answer it. Reply with JSON only, no prose.";

    private final AssistantLlmClient llm;
    private final Clock clock;

    public QuestionInterpreter(AssistantLlmClient llm, @AssistantClock Clock clock) {
        this.llm = llm;
        this.clock = clock;
    }

    /** An interpretation and what the call that produced it cost. */
    public record Interpreted(Interpretation interpretation, LlmResponse usage) {
    }

    /**
     * @param previousScope a sentence describing the scope of the caller's previous question, when
     *                      it is still inside the follow-up window; offered as the default and
     *                      otherwise nothing is remembered
     */
    public Interpreted interpret(String question, String previousScope) {
        LlmResponse response = llm.chat(SYSTEM, userPrompt(question, previousScope));
        return new Interpreted(read(response.content()), response);
    }

    private String userPrompt(String question, String previousScope) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptMarkers.QUESTION).append(question.replace('\n', ' ')).append('\n');
        sb.append("TODAY: ").append(LocalDate.now(clock)).append('\n');
        if (previousScope != null) {
            sb.append("PREVIOUS SCOPE (use it only if the question does not name its own): ")
                    .append(previousScope).append('\n');
        }
        sb.append("""
                Reply with one JSON object:
                {"intent": short phrase,
                 "subjects": any of ["ATTENDANCE","TASK","MATERIAL","DOCUMENT","PROJECT"],
                 "projectRef": the project exactly as typed, or null,
                 "period": {"from":"YYYY-MM-DD","to":"YYYY-MM-DD"} or null,
                 "personRef": a person named in the question, or null,
                 "multiStep": true if answering needs several dependent steps,
                 "ambiguous": list of what is unclear, empty if nothing is}
                """);
        return sb.toString();
    }

    static Interpretation read(String reply) {
        Optional<JsonNode> parsed = ModelJson.object(reply);
        if (parsed.isEmpty()) {
            return unclear();
        }
        JsonNode node = parsed.get();
        LocalDate from = null;
        LocalDate to = null;
        JsonNode period = node.path("period");
        if (period.isObject()) {
            try {
                from = date(period, "from");
                to = date(period, "to");
            } catch (DateTimeParseException e) {
                return unclear();
            }
            if ((from == null) != (to == null) || (from != null && from.isAfter(to))) {
                return new Interpretation(ModelJson.text(node, "intent"), subjects(node), ModelJson.text(node, "projectRef"),
                        null, null, ModelJson.text(node, "personRef"), node.path("multiStep").asBoolean(false),
                        List.of("The period you gave is not a valid range. Give a start and an end date."));
            }
        }
        List<String> ambiguous = new ArrayList<>();
        node.path("ambiguous").forEach(item -> {
            if (!item.asText().isBlank()) {
                ambiguous.add(item.asText().trim());
            }
        });
        return new Interpretation(ModelJson.text(node, "intent"), subjects(node), ModelJson.text(node, "projectRef"),
                from, to, ModelJson.text(node, "personRef"), node.path("multiStep").asBoolean(false), ambiguous);
    }

    private static Interpretation unclear() {
        return new Interpretation(null, Set.of(), null, null, null, null, false, List.of(NOT_UNDERSTOOD));
    }

    private static LocalDate date(JsonNode period, String field) {
        String text = ModelJson.text(period, field);
        return text == null ? null : LocalDate.parse(text);
    }

    private static Set<Subject> subjects(JsonNode node) {
        Set<Subject> subjects = EnumSet.noneOf(Subject.class);
        node.path("subjects").forEach(item -> {
            try {
                subjects.add(Subject.valueOf(item.asText().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // A subject this release does not know is left out; the planner then finds nothing for it.
            }
        });
        return subjects;
    }
}
