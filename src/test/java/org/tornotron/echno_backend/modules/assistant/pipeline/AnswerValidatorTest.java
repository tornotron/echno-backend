package org.tornotron.echno_backend.modules.assistant.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;
import org.tornotron.echno_backend.modules.assistant.pipeline.Statement.Kind;

/**
 * The grounding rules, one claim at a time. Each test hands the validator exactly one kind of bad
 * statement beside one good one, so a failure names the rule that stopped holding.
 */
class AnswerValidatorTest {

    private final AnswerValidator validator = new AnswerValidator();

    private static final EvidenceUnit DAY = unit("attendance:2026-10-05:project-42", Map.of(
            "date", "2026-10-05", "recordCount", 12, "totalWorkMinutes", 5760));

    private static final EvidenceUnit OTHER = unit("task:7", Map.of("taskId", 7, "progress", 40.0));

    @Test
    void aCitedFactWhoseNumbersAreInTheEvidenceIsKept() {
        var result = validator.validate(List.of(
                fact("12 employees have a record on 2026-10-05.", DAY.id())), List.of(DAY, OTHER));

        assertThat(result.facts()).hasSize(1);
        assertThat(result.dropped()).isZero();
    }

    @Test
    void namingTheCitedIdIsNotStatingAFigureButAnotherNumberStillIs() {
        EvidenceUnit summary = unit("task-summary:project-99", Map.of("taskCount", 3));

        var result = validator.validate(List.of(
                fact("task-summary:project-99 reports 3 tasks.", summary.id()),
                fact("task-summary:project-99 reports 4 tasks.", summary.id())), List.of(summary));

        assertThat(result.facts()).hasSize(1);
        assertThat(result.droppedNumbers()).isEqualTo(1);
    }

    @Test
    void aFactWithNoCitationIsDropped() {
        var result = validator.validate(List.of(
                fact("12 employees have a record.", /* none */ new String[0]),
                fact("12 employees have a record on 2026-10-05.", DAY.id())), List.of(DAY));

        assertThat(result.facts()).hasSize(1);
        assertThat(result.droppedUncited()).isEqualTo(1);
    }

    @Test
    void aFactCitingEvidenceThatWasNeverAssembledIsDropped() {
        // The id is well formed and plausible, and belongs to no unit fetched for this question.
        var result = validator.validate(List.of(
                fact("30 employees have a record.", "attendance:2026-10-05:project-99"),
                fact("12 employees have a record on 2026-10-05.", DAY.id())), List.of(DAY));

        assertThat(result.facts()).extracting(Statement::text).containsExactly("12 employees have a record on 2026-10-05.");
        assertThat(result.droppedForeign()).isEqualTo(1);
    }

    @Test
    void aFactCitingOneRealAndOneUnknownIdIsDroppedWholeNotTrimmed() {
        var result = validator.validate(List.of(
                fact("12 employees have a record.", DAY.id(), "attendance:other")), List.of(DAY));

        assertThat(result.facts()).isEmpty();
        assertThat(result.droppedForeign()).isEqualTo(1);
    }

    @Test
    void aNumberTheCitedEvidenceDoesNotHoldIsDroppedAndCounted() {
        var result = validator.validate(List.of(
                fact("15 employees have a record on 2026-10-05.", DAY.id()),
                fact("12 employees have a record on 2026-10-05.", DAY.id())), List.of(DAY));

        assertThat(result.facts()).hasSize(1);
        assertThat(result.droppedNumbers()).isEqualTo(1);
    }

    @Test
    void aNumberHeldOnlyByAnotherUnitDoesNotCountForTheOneCited() {
        // 40 is real, but it is task 7's progress, not the attendance day's.
        var result = validator.validate(List.of(
                fact("40 employees have a record on 2026-10-05.", DAY.id())), List.of(DAY, OTHER));

        assertThat(result.facts()).isEmpty();
        assertThat(result.droppedNumbers()).isEqualTo(1);
    }

    @Test
    void aSumTheEvidenceDoesNotContainIsDropped() {
        var result = validator.validate(List.of(
                fact("Together that is 17 records.", DAY.id())), List.of(DAY));

        assertThat(result.facts()).isEmpty();
        assertThat(result.droppedNumbers()).isEqualTo(1);
    }

    @Test
    void numbersAreComparedByValueNotByHowTheyAreWritten() {
        EvidenceUnit unit = unit("task-summary:project-42", Map.of("taskCount", 1200, "averageProgress", 40.5));

        var result = validator.validate(List.of(
                fact("There are 1,200 tasks at 40.50 percent on average.", unit.id())), List.of(unit));

        assertThat(result.facts()).hasSize(1);
    }

    @Test
    void numbersInsideNestedValuesAreHeld() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("PRESENT", 9);
        counts.put("ABSENT", 3);
        EvidenceUnit unit = unit("attendance:2026-10-06:project-42", Map.of("statusCounts", counts));

        var result = validator.validate(List.of(
                fact("9 were PRESENT and 3 were ABSENT.", unit.id())), List.of(unit));

        assertThat(result.facts()).hasSize(1);
    }

    @Test
    void aFactWithNoNumbersNeedsOnlyItsCitation() {
        var result = validator.validate(List.of(
                fact("Attendance was recorded for that day.", DAY.id())), List.of(DAY));

        assertThat(result.facts()).hasSize(1);
    }

    @Test
    void inferenceAndRecommendationAreKeptButNeverMixedWithTheFacts() {
        var result = validator.validate(List.of(
                new Statement("That looks like a normal day.", Kind.INFERENCE, List.of(DAY.id())),
                new Statement("Check the sign-in sheet.", Kind.RECOMMENDATION, List.of()),
                fact("12 employees have a record on 2026-10-05.", DAY.id())), List.of(DAY));

        assertThat(result.facts()).hasSize(1);
        assertThat(result.reasoning()).extracting(Statement::kind).containsExactly(Kind.INFERENCE, Kind.RECOMMENDATION);
    }

    @Test
    void anInferenceCitingEvidenceThatDoesNotExistIsDropped() {
        var result = validator.validate(List.of(
                new Statement("That looks unusual.", Kind.INFERENCE, List.of("attendance:nowhere")),
                fact("12 employees have a record on 2026-10-05.", DAY.id())), List.of(DAY));

        assertThat(result.reasoning()).isEmpty();
        assertThat(result.droppedForeign()).isEqualTo(1);
    }

    @Test
    void whenNoFactSurvivesTheAnswerHasNoFactsEvenIfReasoningRemains() {
        var result = validator.validate(List.of(
                fact("99 employees were here.", DAY.id()),
                new Statement("Probably a busy day.", Kind.INFERENCE, List.of(DAY.id()))), List.of(DAY));

        assertThat(result.hasFacts()).isFalse();
        assertThat(result.reasoning()).hasSize(1);
    }

    @Test
    void twoSourcesThatDisagreeAreBothReportedAndNeitherIsChosen() {
        EvidenceUnit first = unit("attendance:2026-10-05:project-42", Map.of("recordCount", 12));
        EvidenceUnit second = unit("task:9", Map.of("assigned", 14));

        var result = validator.validate(List.of(
                fact("Attendance shows 12 present.", first.id()),
                fact("The task list shows 14 assigned.", second.id())), List.of(first, second));

        assertThat(result.facts()).hasSize(2);
    }

    @Test
    void aModelThatInventsAKindIsHeldToTheStrictestRule() {
        // GroundedAnswerer reads an unknown kind as FACT; here the validator sees the result.
        var result = validator.validate(List.of(
                new Statement("12 employees are here.", Kind.FACT, List.of())), List.of(DAY));

        assertThat(result.droppedUncited()).isEqualTo(1);
    }

    private static Statement fact(String text, String... ids) {
        return new Statement(text, Kind.FACT, List.of(ids));
    }

    private static EvidenceUnit unit(String id, Map<String, Object> values) {
        return new EvidenceUnit(id, id.startsWith("task") ? "task" : "attendance", "test", values,
                SourceRef.entity("test", id), 1.0);
    }
}
