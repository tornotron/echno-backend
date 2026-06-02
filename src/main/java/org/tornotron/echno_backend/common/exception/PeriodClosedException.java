package org.tornotron.echno_backend.common.exception;

import java.time.LocalDate;

public class PeriodClosedException extends RuntimeException {
    public PeriodClosedException(LocalDate date) {
        super("Cannot post to closed period for date: "+ date);
    }
}
