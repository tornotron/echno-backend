package org.tornotron.echno_backend.modules.documentingestion.extraction.classify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/**
 * Classifies a document with no LLM, from what is plain to read: its file type, its file name, and the
 * headings at the top of its pages. Where one PDF holds several parts, a heading such as "General
 * Conditions of Contract" starts a new segment that runs until the next. A table of contents, which
 * names every part on one page, is ignored. When none of this gives a class, the document is left
 * undecided for the LLM.
 */
public final class DeterministicClassifier {

    /** Lines read from the top of each page for headings. */
    static final int HEADING_LINES = 8;
    /** Lines of the first page read for a corrigendum, which often names the notice it amends first. */
    static final int FIRST_PAGE_LINES = 15;
    /** Distinct classes among heading-like lines that make a page a list of contents. */
    static final int CONTENTS_CLASSES = 3;
    /** Lines with fewer letters, such as page numbers and stray marks, are not counted as lines. */
    static final int MIN_LINE_LETTERS = 3;
    static final int MAX_HEADING_LENGTH = 90;
    /** Lines read from the top of a page to recognise a table of contents. */
    static final int CONTENTS_LINES = 30;

    /** In order: the first that matches wins, so "special conditions of contract" is SCC before GCC. */
    static final List<Rule> RULES = List.of(
            rule(DocClass.CORRIGENDUM, "corrigend|addend(?:um|a)\\b|\\bamendments? (?:no|to)\\b|\\berrat(?:um|a)\\b"),
            rule(DocClass.SCC, "(?:special|particular|additional) conditions? of (?:the )?contract|\\bscc\\b"),
            rule(DocClass.CONTRACT_DATA, "\\bcontract data\\b|schedule of contract data"),
            rule(DocClass.GCC, "general conditions? of (?:the )?contract|\\bgcc\\b|^conditions? of (?:the )?contract\\b"),
            rule(DocClass.ELIGIBILITY, "eligibility criteria|qualification criteria|qualifying criteria|"
                    + "pre-? ?qualification|minimum eligibility"),
            rule(DocClass.ITB, "instructions? to (?:the )?(?:bidders?|tenderers?)|\\bitb\\b|request for bids?\\b|"
                    + "request for quotations?\\b|\\brfb\\b|\\brfq\\b"),
            rule(DocClass.NIT, "notice inviting (?:e-? ?)?tenders?|\\bn\\.? ?i\\.? ?t\\b|\\be-?nit\\b|tender notice|"
                    + "invitation for (?:e-? ?)?bids?|invitation to tender|\\bifb\\b|tender call notice|\\bdtcn\\b|"
                    + "notice for (?:e-? ?)?tender|tender document"),
            rule(DocClass.BOQ, "bills? of quantit|schedule of quantit|\\bboq\\b|\\bsoq\\b|price bid|price schedule|"
                    + "financial bid|abstract of (?:cost|estimate)|schedule of rates\\b"),
            rule(DocClass.MILESTONE_SCHEDULE, "\\bmilestones?\\b|time schedule|programme of works?|bar chart|"
                    + "schedule of completion|construction schedule"),
            rule(DocClass.SCOPE, "scope of (?:the )?works?\\b|employer'?s requirements"),
            rule(DocClass.SPECIFICATION, "technical specifications?|\\bspecifications\\b|particular specifications?"),
            rule(DocClass.DRAWING, "\\bdrawings?\\b|\\bdrg\\b|layout plan"));

    private static final Pattern CONTENTS = Pattern.compile("^(?:table of )?contents$|^index$");

    record Rule(DocClass docClass, Pattern pattern) {
    }

    /** A proposed segment, with pages null for a workbook or a whole-file class. */
    public record ProposedSegment(DocClass docClass, Integer pageFrom, Integer pageTo, String reason) {
    }

    /** The class of the document, its segments, and what it was read from. */
    public record Decision(DocClass documentClass, List<ProposedSegment> segments, String reason) {
    }

    private DeterministicClassifier() {
    }

    private static Rule rule(DocClass c, String regex) {
        return new Rule(c, Pattern.compile(regex));
    }

    /** The decision, or empty when nothing plain names a class. */
    public static Optional<Decision> classify(String fileName, DocumentFileType type, List<String> sheetNames,
                                              List<PageText> pages) {
        Optional<DocClass> byName = fromFileName(fileName);
        if (type == DocumentFileType.DXF || type == DocumentFileType.DWG) {
            return Optional.of(new Decision(DocClass.DRAWING, List.of(), "A drawing file."));
        }
        if (type != DocumentFileType.PDF) {
            if (byName.isPresent()) {
                return Optional.of(new Decision(byName.get(), List.of(), "The file name says " + label(byName.get()) + "."));
            }
            for (String sheet : sheetNames == null ? List.<String>of() : sheetNames) {
                Optional<DocClass> bySheet = match(normalize(sheet), false);
                if (bySheet.isPresent()) {
                    return Optional.of(new Decision(bySheet.get(), List.of(),
                            "A sheet name says " + label(bySheet.get()) + "."));
                }
            }
            return Optional.of(new Decision(DocClass.OTHER, List.of(),
                    "No class could be read from the file name or its sheets; set it by hand if it is one."));
        }
        List<DocClass> perPage = new ArrayList<>();
        boolean anyHeading = false;
        for (PageText page : pages) {
            DocClass heading = headingClass(page).orElse(null);
            perPage.add(heading);
            anyHeading |= heading != null;
        }
        if (!pages.isEmpty() && byName.isEmpty() && namesACorrigendum(pages.get(0))) {
            perPage.set(0, DocClass.CORRIGENDUM);
            anyHeading = true;
        }
        if (!anyHeading && byName.isEmpty()) {
            return Optional.empty();
        }
        if (pages.isEmpty()) {
            DocClass c = byName.orElseThrow();
            return Optional.of(new Decision(c, List.of(), "The file name says " + label(c) + "."));
        }
        int firstHeading = 0;
        while (firstHeading < perPage.size() - 1 && perPage.get(firstHeading) == null) {
            firstHeading++;
        }
        DocClass first = byName.orElse(perPage.get(firstHeading));
        List<ProposedSegment> segments = new ArrayList<>();
        DocClass current = first;
        int start = pages.get(0).page();
        String reason = byName.isPresent() && (perPage.get(0) == null || perPage.get(0) == first)
                ? "The file name says " + label(first) + "."
                : "A heading on page " + pages.get(firstHeading).page() + " says " + label(first) + ".";
        for (int i = 0; i < pages.size(); i++) {
            DocClass heading = perPage.get(i);
            int page = pages.get(i).page();
            if (heading != null && heading != current) {
                if (page > start) {
                    segments.add(new ProposedSegment(current, start, pages.get(i - 1).page(), reason));
                }
                current = heading;
                start = page;
                reason = "A heading on page " + page + " says " + label(heading) + ".";
            }
        }
        segments.add(new ProposedSegment(current, start, pages.get(pages.size() - 1).page(), reason));
        return Optional.of(new Decision(segments.get(0).docClass(), segments, segments.get(0).reason()));
    }

    public static Optional<DocClass> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String base = fileName.replaceAll("\\.[A-Za-z0-9]{2,5}$", "");
        // Split camel case and digits from letters, so "CorrigendumNo1" and "SCCGCC" read as words.
        String spaced = base.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Za-z])(\\d)", "$1 $2")
                .replaceAll("(\\d)([A-Za-z])", "$1 $2");
        return match(normalize(spaced), false);
    }

    /**
     * The class a page's heading names, or empty. A heading in capitals near the top is the page's title
     * and wins; otherwise a page that lists several parts, such as a table of contents or a cover naming
     * each volume, names none.
     */
    static Optional<DocClass> headingClass(PageText page) {
        if (page.isFailed() || page.lines().isEmpty()) {
            return Optional.empty();
        }
        int seen = 0;
        for (PageText.Line line : page.lines()) {
            String text = line.text().strip();
            if (letters(text) < MIN_LINE_LETTERS) {
                continue;
            }
            if (++seen > HEADING_LINES) {
                break;
            }
            if (text.length() > MAX_HEADING_LENGTH) {
                continue;
            }
            Optional<DocClass> c = match(normalize(text), true);
            if (c.isPresent() && looksLikeHeading(text, c.get())) {
                return isCapitals(text) || !isContents(page) ? c : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** A short line naming a corrigendum near the top of the first page makes the document one. */
    private static boolean namesACorrigendum(PageText page) {
        int seen = 0;
        for (PageText.Line line : page.lines()) {
            String text = line.text().strip();
            if (letters(text) < MIN_LINE_LETTERS) {
                continue;
            }
            if (++seen > FIRST_PAGE_LINES) {
                return false;
            }
            if (text.chars().filter(Character::isLetter).count() <= 40
                    && match(normalize(text), true).filter(c -> c == DocClass.CORRIGENDUM).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** A page headed "Contents" or "Index", or one whose heading-like lines name four or more parts. */
    private static boolean isContents(PageText page) {
        Set<DocClass> named = new LinkedHashSet<>();
        int seen = 0;
        for (PageText.Line line : page.lines()) {
            String text = line.text().strip();
            String n = normalize(text);
            if (letters(text) < MIN_LINE_LETTERS) {
                continue;
            }
            if (++seen > CONTENTS_LINES) {
                break;
            }
            if (seen <= HEADING_LINES && CONTENTS.matcher(n).matches()) {
                return true;
            }
            match(n, false).filter(c -> looksLikeHeading(text, c)).ifPresent(named::add);
        }
        return named.size() >= CONTENTS_CLASSES;
    }

    /**
     * A heading is in capitals, or short and mostly the phrase that names the class; a sentence that
     * mentions a bill of quantities in passing is not a heading.
     */
    private static boolean looksLikeHeading(String text, DocClass c) {
        String n = normalize(text);
        int letters = (int) text.chars().filter(Character::isLetter).count();
        if (letters == 0) {
            return false;
        }
        if (isCapitals(text) && letters <= 80) {
            return true;
        }
        if (letters > 40) {
            return false;
        }
        Matcher m = RULES.stream().filter(r -> r.docClass() == c).findFirst().orElseThrow().pattern().matcher(n);
        if (!m.find()) {
            return false;
        }
        return (m.end() - m.start()) * 100 >= n.length() * 50;
    }

    private static int letters(String text) {
        return (int) text.chars().filter(Character::isLetter).count();
    }

    private static boolean isCapitals(String text) {
        int letters = letters(text);
        return letters > 0 && text.chars().filter(Character::isUpperCase).count() * 10 >= letters * 7L;
    }

    private static Optional<DocClass> match(String normalized, boolean heading) {
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (Rule r : RULES) {
            if (r.pattern().matcher(normalized).find()) {
                return Optional.of(r.docClass());
            }
        }
        return Optional.empty();
    }

    static String normalize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace('_', ' ').replace('%', ' ').replaceAll("[\\s\\u00A0]+", " ")
                .replaceAll("[\"()\\[\\]:;,]", " ").replaceAll(" +", " ").strip();
    }

    static String label(DocClass c) {
        return switch (c) {
            case NIT -> "notice inviting tender";
            case ITB -> "instructions to bidders";
            case ELIGIBILITY -> "eligibility criteria";
            case GCC -> "general conditions of contract";
            case SCC -> "special conditions of contract";
            case CONTRACT_DATA -> "contract data";
            case SCOPE -> "scope of work";
            case BOQ -> "bill of quantities";
            case SPECIFICATION -> "specification";
            case DRAWING -> "drawing";
            case MILESTONE_SCHEDULE -> "milestone schedule";
            case CORRIGENDUM -> "corrigendum";
            case OTHER -> "other";
        };
    }
}
