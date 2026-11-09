package org.tornotron.echno_backend.modules.documentingestion.parsing;

import java.util.List;

/**
 * One line of {@code titleblock.jsonl} (worker 0.3.0, contract section 7): the title-block region of one
 * PDF page, its words and lines with their boxes on the page, and the crop image.
 *
 * @param status       {@code OK}, {@code SKIPPED} (not shaped like a drawing sheet) or {@code FAILED}
 * @param reason       Why a line was skipped or failed, or why an {@code OK} region holds nothing
 * @param method       {@code TEXT}, {@code OCR} or {@code NONE}
 * @param region       The title-block box, normalized {@code [x0, y0, x1, y1]}, or null on a skipped line
 * @param regionSource {@code RULED_BOX} or {@code DEFAULT}, or null on a skipped line
 * @param image        The crop's key relative to the output prefix, such as {@code titleblock/1.webp}, or null
 * @param words        Single words in reading order, boxes on the page
 * @param lines        The words grouped into lines, as {@code pages.jsonl} groups them
 */
public record TitleBlock(int page, String status, String reason, String method, List<Double> region,
                         String regionSource, String image, List<PageText.Line> words, List<PageText.Line> lines) {

    public TitleBlock {
        words = words == null ? List.of() : List.copyOf(words);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean isOk() {
        return "OK".equals(status);
    }

    public boolean isSkipped() {
        return "SKIPPED".equals(status);
    }

    public boolean isOcr() {
        return "OCR".equals(method);
    }
}
