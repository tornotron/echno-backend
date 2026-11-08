package org.tornotron.echno_backend.modules.documentingestion.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The pages of a part that were read, as a person reads them: "1 to 8, 11 to 40". */
public final class PagesSearched {

    private PagesSearched() {
    }

    /** The pages from {@code from} to {@code to} less those not read, in order. */
    public static List<Integer> read(int from, int to, Collection<Integer> notRead) {
        Set<Integer> skip = new TreeSet<>(notRead);
        List<Integer> pages = new ArrayList<>();
        for (int p = from; p <= to; p++) {
            if (!skip.contains(p)) {
                pages.add(p);
            }
        }
        return pages;
    }

    /** Runs of pages joined with commas, a run of one page as its number; empty for no pages. */
    public static String describe(List<Integer> pages) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(pages));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            int start = sorted.get(i);
            int end = start;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == end + 1) {
                end = sorted.get(++i);
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(start == end ? Integer.toString(start) : start + " to " + end);
            i++;
        }
        return sb.toString();
    }
}
