package org.tornotron.echno_backend.modules.inspections.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistIncompleteException;

import java.time.LocalDateTime;

/**
 * Ordered ahead of the global handler, whose catch-all would otherwise turn a
 * refused submission into a 500.
 *
 * <p>The body is RFC 7807 with the legacy {@code message} key the clients read,
 * plus the structured list: {@code unansweredItems} is what the client points at,
 * and {@code detail} is the sentence for the client that does not.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InspectionsExceptionHandler {

    public static final String TITLE = "Checklist Incomplete";

    @ExceptionHandler(ChecklistIncompleteException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ProblemDetail handleChecklistIncomplete(ChecklistIncompleteException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle(TITLE);
        pd.setProperty("message", ex.getMessage());
        pd.setProperty("timestamp", LocalDateTime.now());
        pd.setProperty("inspectionId", ex.getInspectionId());
        pd.setProperty("inspectionNumber", ex.getInspectionNumber());
        pd.setProperty("unansweredCount", ex.getItems().size());
        pd.setProperty("unansweredItems", ex.getItems());
        return pd;
    }
}
