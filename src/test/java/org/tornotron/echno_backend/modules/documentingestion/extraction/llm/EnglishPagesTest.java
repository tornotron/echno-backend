package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/** English only (design section 7.4, product owner's decision Q4): which pages are sent. */
class EnglishPagesTest {

    @Test
    void aPageTheWorkerNamesAsNotLatinIsNotSent() {
        UnreadPage u = EnglishPages.notSent(page("निविदा सूचना", "DEVANAGARI", true));

        assertThat(u.reason()).isEqualTo(UnreadPage.NOT_ENGLISH);
        assertThat(u.detail()).contains("Devanagari");
    }

    @Test
    void anOlderOutputWithNoScriptKeyHasItsScriptWorkedOut() {
        assertThat(EnglishPages.notSent(page("தமிழ் மொழியில் உள்ள ஒப்பந்த நிபந்தனைகள் இங்கே உள்ளன", null, false)))
                .isNotNull();
        assertThat(EnglishPages.notSent(page("General conditions of contract shall apply to the works", null, false)))
                .isNull();
    }

    @Test
    void latinTextWithAlmostNoEnglishIsNotSent() {
        String french = ("Les conditions générales du marché sont applicables aux travaux décrits ci-après pour le "
                + "maître d'ouvrage selon les clauses suivantes ").repeat(3);

        assertThat(EnglishPages.notSent(page(french, "LATIN", true))).extracting(UnreadPage::reason)
                .isEqualTo(UnreadPage.NOT_ENGLISH);
    }

    @Test
    void anEnglishPageAnOcrPageAndAFailedPage() {
        String english = ("The contractor shall complete the works within the time for completion stated in the "
                + "contract data and any extension of time granted ").repeat(3);
        assertThat(EnglishPages.notSent(page(english, "LATIN", true))).isNull();
        assertThat(EnglishPages.notSent(new PageText(1, "OCR", null, null, List.of(new PageText.Line(english, null, 0.8)),
                "OK", null, null, true))).isNull();
        assertThat(EnglishPages.notSent(new PageText(1, "NONE", null, null, List.of(), "FAILED", "The page is damaged.",
                null, true)).reason()).isEqualTo(UnreadPage.NOT_PARSED);
    }

    static PageText page(String text, String script, boolean reported) {
        return new PageText(1, "TEXT", 595.0, 842.0, List.of(new PageText.Line(text, List.of(0.1, 0.1, 0.9, 0.2), null)),
                "OK", null, script, reported);
    }
}
