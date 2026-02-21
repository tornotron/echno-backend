package org.tornotron.echno_backend.category;

public class CategoryNormalizer {

    public static String normalize(String input) {
        if (input == null) return null;

        String normalized = input.toLowerCase().trim();

        normalized = normalized.replace("&", "and");

        normalized = normalized.replaceAll("[^a-z0-9\\s]", "");

        normalized = normalized.trim().replaceAll("\\s+", " ");

        return normalized;
    }
}
