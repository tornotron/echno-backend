package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/**
 * Which pages may be sent. This version reads English only (product owner, 8 Nov 2026): a page whose
 * script is not Latin, or whose Latin text has almost none of the commonest English words, is stored,
 * shown as not read and never sent. A page the worker could not read, or with no text, is not sent either.
 */
public final class EnglishPages {

    /** Words are counted only on a page with at least this many; a short page is let through. */
    static final int MIN_WORDS_TO_JUDGE = 40;
    /** Below this share of common English words, a Latin-script page is taken to be in another language. */
    static final double MIN_ENGLISH_SHARE = 0.04;
    /** Letters needed before a script is named, as the worker counts them. */
    static final int MIN_LETTERS = 20;

    private static final Set<String> COMMON_ENGLISH = Set.of(
            "the", "of", "and", "to", "in", "for", "be", "is", "by", "or", "on", "with", "as", "at", "any",
            "all", "this", "that", "will", "from", "are", "not", "shall", "which", "an", "a", "have", "has",
            "per", "no", "if", "such", "work", "works", "contractor", "tender", "date", "rate", "amount");

    private EnglishPages() {
    }

    /** Why the page is not sent, or null when it may be. */
    public static UnreadPage notSent(PageText page) {
        if (page.isFailed()) {
            return new UnreadPage(page.page(), UnreadPage.NOT_PARSED,
                    page.reason() == null ? "The file reader could not read this page." : page.reason());
        }
        String text = page.text();
        if (text.isBlank()) {
            return null;
        }
        String script = page.scriptReported() ? page.script() : dominantScript(text);
        if (script != null && !"LATIN".equals(script)) {
            return new UnreadPage(page.page(), UnreadPage.NOT_ENGLISH,
                    "Mostly in " + title(script) + " script. This version reads English only, so it was not sent.");
        }
        if (!looksEnglish(text)) {
            return new UnreadPage(page.page(), UnreadPage.NOT_ENGLISH,
                    "Not in English. This version reads English only, so it was not sent.");
        }
        return null;
    }

    /**
     * Why a page is not sent on its script alone, or null when it may be: a failed page, or one mostly in
     * a script other than Latin. For short text such as a title block, where the common-word check of
     * {@link #notSent} would refuse English that is all names and codes.
     */
    public static UnreadPage notSentForScript(PageText page) {
        if (page.isFailed()) {
            return new UnreadPage(page.page(), UnreadPage.NOT_PARSED,
                    page.reason() == null ? "The file reader could not read this page." : page.reason());
        }
        String script = page.scriptReported() ? page.script() : dominantScript(page.text());
        if (script != null && !"LATIN".equals(script)) {
            return new UnreadPage(page.page(), UnreadPage.NOT_ENGLISH,
                    "Mostly in " + title(script) + " script. This version reads English only, so it was not sent.");
        }
        return null;
    }

    /** Whether a page holds text worth sending at all. */
    public static boolean hasText(PageText page) {
        return !page.isFailed() && !page.text().isBlank();
    }

    static boolean looksEnglish(String text) {
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+");
        int counted = 0;
        int common = 0;
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            counted++;
            if (COMMON_ENGLISH.contains(w)) {
                common++;
            }
        }
        return counted < MIN_WORDS_TO_JUDGE || (double) common / counted >= MIN_ENGLISH_SHARE;
    }

    /** The Unicode script most letters belong to, as the worker names it, or null with too few letters. */
    static String dominantScript(String text) {
        Map<Character.UnicodeScript, Integer> counts = new HashMap<>();
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letters++;
                counts.merge(Character.UnicodeScript.of(cp), 1, Integer::sum);
            }
        }
        if (letters < MIN_LETTERS) {
            return null;
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey().name()).orElse(null);
    }

    private static String title(String script) {
        String lower = script.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
