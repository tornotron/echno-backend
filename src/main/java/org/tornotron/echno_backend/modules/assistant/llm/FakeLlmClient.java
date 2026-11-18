package org.tornotron.echno_backend.modules.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.time.AssistantClockConfiguration;

/**
 * A model that is not one: it answers from the question and the evidence it is handed, with no
 * network and no randomness, so everything the pipeline does with a model's output can be proven
 * deterministically. It is on only where {@code echno.assistant.llm.client=fake}: the tests, and
 * the local stack for the scripted walk.
 *
 * <p>Two ways to steer it. A test registers a reply for an exact question with
 * {@link #whenInterpreting} or {@link #whenAnswering}, which is how a bad answer (an uncited fact,
 * a foreign evidence id, a number the evidence does not hold) is put in front of the validator.
 * With nothing registered it falls back to a plain reading of the question: a few keywords pick
 * the subjects, a capitalized name after "at", "for", "on" or "in" is the project, and an answer
 * is one fact per evidence line, each restating that line's own values.
 */
@Component
@AssistantModuleEnabled
@ConditionalOnProperty(prefix = "echno.assistant.llm", name = "client", havingValue = "fake")
public class FakeLlmClient implements AssistantLlmClient {

    private static final Pattern ATTENDANCE_WORDS =
            Pattern.compile("(?i)attendance|on site|present|headcount|workers|turned up|absent");
    private static final Pattern TASK_WORDS = Pattern.compile("(?i)\\btasks?\\b|overdue|progress|behind");
    private static final Pattern MULTI_STEP = Pattern.compile("(?i)\\b(and then|compare|after that)\\b");
    private static final Pattern PROJECT =
            Pattern.compile("(?i:\\b(?:at|for|on|in)\\s+(?:project\\s+)?)([A-Z0-9][\\w\\-]*(?:\\s+[A-Z0-9][\\w\\-]*)*)");
    private static final Pattern RANGE =
            Pattern.compile("(?i)from\\s+(\\d{4}-\\d{2}-\\d{2})\\s+to\\s+(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern EVIDENCE_LINE = Pattern.compile("(?m)^\\[\\d+]\\s+id=(\\S+)\\s+kind=(\\S+)\\s+values=(.*)$");

    private final Map<String, String> interpretations = new ConcurrentHashMap<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private volatile LlmUnavailableException failure;

    /** Replies to the interpreting call for exactly this question with this JSON. */
    public void whenInterpreting(String question, String json) {
        interpretations.put(question, json);
    }

    /** Replies to the answering call for exactly this question with this JSON. */
    public void whenAnswering(String question, String json) {
        answers.put(question, json);
    }

    /** Makes every call fail as an unreachable model would. */
    public void failWith(LlmUnavailableException e) {
        this.failure = e;
    }

    /** Forgets every registered reply and failure. */
    public void reset() {
        interpretations.clear();
        answers.clear();
        failure = null;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public LlmResponse chat(String system, String user) {
        LlmUnavailableException failing = failure;
        if (failing != null) {
            throw failing;
        }
        String question = lineAfter(user, PromptMarkers.QUESTION);
        String content;
        if (system.startsWith(PromptMarkers.INTERPRET)) {
            content = interpretations.getOrDefault(question, interpret(question));
        } else {
            content = answers.getOrDefault(question, answer(user));
        }
        return new LlmResponse(content, (system.length() + user.length()) / 4, content.length() / 4, "fake", "1");
    }

    private static String interpret(String question) {
        List<String> subjects = new ArrayList<>();
        if (ATTENDANCE_WORDS.matcher(question).find()) {
            subjects.add("ATTENDANCE");
        }
        if (TASK_WORDS.matcher(question).find()) {
            subjects.add("TASK");
        }
        Matcher project = PROJECT.matcher(question);
        String projectRef = project.find() ? project.group(1) : null;

        String period = "null";
        LocalDate today = LocalDate.now(ZoneId.of(AssistantClockConfiguration.DEFAULT_ZONE));
        Matcher range = RANGE.matcher(question);
        if (range.find()) {
            period = "{\"from\":\"" + range.group(1) + "\",\"to\":\"" + range.group(2) + "\"}";
        } else if (question.toLowerCase().contains("yesterday")) {
            period = "{\"from\":\"" + today.minusDays(1) + "\",\"to\":\"" + today.minusDays(1) + "\"}";
        } else if (question.toLowerCase().contains("today")) {
            period = "{\"from\":\"" + today + "\",\"to\":\"" + today + "\"}";
        }

        String ambiguous = subjects.isEmpty()
                ? "[\"I could not tell what you want to know. Ask about attendance or tasks.\"]" : "[]";
        return "{\"intent\":\"" + String.join("+", subjects).toLowerCase() + "\","
                + "\"subjects\":" + quoted(subjects) + ","
                + "\"projectRef\":" + (projectRef == null ? "null" : "\"" + projectRef + "\"") + ","
                + "\"period\":" + period + ","
                + "\"personRef\":null,"
                + "\"multiStep\":" + MULTI_STEP.matcher(question).find() + ","
                + "\"ambiguous\":" + ambiguous + "}";
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String answer(String user) {
        Matcher lines = EVIDENCE_LINE.matcher(user);
        List<String> statements = new ArrayList<>();
        while (lines.find()) {
            String id = lines.group(1);
            String values = withoutIdFields(lines.group(3));
            String text = lines.group(2) + " " + id + " reports " + values;
            statements.add("{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"kind\":\"fact\",\"evidenceIds\":[\"" + id + "\"]}");
        }
        return "{\"statements\":[" + String.join(",", statements) + "],\"insufficient\":" + statements.isEmpty() + "}";
    }

    private static String withoutIdFields(String rawValues) {
        try {
            JsonNode tree = JSON.readTree(rawValues);
            if (tree instanceof ObjectNode obj) {
                ObjectNode copy = obj.deepCopy();
                Iterator<String> names = copy.fieldNames();
                List<String> toRemove = new ArrayList<>();
                while (names.hasNext()) {
                    String name = names.next();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("id") || lower.endsWith("id") || lower.endsWith("_id")) {
                        toRemove.add(name);
                    }
                }
                toRemove.forEach(copy::remove);
                return JSON.writeValueAsString(copy);
            }
        } catch (Exception ignored) {
            // Fall back to raw text if values are not a JSON object.
        }
        return rawValues;
    }

    private static String quoted(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            out.add("\"" + v + "\"");
        }
        return "[" + String.join(",", out) + "]";
    }

    private static String lineAfter(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }
}
