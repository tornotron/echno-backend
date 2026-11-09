package org.tornotron.echno_backend.modules.documentingestion.extraction.quality;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StandardsTest {

    @Test
    void indianStandardsAreFoundAsPrintedInTheFormsTendersUse() {
        assertThat(Standards.find("Concrete shall be M25 and shall comply with IS 456:2000."))
                .containsExactly("IS 456:2000");
        assertThat(Standards.find("Cement IS:8112:1989 and Steel Reinforcement IS1786:2008"))
                .containsExactly("IS:8112:1989", "IS1786:2008");
        assertThat(Standards.find("Aggregates shall be tested as per IS 2386 (Part 3)-1963 and IS 383 : 2016."))
                .containsExactly("IS 2386 (Part 3)-1963", "IS 383 : 2016");
        assertThat(Standards.find("IS: 1489 (Part 1)")).containsExactly("IS: 1489 (Part 1)");
    }

    @Test
    void roadAndForeignCodesAreFound() {
        assertThat(Standards.find("Bituminous works to IRC:SP:73-2018 and ASTM C150; cement to BS EN 197-1."))
                .containsExactly("IRC:SP:73-2018", "ASTM C150", "BS EN 197");
        assertThat(Standards.find("Quality system to ISO 9001:2015")).containsExactly("ISO 9001:2015");
    }

    @Test
    void twoSpellingsOfOneCodeAreOneStandardAndLowerCaseIsIsNotACode() {
        assertThat(Standards.find("IS 456:2000, also printed as IS:456:2000")).containsExactly("IS 456:2000");
        assertThat(Standards.find("The slump is 75 mm and the mix is 1:1.5:3")).isEmpty();
        assertThat(Standards.find("Concrete shall be M25 and shall comply with the applicable standard.")).isEmpty();
        assertThat(Standards.find(null)).isEmpty();
    }

    @Test
    void isCodeReadsOneCodeOnly() {
        assertThat(Standards.isCode("IS 456:2000")).isTrue();
        assertThat(Standards.isCode("IS xxxx")).isFalse();
        assertThat(Standards.isCode("MoRTH Section 1000")).isFalse();
    }
}
