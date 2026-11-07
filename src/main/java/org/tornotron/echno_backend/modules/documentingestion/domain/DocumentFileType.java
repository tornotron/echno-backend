package org.tornotron.echno_backend.modules.documentingestion.domain;

import java.util.Locale;
import java.util.Optional;

/** The file types a package takes, read from the extension here and from the content by the worker. */
public enum DocumentFileType {
    PDF, XLSX, XLS, DOCX, DXF, DWG, ZIP;

    public static Optional<DocumentFileType> ofFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        return switch (fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "pdf" -> Optional.of(PDF);
            case "xlsx" -> Optional.of(XLSX);
            case "xls" -> Optional.of(XLS);
            case "docx" -> Optional.of(DOCX);
            case "dxf" -> Optional.of(DXF);
            case "dwg" -> Optional.of(DWG);
            case "zip" -> Optional.of(ZIP);
            default -> Optional.empty();
        };
    }
}
