package org.tornotron.echno_backend.modules.assistant.pipeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;

/**
 * Step six, and the one that matters most: the grounding rules, enforced in Java. The model is
 * asked to cite and to copy numbers faithfully; this is where that stops being a request.
 *
 * <p>A {@code FACT} is dropped when it cites nothing, when it cites an id that is not in the
 * evidence that was assembled for this question, or when it states a number that is not in the
 * values of the units it cites. A {@code FACT} that survives is therefore about evidence that was
 * fetched under the caller's scope, with figures the sources actually held. An inference or a
 * recommendation is kept, and shown apart from the facts, but may not cite an id that does not
 * exist. If no fact survives, the answer is a decline, however fluent the rest of it was.
 *
 * <p>Two providers that disagree are two cited facts and both pass: nothing here picks a winner.
 *
 * <p>The number check reads every number in the sentence and requires each to appear, after
 * dropping thousands separators and trailing zeros, among the numbers in the cited units' values.
 * It is deliberately blunt. A model that rounds, sums or converts has stated something the
 * evidence does not contain, and that is dropped and counted rather than argued with: computing
 * is the provider's job, in Java, before the evidence is written.
 */
@Component
@AssistantModuleEnabled
public class AnswerValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

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

    public Validated validate(List<Statement> statements, List<EvidenceUnit> assembled) {
        Map<String, EvidenceUnit> byId = new java.util.HashMap<>();
        assembled.forEach(u -> byId.put(u.id(), u));

        List<Statement> facts = new ArrayList<>();
        List<Statement> reasoning = new ArrayList<>();
        int uncited = 0;
        int foreign = 0;
        int numbers = 0;

        for (Statement statement : statements) {
            boolean isFact = statement.kind() == Statement.Kind.FACT;
            if (isFact && statement.evidenceIds().isEmpty()) {
                uncited++;
                continue;
            }
            if (!statement.evidenceIds().stream().allMatch(byId::containsKey)) {
                foreign++;
                continue;
            }
            if (!isFact) {
                reasoning.add(statement);
                continue;
            }
            Set<String> held = new HashSet<>();
            statement.evidenceIds().forEach(id -> {
                held.addAll(numbersIn(id)); // naming the id it cites is not stating a figure
                collect(byId.get(id).values(), held);
            });
            if (!numbersIn(statement.text()).stream().allMatch(held::contains)) {
                numbers++;
                continue;
            }
            facts.add(statement);
        }
        return new Validated(facts, reasoning, uncited, foreign, numbers);
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
