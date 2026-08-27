package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-value round trips and the type-to-category derivation for the taxonomy
 * enums. Plain JUnit, no Spring context: these are pure value objects.
 */
class InspectionTaxonomyEnumsTest {

    @Test
    void category_roundTripsThroughItsWireValue() {
        for (InspectionCategory category : InspectionCategory.values()) {
            assertThat(InspectionCategory.fromValue(category.getValue())).isEqualTo(category);
            assertThat(InspectionCategory.fromValue(upper(category.getValue()))).isEqualTo(category);
        }
    }

    @Test
    void trade_roundTripsThroughItsWireValue() {
        for (InspectionTrade trade : InspectionTrade.values()) {
            assertThat(InspectionTrade.fromValue(trade.getValue())).isEqualTo(trade);
            assertThat(InspectionTrade.fromValue(upper(trade.getValue()))).isEqualTo(trade);
        }
    }

    @Test
    void defectSeverity_roundTripsThroughItsWireValue() {
        for (DefectSeverity severity : DefectSeverity.values()) {
            assertThat(DefectSeverity.fromValue(severity.getValue())).isEqualTo(severity);
            assertThat(DefectSeverity.fromValue(upper(severity.getValue()))).isEqualTo(severity);
        }
    }

    @Test
    void defectStatus_roundTripsThroughItsWireValue() {
        for (DefectStatus status : DefectStatus.values()) {
            assertThat(DefectStatus.fromValue(status.getValue())).isEqualTo(status);
            assertThat(DefectStatus.fromValue(upper(status.getValue()))).isEqualTo(status);
        }
    }

    @Test
    void unknownValues_areRejected() {
        assertThatThrownBy(() -> InspectionCategory.fromValue("structural"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown inspection category");
        assertThatThrownBy(() -> InspectionTrade.fromValue("roofing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown inspection trade");
        assertThatThrownBy(() -> DefectSeverity.fromValue("catastrophic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown defect severity");
        assertThatThrownBy(() -> DefectStatus.fromValue("wont-fix"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown defect status");
    }

    @Test
    void defectEnums_keepTheWireValuesTheWebContractAlreadyUses() {
        assertThat(Arrays.stream(DefectSeverity.values()).map(DefectSeverity::getValue))
                .containsExactly("critical", "major", "minor");
        assertThat(Arrays.stream(DefectStatus.values()).map(DefectStatus::getValue))
                .containsExactly("open", "in-progress", "resolved", "verified");
    }

    @Test
    void everyType_derivesACategory() {
        for (InspectionType type : InspectionType.values()) {
            assertThat(InspectionCategory.defaultFor(type))
                    .as("category for type %s", type)
                    .isNotNull();
        }
    }

    @Test
    void categoryDerivation_followsTheSpecMapping() {
        assertThat(InspectionCategory.defaultFor(InspectionType.SAFETY))
                .isEqualTo(InspectionCategory.SAFETY);
        assertThat(InspectionCategory.defaultFor(InspectionType.COMPLIANCE))
                .isEqualTo(InspectionCategory.COMPLIANCE);
        assertThat(InspectionCategory.defaultFor(InspectionType.QUALITY))
                .isEqualTo(InspectionCategory.QA_QC);
        assertThat(InspectionCategory.defaultFor(InspectionType.STRUCTURAL))
                .isEqualTo(InspectionCategory.QA_QC);
        assertThat(InspectionCategory.defaultFor(InspectionType.ELECTRICAL))
                .isEqualTo(InspectionCategory.QA_QC);
        assertThat(InspectionCategory.defaultFor(InspectionType.PLUMBING))
                .isEqualTo(InspectionCategory.QA_QC);
        assertThat(InspectionCategory.defaultFor(InspectionType.FINISHING))
                .isEqualTo(InspectionCategory.QA_QC);
        assertThat(InspectionCategory.defaultFor(InspectionType.PROGRESS))
                .isEqualTo(InspectionCategory.QA_QC);
        assertThat(InspectionCategory.defaultFor(InspectionType.FINAL))
                .isEqualTo(InspectionCategory.QA_QC);
    }

    @Test
    void categoryDerivation_fallsBackToOtherWithoutAType() {
        assertThat(InspectionCategory.defaultFor(null)).isEqualTo(InspectionCategory.OTHER);
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }
}
