package org.tornotron.echno_backend.modules.assistant.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient;
import org.tornotron.echno_backend.modules.assistant.llm.LlmResponse;
import org.tornotron.echno_backend.modules.assistant.llm.PromptMarkers;

/**
 * Step five: one model call that writes the answer from the evidence and nothing else. The
 * model is shown the question, the scope in words, and the evidence as a numbered list with the
 * field names and units the sources use, and is asked for statements each pointing at the evidence
 * ids it rests on. Whether it kept to that is not trusted here; {@link AnswerValidator} checks.
 *
 * <p>A reply that is not the expected JSON becomes an answer with no statements and
 * {@code insufficient} set, which the validator turns into a decline.
 */
@Component
@AssistantModuleEnabled
public class GroundedAnswerer {

    private static final String SYSTEM = PromptMarkers.ANSWER + " You answer a construction site manager's "
            + "question using ONLY the numbered evidence you are given. Every statement of fact must cite the "
            + "ids of the evidence it comes from, and must use numbers exactly as they appear there. Do not "
            + "calculate, estimate or add anything the evidence does not say. If the evidence does not answer "
            + "the question, say insufficient. Reply with JSON only, no prose.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistantLlmClient llm;

    public GroundedAnswerer(AssistantLlmClient llm) {
        this.llm = llm;
    }

    /** The model's statements, as given, and what the call cost. */
    public record Generated(List<Statement> statements, boolean insufficient, LlmResponse usage) {
    }

    public Generated answer(String question, String scope, List<String> assumptions,
                            List<EvidenceUnit> evidence, List<ProviderDescriptor> descriptors) {
        LlmResponse response = llm.chat(SYSTEM, userPrompt(question, scope, assumptions, evidence, descriptors));
        Optional<JsonNode> parsed = ModelJson.object(response.content());
        if (parsed.isEmpty()) {
            return new Generated(List.of(), true, response);
        }
        List<Statement> statements = new ArrayList<>();
        parsed.get().path("statements").forEach(node -> {
            String text = ModelJson.text(node, "text");
            if (text == null) {
                return;
            }
            List<String> ids = new ArrayList<>();
            node.path("evidenceIds").forEach(id -> ids.add(id.asText()));
            statements.add(new Statement(text, kind(node.path("kind").asText("")), ids));
        });
        return new Generated(statements, parsed.get().path("insufficient").asBoolean(false), response);
    }

    private static Statement.Kind kind(String name) {
        try {
            return Statement.Kind.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A kind the model invented is treated as a fact, the strictest reading: it must cite.
            return Statement.Kind.FACT;
        }
    }

    private static String userPrompt(String question, String scope, List<String> assumptions,
                                     List<EvidenceUnit> evidence, List<ProviderDescriptor> descriptors) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptMarkers.QUESTION).append(question.replace('\n', ' ')).append('\n');
        sb.append("SCOPE: ").append(scope).append('\n');
        assumptions.forEach(a -> sb.append("ASSUMPTION: ").append(a).append('\n'));
        sb.append("FIELDS (name, unit, meaning):\n");
        for (ProviderDescriptor descriptor : descriptors) {
            for (FieldSpec field : descriptor.fields()) {
                sb.append("- ").append(descriptor.id()).append('.').append(field.name()).append(": ")
                        .append(field.unit()).append(", ").append(field.meaning()).append('\n');
            }
        }
        sb.append("EVIDENCE:\n");
        int number = 1;
        for (EvidenceUnit unit : evidence) {
            sb.append('[').append(number++).append(']').append(PromptMarkers.EVIDENCE_ID).append(unit.id())
                    .append(" kind=").append(unit.kind())
                    .append(PromptMarkers.EVIDENCE_VALUES).append(json(unit.values())).append('\n');
        }
        sb.append("""
                Reply with one JSON object:
                {"statements": [{"text": one sentence, "kind": "fact" | "inference" | "recommendation",
                                 "evidenceIds": ids from the list above}],
                 "insufficient": true if the evidence does not answer the question}
                """);
        return sb.toString();
    }

    private static String json(Map<String, Object> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            return values.toString();
        }
    }
}
