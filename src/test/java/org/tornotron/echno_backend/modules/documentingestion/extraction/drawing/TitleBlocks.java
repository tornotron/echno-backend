package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import java.util.ArrayList;
import java.util.List;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TitleBlock;

/** Builds title blocks for tests: words placed at a left edge and a row's top, 0.008 high. */
final class TitleBlocks {

    static final double H = 0.008;

    private final int page;
    private final String method;
    private final List<PageText.Line> words = new ArrayList<>();

    private TitleBlocks(int page, String method) {
        this.page = page;
        this.method = method;
    }

    static TitleBlocks page(int page) {
        return new TitleBlocks(page, "TEXT");
    }

    static TitleBlocks ocrPage(int page) {
        return new TitleBlocks(page, "OCR");
    }

    /** Words of one row, left to right from {@code x}, each 0.006 per character plus a gap. */
    TitleBlocks row(double y, double x, String... texts) {
        double at = x;
        for (String t : texts) {
            double w = 0.006 * t.length();
            words.add(new PageText.Line(t, List.of(at, y, at + w, y + H), "OCR".equals(method) ? 0.9 : null));
            at += w + 0.004;
        }
        return this;
    }

    TitleBlock build() {
        return new TitleBlock(page, "OK", null, method, List.of(0.75, 0.6, 1.0, 1.0), "RULED_BOX",
                "titleblock/" + page + ".webp", words, lines());
    }

    /** The words grouped into lines by row, as the worker groups them. */
    private List<PageText.Line> lines() {
        List<PageText.Line> out = new ArrayList<>();
        List<PageText.Line> row = new ArrayList<>();
        for (PageText.Line w : words) {
            if (!row.isEmpty() && Math.abs(row.get(0).box().get(1) - w.box().get(1)) > 1e-9) {
                out.add(join(row));
                row = new ArrayList<>();
            }
            row.add(w);
        }
        if (!row.isEmpty()) {
            out.add(join(row));
        }
        return out;
    }

    private static PageText.Line join(List<PageText.Line> row) {
        StringBuilder sb = new StringBuilder();
        row.forEach(w -> sb.append(sb.isEmpty() ? "" : " ").append(w.text()));
        return new PageText.Line(sb.toString(), List.of(row.get(0).box().get(0), row.get(0).box().get(1),
                row.get(row.size() - 1).box().get(2), row.get(0).box().get(3)), row.get(0).ocrConfidence());
    }
}
