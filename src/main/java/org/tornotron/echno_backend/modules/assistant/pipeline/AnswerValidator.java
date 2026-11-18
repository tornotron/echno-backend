package org.tornotron.echno_backend.modules.assistant.pipeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;

/**
 * Step six, and the one that matters most: the grounding rules, enforced in Java. The model is
 * asked to cite and to copy numbers faithfully; this is where that stops being a request.
 *
 * <p>Every statement — fact, inference, or recommendation — is dropped when it cites nothing, when
 * it cites an id that is not in the evidence assembled for this question, when it spells out a
 * number in words, or when it states a digit number that is not in the quantity fields of the units
 * it cites. Held numbers come only from quantity fields (ids, dates, and free text are excluded),
 * and cited evidence ids and ISO-8601 dates/date-times are stripped from the statement text before
 * scanning so neither can masquerade as a figure.
 *
 * <p>Facts are kept apart from inferences and recommendations. If no fact survives, the answer is a
 * decline, however fluent the rest of it was. Two providers that disagree are two cited facts and
 * both pass: nothing here picks a winner.
 */
@Component
@AssistantModuleEnabled
public class AnswerValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    // ISO-8601 date-times (with optional fractional seconds and timezone) and plain ISO-8601 dates.
    private static final Pattern ISO_DATE_TIME = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b");

    private static final Pattern SPELLED_NUMBER = Pattern.compile(
            "(?i)\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|"
                    + "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|"
                    + "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|"
                    + "hundred|thousand|million|billion|dozen)\\b");

    private static final Set<String> NON_QUANTITY_UNITS = Set.of(
            "id", "text", "name", "code", "unit", "status name", "true or false", "boolean");

    /** What survived, and how many of each kind of claim did not. */
    public record Validated(List<Statement> facts, List<Statement> reasoning,
                            int droppedUncited, int droppedForeign, int droppedNumbers) {

        public Validated {
            facts = List.copyOf(facts);
            reasoning = List.copyOf(reasoning);
        }

        public boolean hasFacts() {
            return !facts.isEmpty();
        }

        public int dropped() {
            return droppedUncited + droppedForeign + droppedNumbers;
        }
    }

    /** Convenience overload when provider descriptors are not supplied; falls back to field-name rules. */
    public Validated validate(List<Statement> statements, List<EvidenceUnit> assembled) {
        return validate(statements, assembled, List.of());
    }

    public Validated validate(List<Statement> statements, List<EvidenceUnit> assembled,
                              List<ProviderDescriptor> descriptors) {
        Map<String, EvidenceUnit> byId = new HashMap<>();
        assembled.forEach(u -> byId.put(u.id(), u));

        Map<String, Map<String, FieldSpec>> fieldsByProvider = indexFields(descriptors);

        List<Statement> facts = new ArrayList<>();
        List<Statement> reasoning = new ArrayList<>();
        int uncited = 0;
        int foreign = 0;
        int numbers = 0;

        for (Statement statement : statements) {
            if (statement.evidenceIds().isEmpty()) {
                uncited++;
                continue;
            }
            if (!statement.evidenceIds().stream().allMatch(byId::containsKey)) {
                foreign++;
                continue;
            }

            String cleaned = stripAllowedTokens(statement.text(), statement.evidenceIds());
            if (SPELLED_NUMBER.matcher(cleaned).find()) {
                numbers++;
                continue;
            }

            Set<String> held = new HashSet<>();
            for (String id : statement.evidenceIds()) {
                EvidenceUnit unit = byId.get(id);
                Map<String, FieldSpec> providerFields = fieldsByProvider.getOrDefault(unit.providerId(), Map.of());
                collectQuantityNumbers(unit.values(), providerFields, held);
            }

            if (!numbersIn(cleaned).stream().allMatch(held::contains)) {
                numbers++;
                continue;
            }

            if (statement.kind() == Statement.Kind.FACT) {
                facts.add(statement);
            } else {
                reasoning.add(statement);
            }
        }
        return new Validated(facts, reasoning, uncited, foreign, numbers);
    }

    private static Map<String, Map<String, FieldSpec>> indexFields(List<ProviderDescriptor> descriptors) {
        Map<String, Map<String, FieldSpec>> index = new HashMap<>();
        if (descriptors == null) {
            return index;
        }
        for (ProviderDescriptor descriptor : descriptors) {
            Map<String, FieldSpec> fields = new HashMap<>();
            for (FieldSpec field : descriptor.fields()) {
                fields.put(field.name(), field);
            }
            index.put(descriptor.id(), fields);
        }
        return index;
    }

    private static String stripAllowedTokens(String text, List<String> citedIds) {
        String cleaned = text;
        // Strip cited ids longest-first so a shorter prefix id cannot break a longer id match.
        List<String> sortedIds = new ArrayList<>(citedIds);
        sortedIds.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String id : sortedIds) {
            if (id != null && !id.isEmpty()) {
                cleaned = cleaned.replace(id, " ");
            }
        }
        return ISO_DATE_TIME.matcher(cleaned).replaceAll(" ");
    }

    private static void collectQuantityNumbers(Map<String, Object> values,
                                               Map<String, FieldSpec> providerFields,
                                               Set<String> into) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            FieldSpec spec = providerFields.get(fieldName);
            if (!isQuantityField(fieldName, value, spec)) {
                continue;
            }
            collect(value, into);
        }
    }

    private static boolean isQuantityField(String fieldName, Object value, FieldSpec spec) {
        if (spec != null && spec.unit() != null) {
            String unit = spec.unit().trim().toLowerCase(Locale.ROOT);
            if (NON_QUANTITY_UNITS.contains(unit)
                    || unit.contains("iso-8601")
                    || unit.contains("date")) {
                return false;
            }
            return true;
        }
        // Fallback when no FieldSpec is supplied: exclude ids, dates, and text fields.
        if (fieldName != null) {
            String lower = fieldName.toLowerCase(Locale.ROOT);
            if (lower.equals("id") || lower.endsWith("id") || lower.endsWith("_id")
                    || lower.equals("date") || lower.endsWith("date")
                    || lower.equals("title") || lower.equals("content")) {
                return false;
            }
        }
        if (value instanceof String s && ISO_DATE_TIME.matcher(s.trim()).matches()) {
            return false;
        }
        return true;
    }

    private static void collect(Object value, Set<String> into) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, into));
        } else if (value instanceof Iterable<?> items) {
            items.forEach(v -> collect(v, into));
        } else if (value != null) {
            into.addAll(numbersIn(String.valueOf(value)));
        }
    }

    private static Set<String> numbersIn(String text) {
        Set<String> found = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            found.add(normalize(matcher.group()));
        }
        return found;
    }

    private static String normalize(String number) {
        String plain = number.replace(",", "");
        try {
            return new BigDecimal(plain).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return plain;
        }
    }
}
