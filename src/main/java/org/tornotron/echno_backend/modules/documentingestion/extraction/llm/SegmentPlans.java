package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/** The plan most segment readers start from: every page of the segment in English and with text. */
public final class SegmentPlans {

    private SegmentPlans() {
    }

    /** Sends the segment's English pages that hold text; names the others as not read. */
    public static ExtractPlan englishPages(ExtractContext context, Map<String, Object> result) {
        List<PageText> send = new ArrayList<>();
        List<UnreadPage> unread = new ArrayList<>();
        for (PageText p : context.pages()) {
            UnreadPage notSent = EnglishPages.notSent(p);
            if (notSent != null) {
                unread.add(notSent);
            } else if (EnglishPages.hasText(p)) {
                send.add(p);
            }
        }
        return new ExtractPlan(send, unread, result);
    }

    /** The pages of a chunk as the model reads them: a {@code [page n]} line, then the page's lines. */
    public static String pagesText(ChunkPages chunk) {
        StringBuilder sb = new StringBuilder();
        for (PageText page : chunk.pages()) {
            sb.append("[page ").append(page.page()).append("]\n").append(page.text()).append('\n');
        }
        return sb.toString();
    }
}
