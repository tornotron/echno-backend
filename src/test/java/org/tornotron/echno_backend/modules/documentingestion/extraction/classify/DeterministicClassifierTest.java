package org.tornotron.echno_backend.modules.documentingestion.extraction.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/** The classifier that needs no LLM: file type, file name, headings and page splits, on synthetic pages. */
class DeterministicClassifierTest {

    @Test
    void fileNamesNameTheirClass() {
        assertThat(DeterministicClassifier.fromFileName("CorrigendumNo1_04may23.pdf")).contains(DocClass.CORRIGENDUM);
        assertThat(DeterministicClassifier.fromFileName("Volume 6 - Price Bid_1.pdf")).contains(DocClass.BOQ);
        assertThat(DeterministicClassifier.fromFileName("tender_boq_flooring.xls")).contains(DocClass.BOQ);
        assertThat(DeterministicClassifier.fromFileName("Special Conditions of Contract.pdf")).contains(DocClass.SCC);
        assertThat(DeterministicClassifier.fromFileName("GCC.pdf")).contains(DocClass.GCC);
        assertThat(DeterministicClassifier.fromFileName("Corrigendum to NIT 12.pdf")).contains(DocClass.CORRIGENDUM);
        assertThat(DeterministicClassifier.fromFileName("170116378488.pdf")).isEmpty();
    }

    @Test
    void headingsSplitOnePdfIntoItsPartsAndATableOfContentsSplitsNothing() {
        List<PageText> pages = new ArrayList<>();
        pages.add(page(1, "CONTENTS", "Notice inviting tender 3", "General conditions of contract 5", "Bill of quantities 9"));
        pages.add(page(2, "NOTICE INVITING TENDER", "Name of work: synthetic works"));
        pages.add(page(3, "The bidder shall fill the bill of quantities in the format at the end of this document."));
        pages.add(page(4, "Section 3", "General Conditions of Contract", "1. Definitions"));
        pages.add(page(5, "1.2 The engineer means the person named."));
        pages.add(page(6, "BILL OF QUANTITIES"));

        DeterministicClassifier.Decision d = DeterministicClassifier.classify("Tender document.pdf", DocumentFileType.PDF,
                List.of(), pages).orElseThrow();

        assertThat(d.documentClass()).isEqualTo(DocClass.NIT);
        assertThat(d.segments()).extracting(s -> s.docClass() + " " + s.pageFrom() + "-" + s.pageTo())
                .containsExactly("NIT 1-3", "GCC 4-5", "BOQ 6-6");
        assertThat(d.segments().get(1).reason()).isEqualTo("A heading on page 4 says general conditions of contract.");
    }

    @Test
    void aPdfWithNothingPlainIsLeftForTheLlm() {
        assertThat(DeterministicClassifier.classify("scan.pdf", DocumentFileType.PDF, List.of(),
                List.of(page(1, "Clause 4.1 applies to the works.")))).isEmpty();
    }

    @Test
    void workbooksAndDrawingsAreClassifiedFromTheirNameSheetsOrType() {
        assertThat(DeterministicClassifier.classify("rates.xlsx", DocumentFileType.XLSX, List.of("Abstract", "Schedule of Quantities"),
                List.of()).orElseThrow().documentClass()).isEqualTo(DocClass.BOQ);
        assertThat(DeterministicClassifier.classify("misc.xlsx", DocumentFileType.XLSX, List.of("Sheet1"), List.of())
                .orElseThrow().documentClass()).isEqualTo(DocClass.OTHER);
        assertThat(DeterministicClassifier.classify("A-101.dxf", DocumentFileType.DXF, List.of(), List.of())
                .orElseThrow().documentClass()).isEqualTo(DocClass.DRAWING);
    }

    static PageText page(int n, String... lines) {
        List<PageText.Line> out = new ArrayList<>();
        for (String l : lines) {
            out.add(new PageText.Line(l, null, null));
        }
        return new PageText(n, "TEXT", 595.0, 842.0, out, "OK", null, "LATIN", true);
    }
}
