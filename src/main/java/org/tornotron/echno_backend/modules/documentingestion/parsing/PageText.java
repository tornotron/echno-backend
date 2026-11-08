package org.tornotron.echno_backend.modules.documentingestion.parsing;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One line of {@code pages.jsonl}, as the worker contract (section 7) states it.
 *
 * @param page           Physical page number, from 1
 * @param method         {@code TEXT}, {@code OCR} or {@code NONE}
 * @param lines          The page's lines in reading order
 * @param status         {@code OK} or {@code FAILED}
 * @param reason         Why a page failed, or why it holds nothing, or null
 * @param script         Worker 0.2.0: the Unicode script most letters of the text layer belong to, such
 *                       as {@code LATIN} or {@code DEVANAGARI}; null on OCR pages and pages with too few
 *                       letters
 * @param scriptReported Whether the worker wrote the {@code script} key at all; an older worker did not
 */
public record PageText(int page, String method, Double width, Double height, List<Line> lines, String status,
                       String reason, String script, boolean scriptReported) {

    public PageText {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean isOcr() {
        return "OCR".equals(method);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    /** The page's text, one line per line. */
    public String text() {
        return lines.stream().map(Line::text).collect(Collectors.joining("\n"));
    }

    /**
     * One line of text with its box.
     *
     * @param box           Normalized {@code [x0, y0, x1, y1]}, top-left origin, or null
     * @param ocrConfidence Mean word confidence 0 to 1 on an OCR page, else null
     */
    public record Line(String text, List<Double> box, Double ocrConfidence) {

        public Line {
            text = text == null ? "" : text;
        }
    }
}
