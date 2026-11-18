package org.tornotron.echno_backend.modules.assistant.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient;
import org.tornotron.echno_backend.modules.assistant.llm.FakeLlmClient;
import org.tornotron.echno_backend.modules.assistant.llm.LlmResponse;
import org.tornotron.echno_backend.modules.assistant.llm.LlmUnavailableException;

/** Reading a question, against the fake model and against replies a real one could send. */
class QuestionInterpreterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final FakeLlmClient fake = new FakeLlmClient();
    private final QuestionInterpreter interpreter = new QuestionInterpreter(fake, CLOCK);

    @BeforeEach
    void reset() {
        fake.reset();
    }

    @Test
    void theFakeReadsAnAttendanceQuestionWithAProjectAndNoPeriod() {
        var reading = interpreter.interpret("How many workers were on site at Tower B this week?", null).interpretation();

        assertThat(reading.subjects()).containsExactly(Subject.ATTENDANCE);
        assertThat(reading.projectRef()).isEqualTo("Tower B");
        assertThat(reading.hasPeriod()).isFalse();
        assertThat(reading.ambiguous()).isEmpty();
        assertThat(reading.multiStep()).isFalse();
    }

    @Test
    void theFakeReadsTodayAsAOneDayPeriod() {
        var reading = interpreter.interpret("Who was on site at Tower B today?", null).interpretation();

        assertThat(reading.from()).isEqualTo(reading.to()).isNotNull();
    }

    @Test
    void theFakeReadsAnExplicitRange() {
        var reading = interpreter.interpret("Attendance at Tower B from 2026-10-01 to 2026-10-03", null).interpretation();

        assertThat(reading.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(reading.to()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void aQuestionAboutNothingItKnowsIsAskedBackNotGuessed() {
        var reading = interpreter.interpret("What is the meaning of life?", null).interpretation();

        assertThat(reading.subjects()).isEmpty();
        assertThat(reading.ambiguous()).isNotEmpty();
    }

    @Test
    void aTwoPartQuestionIsMarkedMultiStep() {
        var reading = interpreter.interpret("Compare attendance at Tower B and then list its tasks", null).interpretation();

        assertThat(reading.multiStep()).isTrue();
    }

    @Test
    void jsonWrappedInProseOrAFenceIsStillRead() {
        fake.whenInterpreting("q", "Sure! ```json\n{\"intent\":\"x\",\"subjects\":[\"task\"],\"projectRef\":\"Tower B\","
                + "\"period\":null,\"personRef\":null,\"multiStep\":false,\"ambiguous\":[]}\n```");

        var reading = interpreter.interpret("q", null).interpretation();

        assertThat(reading.subjects()).containsExactly(Subject.TASK);
        assertThat(reading.projectRef()).isEqualTo("Tower B");
    }

    @Test
    void aSubjectThisReleaseDoesNotKnowIsLeftOut() {
        fake.whenInterpreting("q", "{\"subjects\":[\"WEATHER\",\"ATTENDANCE\"],\"ambiguous\":[]}");

        assertThat(interpreter.interpret("q", null).interpretation().subjects()).containsExactly(Subject.ATTENDANCE);
    }

    @Test
    void aReplyThatIsNotJsonIsAQuestionNotUnderstoodNotAFailure() {
        fake.whenInterpreting("q", "I'm sorry, I can't help with that.");

        var reading = interpreter.interpret("q", null).interpretation();

        assertThat(reading.ambiguous()).containsExactly(QuestionInterpreter.NOT_UNDERSTOOD);
        assertThat(reading.subjects()).isEmpty();
    }

    @Test
    void aPeriodThatEndsBeforeItStartsIsAskedBack() {
        fake.whenInterpreting("q", "{\"subjects\":[\"ATTENDANCE\"],\"projectRef\":\"Tower B\","
                + "\"period\":{\"from\":\"2026-10-05\",\"to\":\"2026-10-01\"},\"ambiguous\":[]}");

        var reading = interpreter.interpret("q", null).interpretation();

        assertThat(reading.hasPeriod()).isFalse();
        assertThat(reading.ambiguous()).isNotEmpty();
    }

    @Test
    void aPeriodWithAnUnreadableDateIsNotUnderstood() {
        fake.whenInterpreting("q", "{\"subjects\":[\"ATTENDANCE\"],\"period\":{\"from\":\"last Tuesday\",\"to\":\"2026-10-01\"}}");

        assertThat(interpreter.interpret("q", null).interpretation().ambiguous())
                .containsExactly(QuestionInterpreter.NOT_UNDERSTOOD);
    }

    @Test
    void thePreviousScopeIsOfferedToTheModelOnlyWhenThereIsOne() {
        List<String> prompts = new ArrayList<>();
        AssistantLlmClient recording = new AssistantLlmClient() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse chat(String system, String user) {
                prompts.add(user);
                return new LlmResponse("{\"subjects\":[\"TASK\"]}", 1, 1, "m", "1");
            }
        };
        QuestionInterpreter withRecording = new QuestionInterpreter(recording, CLOCK);

        withRecording.interpret("and the tasks?", "project Tower B");
        withRecording.interpret("and the tasks?", null);

        assertThat(prompts.get(0)).contains("PREVIOUS SCOPE").contains("project Tower B");
        assertThat(prompts.get(1)).doesNotContain("PREVIOUS SCOPE");
    }

    @Test
    void todayIsTheSitesDayNotTheServersDay() {
        // 04:00 UTC is 09:30 on the 7th in Kolkata; a clock in the wrong zone could say the 6th.
        List<String> prompts = new ArrayList<>();
        AssistantLlmClient recording = new AssistantLlmClient() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse chat(String system, String user) {
                prompts.add(user);
                return new LlmResponse("{}", 1, 1, "m", "1");
            }
        };

        new QuestionInterpreter(recording, CLOCK).interpret("q", null);

        assertThat(prompts.get(0)).contains("TODAY: 2026-10-07");
    }

    @Test
    void anUnreachableModelIsNotSwallowed() {
        fake.failWith(new LlmUnavailableException("down"));

        assertThatThrownBy(() -> interpreter.interpret("q", null)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void theCallReportsWhatItCost() {
        var interpreted = interpreter.interpret("Attendance at Tower B", null);

        assertThat(interpreted.usage().promptTokens()).isPositive();
    }
}
