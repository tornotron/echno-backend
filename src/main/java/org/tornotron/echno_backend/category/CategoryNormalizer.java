package org.tornotron.echno_backend.category;

import java.util.Locale;

public class CategoryNormalizer {

    public static String normalize(String input) {
        if (input == null) return null;

        // Locale.ROOT, because this value is the uniqueness key and the strip below is
        // ASCII-only. Under tr-TR the default lowering turns I into a dotless small i,
        // which [^a-z0-9\s] then deletes rather than folds, so "MIX" would normalise to
        // "mx" on one host and "mix" on another. See #719.
        String normalized = input.toLowerCase(Locale.ROOT).trim();

        normalized = normalized.replace("&", "and");

        normalized = normalized.replaceAll("[^a-z0-9\\s]", "");

        normalized = normalized.trim().replaceAll("\\s+", " ");

        return normalized;
    }
}
