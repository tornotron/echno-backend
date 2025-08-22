package org.tornotron.echno_backend.common.conversions;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateConversion {

    public static String convertDateToString(LocalDateTime javaDate) {
        if (javaDate == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
        return javaDate.format(formatter);
    }
}
