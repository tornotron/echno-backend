package org.tornotron.echno_backend.modules.documentingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyProfile;
import org.tornotron.echno_backend.modules.documentingestion.domain.MatrixStatus;
import org.tornotron.echno_backend.modules.documentingestion.extraction.qualification.QualificationRequirementType;
import org.tornotron.echno_backend.modules.documentingestion.service.QualificationComparison.Basis;
import org.tornotron.echno_backend.modules.documentingestion.service.QualificationComparison.Outcome;

class QualificationComparisonTest {

    static final int YEAR = 2026;
    static final BigDecimal CRORE = BigDecimal.valueOf(10_000_000L);

    static BigDecimal crore(long n) {
        return CRORE.multiply(BigDecimal.valueOf(n));
    }

    static CompanyProfile.SimilarProject project(Long croreValue, Integer year) {
        return new CompanyProfile.SimilarProject("Work", null, croreValue == null ? null : crore(croreValue), year, null);
    }

    static CompanyProfile.TurnoverYear year(String fy, long croreValue) {
        return new CompanyProfile.TurnoverYear(fy, crore(croreValue));
    }

    static CompanyProfile stubProfile() {
        CompanyProfile p = new CompanyProfile();
        p.setYearsInBusiness(11);
        p.setTurnover(List.of(year("2025-26", 132)));
        p.setSimilarProjects(List.of(project(42L, 2024), project(36L, 2023), project(33L, 2022), project(31L, 2021)));
        p.setEquipment(List.of(new CompanyProfile.EquipmentItem("Tower crane", 2, null)));
        p.setPersonnel(List.of(new CompanyProfile.PersonnelEntry("Project manager", null, null, null)));
        return p;
    }

    static Outcome compare(QualificationRequirementType type, String value, String min, String period, CompanyProfile p,
                           MatrixStatus person) {
        return QualificationComparison.compare(type, value, min, period, p, person, YEAR);
    }

    @Test
    void theStubsNumericRowsMatch() {
        CompanyProfile p = stubProfile();

        Outcome turnover = compare(QualificationRequirementType.TURNOVER, "₹100 Cr", null, null, p, null);
        assertThat(turnover.status()).isEqualTo(MatrixStatus.MATCH);
        assertThat(turnover.companyValue()).isEqualTo("₹132 Cr (2025-26)");

        Outcome similar = compare(QualificationRequirementType.SIMILAR_PROJECTS, "3", "₹30 Cr", null, p, null);
        assertThat(similar.status()).isEqualTo(MatrixStatus.MATCH);
        assertThat(similar.companyValue()).isEqualTo("4 qualifying of 4 listed, largest ₹42 Cr");

        Outcome experience = compare(QualificationRequirementType.EXPERIENCE, "7 years", null, null, p, null);
        assertThat(experience.status()).isEqualTo(MatrixStatus.MATCH);
        assertThat(experience.companyValue()).isEqualTo("11 years");
        assertThat(experience.basis()).isEqualTo(Basis.COMPARED);
    }

    @Test
    void figuresBelowTheRequirementAreAGap() {
        CompanyProfile p = stubProfile();
        assertThat(compare(QualificationRequirementType.TURNOVER, "Rs. 150 crore", null, null, p, null).status())
                .isEqualTo(MatrixStatus.GAP);
        assertThat(compare(QualificationRequirementType.SIMILAR_PROJECTS, "2", "Rs. 40 crore", null, p, null).status())
                .as("only one work reaches 40 crore").isEqualTo(MatrixStatus.GAP);
        assertThat(compare(QualificationRequirementType.EXPERIENCE, "fifteen years", null, null, p, null).status())
                .isEqualTo(MatrixStatus.GAP);
        assertThat(compare(QualificationRequirementType.SIMILAR_PROJECTS, "3", null, "in the last 4 years", p, null)
                .status()).as("the 2021 work falls outside four years back from 2026, leaving three").isEqualTo(MatrixStatus.MATCH);
        assertThat(compare(QualificationRequirementType.SIMILAR_PROJECTS, "4", null, "in the last 4 years", p, null)
                .status()).isEqualTo(MatrixStatus.GAP);
    }

    @Test
    void aBlankProfileFieldIsUnknownAndNeverAGap() {
        CompanyProfile empty = new CompanyProfile();
        for (QualificationRequirementType type : QualificationRequirementType.values()) {
            Outcome o = compare(type, "Rs. 999 crore 99 years", null, null, empty, MatrixStatus.GAP);
            assertThat(o.status()).as(type.name()).isEqualTo(MatrixStatus.UNKNOWN);
            assertThat(o.basis()).as(type.name()).isIn(Basis.PROFILE_BLANK, Basis.NOT_READABLE);
            assertThat(o.companyValue()).as(type.name()).isNull();
        }
        for (QualificationRequirementType type : QualificationRequirementType.values()) {
            assertThat(compare(type, "5", null, null, null, MatrixStatus.GAP).status()).as("no profile at all")
                    .isEqualTo(MatrixStatus.UNKNOWN);
        }
        assertThat(compare(QualificationRequirementType.EXPERIENCE, "7 years", null, null, empty, null).basis())
                .isEqualTo(Basis.PROFILE_BLANK);
    }

    @Test
    void aBlankValueOrYearOnAProjectIsUnknownUnlessTheOthersAlreadyMeetIt() {
        CompanyProfile p = new CompanyProfile();
        p.setSimilarProjects(List.of(project(42L, 2024), project(null, 2023), project(35L, null)));

        Outcome three = compare(QualificationRequirementType.SIMILAR_PROJECTS, "3", "Rs. 30 crore", "last 5 years", p, null);
        assertThat(three.status()).isEqualTo(MatrixStatus.UNKNOWN);
        assertThat(three.reason()).startsWith("2 more could qualify");
        assertThat(compare(QualificationRequirementType.SIMILAR_PROJECTS, "1", "Rs. 30 crore", "last 5 years", p, null)
                .status()).isEqualTo(MatrixStatus.MATCH);
        assertThat(compare(QualificationRequirementType.SIMILAR_PROJECTS, "4", "Rs. 30 crore", "last 5 years", p, null)
                .status()).as("even with every blank in its favour, three works cannot make four").isEqualTo(MatrixStatus.GAP);
    }

    @Test
    void turnoverOverAPeriodIsAveragedOrTakenAtItsLowestOrHighestYear() {
        CompanyProfile p = new CompanyProfile();
        p.setTurnover(List.of(year("2023-24", 80), year("2025-26", 120), year("2024-25", 100), year("2020-21", 10)));

        Outcome average = compare(QualificationRequirementType.TURNOVER, "Rs. 100 crore", null,
                "average of the last 3 financial years", p, null);
        assertThat(average.status()).isEqualTo(MatrixStatus.MATCH);
        assertThat(average.companyValue()).isEqualTo("average ₹100 Cr over 2023-24 to 2025-26");
        assertThat(compare(QualificationRequirementType.TURNOVER, "Rs. 100 crore", null, "in each of the last 3 years", p,
                null).status()).isEqualTo(MatrixStatus.GAP);
        assertThat(compare(QualificationRequirementType.TURNOVER, "Rs. 110 crore", null, "in any one of the last 3 years",
                p, null).status()).isEqualTo(MatrixStatus.MATCH);
        Outcome tooFew = compare(QualificationRequirementType.TURNOVER, "Rs. 10 crore", null, "last 5 years", p, null);
        assertThat(tooFew.status()).as("four years entered, five counted").isEqualTo(MatrixStatus.UNKNOWN);
        assertThat(tooFew.basis()).isEqualTo(Basis.PROFILE_BLANK);
    }

    @Test
    void aTenderValueThatIsNotAnAmountIsLeftToAPerson() {
        CompanyProfile p = stubProfile();
        Outcome percent = compare(QualificationRequirementType.SIMILAR_PROJECTS, "3", "40% of the estimated cost", null, p,
                null);
        assertThat(percent.status()).isEqualTo(MatrixStatus.UNKNOWN);
        assertThat(percent.basis()).isEqualTo(Basis.NOT_READABLE);
        assertThat(compare(QualificationRequirementType.TURNOVER, "as stated in the NIT", null, null, p, null).basis())
                .isEqualTo(Basis.NOT_READABLE);
    }

    @Test
    void equipmentAndPersonnelCarryThePersonsStatus() {
        CompanyProfile p = stubProfile();
        Outcome waiting = compare(QualificationRequirementType.PERSONNEL, "5", null, null, p, null);
        assertThat(waiting.status()).isEqualTo(MatrixStatus.UNKNOWN);
        assertThat(waiting.basis()).isEqualTo(Basis.AWAITING_PERSON);
        assertThat(waiting.companyValue()).isEqualTo("1 person listed: Project manager");

        Outcome gap = compare(QualificationRequirementType.PERSONNEL, "5", null, null, p, MatrixStatus.GAP);
        assertThat(gap.status()).isEqualTo(MatrixStatus.GAP);
        assertThat(gap.basis()).isEqualTo(Basis.SET_BY_PERSON);
        assertThat(compare(QualificationRequirementType.EQUIPMENT, "a crane", null, null, p, MatrixStatus.MATCH)
                .companyValue()).isEqualTo("1 item listed: Tower crane (2)");
    }

    @Test
    void amountsReadInCroreAndLakh() {
        assertThat(QualificationComparison.rupees(new BigDecimal("1320000000"))).isEqualTo("₹132 Cr");
        assertThat(QualificationComparison.rupees(new BigDecimal("12500000"))).isEqualTo("₹1.25 Cr");
        assertThat(QualificationComparison.rupees(new BigDecimal("8000000"))).isEqualTo("₹80 lakh");
        assertThat(QualificationComparison.rupees(new BigDecimal("5000"))).isEqualTo("₹5000");
        assertThat(QualificationComparison.wholeNumber("3 (three)")).contains(3);
        assertThat(QualificationComparison.wholeNumber("seven years")).contains(7);
        assertThat(QualificationComparison.wholeNumber("none")).isEmpty();
        assertThat(QualificationComparison.tenderValue(QualificationRequirementType.SIMILAR_PROJECTS, "3", "Rs. 30 Cr",
                "last 7 years")).isEqualTo("3, each at least Rs. 30 Cr (last 7 years)");
    }
}
