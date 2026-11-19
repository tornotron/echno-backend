package org.tornotron.echno_backend.modules.assistant.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;

/**
 * Extracts plain text from document attachments.
 *
 * <p>Supports PDF via Apache PDFBox (page by page) and plain text UTF-8 files.
 * Rejects any unsupported content type with an {@link UnsupportedDocumentTypeException}.
 */
@Slf4j
@Component
@AssistantModuleEnabled
public class DocumentExtractor {

    private static final String PDF_MIME = "application/pdf";
    private static final String TEXT_MIME = "text/plain";

    /**
     * Extracts text page by page from the document bytes with known MIME type.
     */
    public ExtractedDocument extract(byte[] content, String contentType) {
        return extract(content, contentType, null);
    }

    /**
     * Extracts text page by page from the document bytes.
     *
     * @param content     the raw bytes of the document
     * @param contentType MIME type of the document, e.g. "application/pdf"
     * @param filename    optional original filename to infer type if MIME is generic
     * @return an {@link ExtractedDocument} containing the page-wise extracted text
     */
    public ExtractedDocument extract(byte[] content, String contentType, String filename) {
        if (content == null || content.length == 0) {
            return new ExtractedDocument(List.of());
        }

        String mime = contentType != null ? contentType.toLowerCase().trim() : "";
        String name = filename != null ? filename.toLowerCase().trim() : "";

        if (isPdf(mime, name)) {
            return extractPdf(content);
        } else if (isPlainText(mime, name)) {
            return extractPlainText(content);
        } else {
            throw new UnsupportedDocumentTypeException(
                    "Unsupported document type: '" + contentType + "'. Only PDF and plain text documents can be ingested.");
        }
    }

    private boolean isPdf(String mime, String name) {
        return PDF_MIME.equals(mime) || name.endsWith(".pdf");
    }

    private boolean isPlainText(String mime, String name) {
        return TEXT_MIME.equals(mime) || name.endsWith(".txt") || name.endsWith(".text");
    }

    private ExtractedDocument extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            int numPages = document.getNumberOfPages();
            List<ExtractedPage> pages = new ArrayList<>(numPages);
            PDFTextStripper stripper = new PDFTextStripper();

            for (int p = 1; p <= numPages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(document);
                pages.add(new ExtractedPage(p, pageText != null ? pageText : ""));
            }
            return new ExtractedDocument(pages);
        } catch (IOException e) {
            log.error("Failed to parse PDF document bytes: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to read PDF document: " + e.getMessage(), e);
        }
    }

    private ExtractedDocument extractPlainText(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        return new ExtractedDocument(List.of(new ExtractedPage(1, text)));
    }
}
