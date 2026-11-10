package org.tornotron.echno_backend.modules.documentingestion.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualityRequirementEntry;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort.TemplateItem;

class ChecklistCopierItemTest {

    @Test
    void aRowBecomesACheckPointWithEachValueAsPrintedAndNoAcceptanceCriterionInvented() {
        QualityRequirementEntry row = new QualityRequirementEntry();
        row.setSubject("Concrete");
        row.setActivity("Concrete");
        row.setMaterial("Concrete");
        row.setGrade("M25");
        row.setStandards(List.of("IS 456:2000"));

        TemplateItem item = ChecklistCopier.item(row);

        assertThat(item.category()).isEqualTo("Concrete");
        assertThat(item.checkPoint()).isEqualTo("Concrete");
        assertThat(item.expectedValue()).isEqualTo("M25");
        assertThat(item.specification()).isEqualTo("Standards: IS 456:2000");
        assertThat(item.acceptanceCriterion()).isNull();
        assertThat(item.tolerance()).isNull();
        assertThat(item.priority()).isEqualTo("medium");
    }

    @Test
    void aTestFrequencyHoldPointAndDocumentsAreCarriedAndAHoldPointIsHighPriority() {
        QualityRequirementEntry row = new QualityRequirementEntry();
        row.setSubject("Bricks");
        row.setMaterial("Bricks");
        row.setTest("Compressive strength");
        row.setFrequency("10 bricks from every lot of 50,000");
        row.setAcceptanceCriterion("the average shall be not less than 7.5 N/mm2");
        row.setHoldOrWitnessPoint("Hold point");
        row.setRequiredDocuments(List.of("Test certificate"));
        row.setTradeName("Masonry");

        TemplateItem item = ChecklistCopier.item(row);

        assertThat(item.category()).isEqualTo("Masonry");
        assertThat(item.checkPoint()).isEqualTo("Compressive strength: Bricks");
        assertThat(item.specification()).isEqualTo("Frequency: 10 bricks from every lot of 50,000. Hold or witness "
                + "point: Hold point. Documents: Test certificate");
        assertThat(item.acceptanceCriterion()).isEqualTo("the average shall be not less than 7.5 N/mm2");
        assertThat(item.priority()).isEqualTo("high");
    }

    @Test
    void aSpecificationOverTheTemplateLimitIsRefused() {
        QualityRequirementEntry row = new QualityRequirementEntry();
        row.setSubject("Cement");
        row.setFrequency("x".repeat(1000));
        assertThatThrownBy(() -> ChecklistCopier.item(row)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("1000 characters");
    }
}
