package org.tornotron.echno_backend.modules.documentingestion.gate;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers a refusal with 403 and the stable code. Ordered first, because the application's handler
 * has a catch-all that would otherwise take this exception and answer 500.
 */
@RestControllerAdvice(annotations = DocumentIngestionGate.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ModuleDisabledAdvice {

    @ExceptionHandler(ModuleDisabledException.class)
    public ResponseEntity<ProblemDetail> refuse(ModuleDisabledException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Module disabled");
        problem.setProperty("code", ModuleDisabledException.CODE);
        problem.setProperty("moduleId", ex.getModuleId());
        problem.setProperty("reason", ex.getReason().name());
        problem.setProperty("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
