package org.tornotron.echno_backend.pdfGeneration;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Turns a rendered HTML document into PDF bytes.
 *
 * <p>The four lines this holds were copied between the invoice service and the
 * site progress controller, and every new report copied them again. Having one
 * copy means the renderer is configured the same way everywhere: notably fast
 * mode, which is openhtmltopdf's supported renderer, and a {@code
 * classpath:/templates/} base URI so a document may reference a sibling template
 * resource.
 *
 * <p>Nothing here fetches anything over the network. The builder is left with its
 * default user agent, which is only ever asked to resolve classpath-relative
 * references, and every image a report prints is embedded in the HTML as a
 * {@code data:} URI before it gets here. That is deliberate rather than
 * incidental: a report renders from strings held in the database, and letting the
 * renderer dereference one of those would hand a request-scoped thread an
 * arbitrary outbound fetch with no timeout.
 */
@Component
public class PdfRenderer {

    /**
     * Renders one HTML document.
     *
     * @param html A complete, well-formed XHTML document. openhtmltopdf parses
     *             strictly, so an unclosed tag is an error rather than a guess.
     * @return The PDF bytes.
     * @throws IOException if the document cannot be parsed or written.
     */
    public byte[] render(String html) throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, "classpath:/templates/");
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        }
    }
}
