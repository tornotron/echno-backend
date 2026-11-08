package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/**
 * The pages of one call, from one document. Its key is its first and last page, so a chunk split in
 * half has two new keys and the chunk before the split keeps its own.
 */
public record ChunkPages(List<PageText> pages) {

    public ChunkPages {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("A chunk holds at least one page");
        }
        pages = List.copyOf(pages);
    }

    /** Consecutive chunks of at most {@code size} pages, in page order. */
    public static List<ChunkPages> of(List<PageText> pages, int size) {
        int step = Math.max(1, size);
        List<ChunkPages> chunks = new ArrayList<>();
        for (int i = 0; i < pages.size(); i += step) {
            chunks.add(new ChunkPages(pages.subList(i, Math.min(i + step, pages.size()))));
        }
        return chunks;
    }

    public String key() {
        return first() + "-" + last();
    }

    public int first() {
        return pages.get(0).page();
    }

    public int last() {
        return pages.get(pages.size() - 1).page();
    }

    public int size() {
        return pages.size();
    }

    public List<Integer> pageNumbers() {
        return pages.stream().map(PageText::page).toList();
    }

    public boolean contains(int page) {
        return pages.stream().anyMatch(p -> p.page() == page);
    }

    public Optional<PageText> page(int page) {
        return pages.stream().filter(p -> p.page() == page).findFirst();
    }

    /** The two halves of a chunk of two or more pages. */
    public List<ChunkPages> halves() {
        if (pages.size() < 2) {
            throw new IllegalStateException("A one-page chunk cannot be split");
        }
        int mid = pages.size() / 2;
        return List.of(new ChunkPages(pages.subList(0, mid)), new ChunkPages(pages.subList(mid, pages.size())));
    }

    /** The pages as {@code 1-4,6,9}, for the ledger. */
    public String describe() {
        return describe(pageNumbers());
    }

    public static String describe(List<Integer> numbers) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < numbers.size()) {
            int start = numbers.get(i);
            int end = start;
            while (i + 1 < numbers.size() && numbers.get(i + 1) == end + 1) {
                end = numbers.get(++i);
            }
            parts.add(start == end ? Integer.toString(start) : start + "-" + end);
            i++;
        }
        return parts.stream().collect(Collectors.joining(","));
    }

    /** The reverse of {@link #describe(List)}. */
    public static List<Integer> parse(String described) {
        List<Integer> out = new ArrayList<>();
        if (described == null || described.isBlank()) {
            return out;
        }
        for (String part : described.split(",")) {
            String[] range = part.strip().split("-");
            int from = Integer.parseInt(range[0].strip());
            int to = range.length > 1 ? Integer.parseInt(range[1].strip()) : from;
            for (int p = from; p <= to; p++) {
                out.add(p);
            }
        }
        return out;
    }
}
