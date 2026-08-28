package org.tornotron.echno_backend.pdfGeneration;

/**
 * A rendered PDF and the name it should download as.
 *
 * <p>The two travel together so a controller does not have to read the record a
 * second time to find out what to call the file, which is where the download name
 * comes from on every report that has a document number.
 *
 * @param documentName Base name for the download, without the extension.
 * @param content      The PDF bytes.
 */
public record RenderedReport(String documentName, byte[] content) {
}
