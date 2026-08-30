package org.tornotron.echno_backend.common.exception;

/**
 * Raised when a report could not be turned into a PDF: the template failed to
 * evaluate, or the renderer failed to write the document.
 *
 * <p>It exists because the failure it wraps used to arrive at the caller as
 * "Unknown error occurred", with nothing naming the document that failed. A
 * template expression that cannot evaluate is a defect in a specific template,
 * and it usually only shows up once a tenant has the data that reaches the
 * offending row, so the one thing the failure has to carry is which template and
 * which expression. This puts both in the message and in the log line, and
 * separates a broken report from any other server fault.
 */
public class ReportRenderingException extends RuntimeException {

    public ReportRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
