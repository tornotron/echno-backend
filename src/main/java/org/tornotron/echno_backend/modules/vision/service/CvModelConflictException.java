package org.tornotron.echno_backend.modules.vision.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A model key or a version that already exists, or a status change a retired version cannot take. */
@ResponseStatus(HttpStatus.CONFLICT)
public class CvModelConflictException extends RuntimeException {

    public CvModelConflictException(String message) {
        super(message);
    }
}
