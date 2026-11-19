package org.tornotron.echno_backend.modules.assistant.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentExtractorTest {

    private final DocumentExtractor extractor = new DocumentExtractor();

    @Test
    @DisplayName("Extracts page-by-page text from multi-page PDF")
    void extractsMultiPagePdf() throws IOException {
        byte[] pdfBytes = createPdfWithPages("First page content: Project specifications and soil test report.",
                "Second page content: Foundation details and reinforcement concrete guidelines.");

        ExtractedDocument doc = extractor.extract(pdfBytes, "application/pdf");

        assertThat(doc.pages()).hasSize(2);
        assertThat(doc.pages().get(0).pageNo()).isEqualTo(1);
        assertThat(doc.pages().get(0).text()).contains("First page content");
        assertThat(doc.pages().get(1).pageNo()).isEqualTo(2);
        assertThat(doc.pages().get(1).text()).contains("Second page content");
    }

    @Test
    @DisplayName("Extracts plain text as a single page")
    void extractsPlainText() {
        String content = "Site safety protocols:\n1. Wear helmets at all times.\n2. Steel-toed boots required.";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        ExtractedDocument doc = extractor.extract(bytes, "text/plain");

        assertThat(doc.pages()).hasSize(1);
        assertThat(doc.pages().get(0).pageNo()).isEqualTo(1);
        assertThat(doc.pages().get(0).text()).isEqualTo(content);
    }

    @Test
    @DisplayName("Rejects unsupported MIME types with informative exception")
    void rejectsUnsupportedMimeType() {
        byte[] fakeZip = new byte[]{0x50, 0x4B, 0x03, 0x04};

        assertThatThrownBy(() -> extractor.extract(fakeZip, "application/zip"))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("application/zip");

        assertThatThrownBy(() -> extractor.extract(fakeZip, "image/png"))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("image/png");
    }

    private byte[] createPdfWithPages(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
