package org.tornotron.echno_backend.modules.documentingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CorpusGates.Pairing;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CorpusGates.Tally;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqField;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqHeaderDetector;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.DeterministicClassifier;
import org.tornotron.echno_backend.modules.documentingestion.extraction.drawing.DrawingFields;
import org.tornotron.echno_backend.modules.documentingestion.extraction.drawing.DxfTitleBlocks;
import org.tornotron.echno_backend.modules.documentingestion.extraction.drawing.TitleBlockFields;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.milestone.MilestoneTables;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quality.Standards;
import org.tornotron.echno_backend.modules.documentingestion.parsing.DxfReading;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TitleBlock;
import org.tornotron.echno_backend.modules.documentingestion.parsing.WorkbookMeta;

/**
 * The corpus run of build step 24: every path that reads a document with no LLM, run over the worker's outputs
 * for the private evaluation corpus, with the predictions written in the format of the corpus scorer
 * ({@code ingest score} in tornotron/echno-datasets) and the deterministic gates of design section 5 asserted
 * where the corpus holds labels.
 *
 * <p>Runs only when {@code ECHNO_INGEST_CORPUS} names a directory holding the corpus {@code manifest.jsonl}, the
 * worker's outputs under {@code parsed/<document_id>/} and, for the gates, {@code labels.json}. CI has none of
 * them, so it is skipped there. {@code ECHNO_INGEST_PREDICTIONS} names the predictions file; by default it is
 * {@code runs/deterministic-predictions.jsonl} inside the corpus directory, because it carries quotes from the
 * documents and belongs with them.
 *
 * <p>The paths, as the module runs them once a person has confirmed a document's class:
 * <ul>
 *   <li>classification by file name, sheet names and headings, as {@code ClassGateCorpusTest} scores it;</li>
 *   <li>Excel BOQ: header detection on every sheet of a workbook, and the sheet read with the detected mapping
 *       where the detection is complete (a sheet that needs a person to map it is counted, not read). In the
 *       module a person confirms or corrects every sheet's mapping before it is read, so where the corpus
 *       directory holds {@code boq-mappings.json} (document id, then sheet name, then {@code headerRow} and
 *       {@code columns} by field key, as the mapping request takes them) the sheet is read with that mapping,
 *       and the figures for the detected mapping alone are printed beside it;</li>
 *   <li>milestone tables in the worker's tables, with the header rule of a contract-data segment (the header
 *       must say "milestone") unless the document is a milestone schedule, over every page, since the corpus
 *       labels no segment boundaries for the documents that hold milestones;</li>
 *   <li>DXF title-block attributes, and the title-block patterns on PDF sheets of documents classed as drawings
 *       (a title block the worker cropped from a page of any other class is counted, not read);</li>
 *   <li>the standards patterns on the pages labelled for quality requirements. These fill one field of a
 *       candidate the LLM makes, so they write no prediction and are reported against the labelled
 *       standards.</li>
 * </ul>
 *
 * <p>The report prints numbers only, never a file name or any document text, since the corpus is private.
 */
@EnabledIfEnvironmentVariable(named = "ECHNO_INGEST_CORPUS", matches = ".+")
class CorpusGateHarnessTest {

    static final String BOQ_EXCEL_ROWS = "0.99";
    static final String BOQ_EXCEL_NUMBERS = "0.995";
    static final String MILESTONE_ROWS = "0.95";
    static final String TITLE_BLOCK = "0.90";

    /** Decimals read exactly, so a label's number is never rounded through a double. */
    final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** What one corpus document holds after the worker read it. */
    record Parsed(String id, String fileName, DocumentFileType type, String surface, DocClass labelledClass,
                  String sha256, WorkbookMeta meta, List<PageText> pages, List<TableRow> tables,
                  List<TitleBlock> titleBlocks, DxfReading dxf) {
    }

    /** Counts printed at the end; numbers only. */
    final Map<String, Integer> counts = new TreeMap<>();

    @Test
    void theDeterministicPathsMeetTheirGatesOnTheCorpus() throws IOException {
        Path corpus = Path.of(System.getenv("ECHNO_INGEST_CORPUS"));
        String target = System.getenv("ECHNO_INGEST_PREDICTIONS");
        Path out = target == null || target.isBlank() ? corpus.resolve("runs").resolve("deterministic-predictions.jsonl")
                : Path.of(target);
        List<Parsed> documents = load(corpus);
        assertThat(documents).as("parsed corpus documents").isNotEmpty();

        Path mappingsFile = corpus.resolve("boq-mappings.json");
        JsonNode mappings = Files.exists(mappingsFile) ? json.readTree(mappingsFile.toFile()) : json.createObjectNode();

        List<ObjectNode> classes = new ArrayList<>();
        Map<String, List<ObjectNode>> candidates = new LinkedHashMap<>();
        Map<String, List<ObjectNode>> detectedBoq = new LinkedHashMap<>();
        for (Parsed doc : documents) {
            List<ObjectNode> mine = candidates.computeIfAbsent(doc.id(), k -> new ArrayList<>());
            Optional<DeterministicClassifier.Decision> decision = classify(doc);
            decision.ifPresent(d -> {
                ObjectNode line = json.createObjectNode();
                line.put("document_id", doc.id());
                line.put("document_class", d.documentClass().name());
                classes.add(line);
            });
            DocClass routed = doc.labelledClass() != null ? doc.labelledClass()
                    : decision.map(DeterministicClassifier.Decision::documentClass).orElse(DocClass.OTHER);
            if (doc.type() == DocumentFileType.XLS || doc.type() == DocumentFileType.XLSX) {
                mine.addAll(boq(doc, mappings.path(doc.id()), true));
                detectedBoq.put(doc.id(), boq(doc, json.createObjectNode(), false));
            }
            mine.addAll(milestones(doc, routed));
            mine.addAll(drawings(doc, routed));
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (ObjectNode line : classes) {
                w.write(json.writeValueAsString(line));
                w.write('\n');
            }
            for (List<ObjectNode> list : candidates.values()) {
                for (ObjectNode c : list) {
                    w.write(json.writeValueAsString(c));
                    w.write('\n');
                    counts.merge("predictions, " + c.path("kind").asText(), 1, Integer::sum);
                }
            }
        }
        counts.put("documents read", documents.size());
        counts.put("documents classified", classes.size());
        counts.forEach((name, n) -> System.out.printf(Locale.ROOT, "Corpus run: %s: %d%n", name, n));

        Path labelsFile = corpus.resolve("labels.json");
        if (!Files.exists(labelsFile)) {
            System.out.println("Corpus run: no labels.json beside the manifest, so no gate is judged");
            return;
        }
        judge(json.readTree(labelsFile.toFile()), documents, classes, candidates, detectedBoq);
    }

    private void judge(JsonNode labels, List<Parsed> documents, List<ObjectNode> classes,
                       Map<String, List<ObjectNode>> candidates, Map<String, List<ObjectNode>> detectedBoq) {
        Map<String, String> predictedClass = new HashMap<>();
        classes.forEach(c -> predictedClass.put(c.path("document_id").asText(), c.path("document_class").asText()));
        Map<String, Parsed> byId = new HashMap<>();
        documents.forEach(d -> byId.put(d.id(), d));

        Tally classText = Tally.NONE;
        Tally boqRows = Tally.NONE;
        Tally boqNumbers = Tally.NONE;
        int boqSurplus = 0;
        Tally detectedRows = Tally.NONE;
        Tally detectedNumbers = Tally.NONE;
        Tally milestoneRows = Tally.NONE;
        int milestoneSurplus = 0;
        Tally titleBlocks = Tally.NONE;
        Tally standardsFound = Tally.NONE;
        int standardsNotLabelled = 0;
        for (JsonNode doc : labels.path("documents")) {
            String id = doc.path("document_id").asText();
            if (!"text".equals(doc.path("surface").asText())) {
                continue;
            }
            if (!byId.containsKey(id)) {
                // A labelled document with no parsed output still counts: its labels are misses, not absent.
                counts.merge("labelled text documents with no parsed output, counted as missed", 1, Integer::sum);
            }
            classText = classText.plus(doc.path("document_class").asText().equals(predictedClass.get(id)) ? 1 : 0, 1);
            boolean excel = Set.of("xls", "xlsx").contains(doc.path("file_type").asText());
            for (JsonNode coverage : doc.path("coverage")) {
                String kind = coverage.path("kind").asText();
                List<JsonNode> wanted = new ArrayList<>();
                doc.path("candidates").forEach(c -> {
                    if (kind.equals(c.path("kind").asText())) {
                        wanted.add(c);
                    }
                });
                List<JsonNode> offered = new ArrayList<>();
                candidates.getOrDefault(id, List.of()).forEach(c -> {
                    if (kind.equals(c.path("kind").asText()) && CorpusGates.inScope(c, coverage)) {
                        offered.add(c);
                    }
                });
                switch (kind) {
                    case "BOQ_ITEM" -> {
                        if (excel) {
                            Pairing p = CorpusGates.pair(wanted, offered);
                            boqRows = boqRows.plus(p.captured());
                            boqNumbers = boqNumbers.plus(CorpusGates.boqNumbers(p));
                            boqSurplus += p.surplus();
                            List<JsonNode> detected = new ArrayList<>();
                            detectedBoq.getOrDefault(id, List.of()).forEach(c -> {
                                if (CorpusGates.inScope(c, coverage)) {
                                    detected.add(c);
                                }
                            });
                            Pairing d = CorpusGates.pair(wanted, detected);
                            detectedRows = detectedRows.plus(d.captured());
                            detectedNumbers = detectedNumbers.plus(CorpusGates.boqNumbers(d));
                        }
                    }
                    case "MILESTONE" -> {
                        Pairing p = CorpusGates.pair(wanted, offered);
                        milestoneRows = milestoneRows.plus(p.captured());
                        milestoneSurplus += p.surplus();
                    }
                    case "DRAWING" -> titleBlocks = titleBlocks.plus(CorpusGates.titleBlocks(wanted,
                            CorpusGates.pair(wanted, offered)));
                    case "QUALITY_REQUIREMENT" -> {
                        Set<String> labelled = new HashSet<>();
                        wanted.forEach(c -> c.path("payload").path("standards")
                                .forEach(s -> labelled.add(CorpusGates.normalize(s))));
                        Set<String> found = new HashSet<>();
                        Standards.find(byId.containsKey(id) ? text(byId.get(id), coverage.path("pages").asText("")) : "")
                                .forEach(s -> found.add(CorpusGates.normalize(json.getNodeFactory().textNode(s))));
                        Set<String> hit = new HashSet<>(labelled);
                        hit.retainAll(found);
                        standardsFound = standardsFound.plus(hit.size(), labelled.size());
                        found.removeAll(labelled);
                        standardsNotLabelled += found.size();
                    }
                    default -> {
                    }
                }
            }
        }

        System.out.printf(Locale.ROOT, "Corpus run: labelled text documents with no parsed output, counted as "
                + "missed: %d%n", counts.getOrDefault("labelled text documents with no parsed output, counted as missed", 0));
        System.out.printf(Locale.ROOT, "Gate, document class on text documents, deterministic reading alone: %s "
                + "(an undecided document goes to the LLM; ClassGateCorpusTest asserts precision)%n", classText.show());
        System.out.printf(Locale.ROOT, "Gate, BOQ rows captured, Excel (bar %s): %s%n", BOQ_EXCEL_ROWS, boqRows.show());
        System.out.printf(Locale.ROOT, "Gate, BOQ numbers exact on captured rows, Excel (bar %s): %s%n",
                BOQ_EXCEL_NUMBERS, boqNumbers.show());
        System.out.printf(Locale.ROOT, "Reported, BOQ rows predicted beyond the labels, Excel: %d%n", boqSurplus);
        System.out.printf(Locale.ROOT, "Reported, BOQ with the detected mapping alone, Excel: rows %s, numbers %s%n",
                detectedRows.show(), detectedNumbers.show());
        System.out.printf(Locale.ROOT, "Gate, milestone rows captured, tables (bar %s): %s%n", MILESTONE_ROWS,
                milestoneRows.show());
        System.out.printf(Locale.ROOT, "Reported, milestone rows predicted beyond the labels: %d%n", milestoneSurplus);
        System.out.printf(Locale.ROOT, "Gate, title block number and revision (bar %s): %s%n", TITLE_BLOCK,
                titleBlocks.show());
        System.out.printf(Locale.ROOT, "Reported, labelled standards the patterns find on the labelled pages: %s; "
                + "codes found there that no label names: %d%n", standardsFound.show(), standardsNotLabelled);

        if (boqRows.measured()) {
            assertThat(boqRows.meets(BOQ_EXCEL_ROWS)).as("BOQ rows captured, Excel: " + boqRows.show()).isTrue();
        }
        if (boqNumbers.measured()) {
            assertThat(boqNumbers.meets(BOQ_EXCEL_NUMBERS)).as("BOQ numbers exact, Excel: " + boqNumbers.show())
                    .isTrue();
        }
        if (milestoneRows.measured()) {
            assertThat(milestoneRows.meets(MILESTONE_ROWS)).as("milestone rows: " + milestoneRows.show()).isTrue();
        }
        if (titleBlocks.measured()) {
            assertThat(titleBlocks.meets(TITLE_BLOCK)).as("title blocks: " + titleBlocks.show()).isTrue();
        }
    }

    private Optional<DeterministicClassifier.Decision> classify(Parsed doc) {
        List<String> sheets = doc.meta().sheets().stream().map(WorkbookMeta.Sheet::name).toList();
        return DeterministicClassifier.classify(doc.fileName(), doc.type(), sheets, doc.pages());
    }

    /**
     * The BOQ lines of every sheet of a workbook. A sheet with a person's mapping in {@code confirmed} is read
     * with it; otherwise with the detected mapping when that is complete. {@code count} is false for the second
     * pass that measures the detected mapping alone.
     */
    private List<ObjectNode> boq(Parsed doc, JsonNode confirmed, boolean count) {
        List<ObjectNode> out = new ArrayList<>();
        for (WorkbookMeta.Sheet sheet : doc.meta().sheets()) {
            List<TableRow> rows = doc.tables().stream().filter(r -> r.page() == null)
                    .filter(r -> r.sheetIndex() != null ? r.sheetIndex() == sheet.index()
                            : sheet.name() != null && sheet.name().equals(r.sheet()))
                    .toList();
            JsonNode person = confirmed.path(String.valueOf(sheet.name()));
            BoqSheetExtractor.Mapping mapping;
            if (person.path("columns").isObject()) {
                Map<BoqField, Integer> columns = new EnumMap<>(BoqField.class);
                person.path("columns").fields().forEachRemaining(e -> columns.put(
                        BoqField.ofKey(e.getKey()).orElseThrow(), e.getValue().asInt()));
                mapping = new BoqSheetExtractor.Mapping(person.path("headerRow").asInt(), columns, null);
                tally(count, "workbook sheets read with a person's mapping from boq-mappings.json");
            } else {
                Optional<BoqHeaderDetector.Detection> found = BoqHeaderDetector.detect(rows);
                if (found.isEmpty() || !found.get().complete()) {
                    tally(count, "workbook sheets left for a person to map");
                    continue;
                }
                mapping = new BoqSheetExtractor.Mapping(found.get().headerRow(), found.get().columns(), null);
                tally(count, "workbook sheets read with the detected mapping");
            }
            BoqSheetExtractor.Result result = BoqSheetExtractor.extract(rows, sheet.hidden(), mapping);
            for (BoqSheetExtractor.Line line : result.lines()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("code", line.itemCode());
                payload.put("description", line.description());
                payload.put("unit", line.unit());
                payload.put("quantity", line.quantity() == null ? null : line.quantity().toPlainString());
                payload.put("rate", line.rate() == null ? null : line.rate().toPlainString());
                payload.put("amount", line.amount() == null ? null : line.amount().toPlainString());
                ObjectNode c = candidate(doc, "BOQ_ITEM",
                        line.itemCode() != null ? line.itemCode() : sheet.name() + "!" + line.row(), payload);
                ArrayNode refs = c.putArray("source_refs");
                line.sources().forEach((field, cell) -> {
                    ObjectNode ref = refs.addObject();
                    ref.put("field", field == BoqField.ITEM_CODE ? "code" : field.key());
                    ref.put("sheet", sheet.name());
                    ref.put("row", line.row());
                    ref.put("cell", cell.ref());
                    ref.put("quote", cell.text());
                });
                out.add(c);
            }
        }
        return out;
    }

    private void tally(boolean count, String name) {
        if (count) {
            counts.merge(name, 1, Integer::sum);
        }
    }

    private List<ObjectNode> milestones(Parsed doc, DocClass routed) {
        List<TableRow> tables = doc.type() == DocumentFileType.PDF
                ? doc.tables().stream().filter(r -> r.page() != null).toList() : doc.tables();
        if (tables.isEmpty()) {
            return List.of();
        }
        ExtractContext context = context(doc, routed, tables);
        List<ObjectNode> out = new ArrayList<>();
        for (ProposedCandidate p : MilestoneTables.read(context, routed != DocClass.MILESTONE_SCHEDULE)) {
            out.add(fromProposed(doc, p));
        }
        return out;
    }

    private List<ObjectNode> drawings(Parsed doc, DocClass routed) {
        List<ObjectNode> out = new ArrayList<>();
        if (doc.type() == DocumentFileType.DXF && doc.dxf() != null) {
            for (DxfTitleBlocks.Sheet sheet : DxfTitleBlocks.read(doc.dxf())) {
                Map<String, Object> payload = new LinkedHashMap<>();
                sheet.values().forEach((field, v) -> payload.put(field, v.text()));
                payload.putIfAbsent(DrawingFields.NUMBER, DrawingFields.fromFileName(doc.fileName())[0]);
                ObjectNode c = candidate(doc, "DRAWING", String.valueOf(payload.get(DrawingFields.NUMBER)), payload);
                ArrayNode refs = c.putArray("source_refs");
                sheet.values().forEach((field, v) -> {
                    ObjectNode ref = refs.addObject();
                    ref.put("field", field);
                    ref.put("sheet", sheet.layout());
                    ref.put("quote", v.tag() == null ? v.text() : v.tag() + ": " + v.text());
                });
                out.add(c);
            }
            return out;
        }
        if (doc.titleBlocks() == null) {
            return out;
        }
        for (TitleBlock block : doc.titleBlocks()) {
            if (!block.isOk()) {
                continue;
            }
            if (routed != DocClass.DRAWING) {
                counts.merge("title blocks cropped on pages not classed as drawings, not read", 1, Integer::sum);
                continue;
            }
            TitleBlockFields.Reading reading = TitleBlockFields.read(block);
            if (!reading.hasNumber()) {
                counts.merge("drawing title blocks the patterns leave to the LLM", 1, Integer::sum);
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            reading.fields().forEach((field, f) -> payload.put(field, f.value()));
            ObjectNode c = candidate(doc, "DRAWING", String.valueOf(payload.get(DrawingFields.NUMBER)), payload);
            ArrayNode refs = c.putArray("source_refs");
            reading.fields().forEach((field, f) -> {
                ObjectNode ref = refs.addObject();
                ref.put("field", field);
                ref.put("page", block.page());
                ref.set("box", json.valueToTree(f.box()));
                ref.put("quote", f.quote());
            });
            out.add(c);
        }
        return out;
    }

    private ExtractContext context(Parsed doc, DocClass routed, List<TableRow> tables) {
        return new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(), doc.fileName(),
                doc.type(), doc.sha256(), null, routed.name(), null, null, doc.pages(), doc.meta(), Map.of(), tables,
                doc.titleBlocks(), doc.dxf());
    }

    private ObjectNode fromProposed(Parsed doc, ProposedCandidate p) {
        ObjectNode c = candidate(doc, p.kind().name(), p.naturalKey(), p.payload());
        ArrayNode refs = c.putArray("source_refs");
        for (ProposedCandidate.ProposedSource s : p.sources()) {
            ObjectNode ref = refs.addObject();
            ref.put("field", s.field());
            ref.put("page", s.page());
            ref.set("box", json.valueToTree(s.box()));
            ref.put("sheet", s.sheet());
            ref.put("row", s.rowNumber());
            ref.put("cell", s.cell());
            ref.put("quote", s.quote());
        }
        return c;
    }

    private ObjectNode candidate(Parsed doc, String kind, String naturalKey, Map<String, Object> payload) {
        ObjectNode c = json.createObjectNode();
        c.put("document_id", doc.id());
        c.put("kind", kind);
        c.put("natural_key", naturalKey);
        c.set("payload", json.valueToTree(payload));
        c.put("method", "DETERMINISTIC");
        return c;
    }

    /** The text of the pages in a range such as {@code 98-99}, or of every page. */
    private static String text(Parsed doc, String range) {
        int first = 1;
        int last = Integer.MAX_VALUE;
        if (!range.isBlank()) {
            String[] r = range.split("-");
            first = Integer.parseInt(r[0].strip());
            last = Integer.parseInt(r[r.length - 1].strip());
        }
        StringBuilder sb = new StringBuilder();
        for (PageText page : doc.pages()) {
            if (page.page() >= first && page.page() <= last) {
                page.lines().forEach(l -> sb.append(l.text()).append('\n'));
            }
        }
        return sb.toString();
    }

    private List<Parsed> load(Path corpus) throws IOException {
        List<Parsed> out = new ArrayList<>();
        for (String line : Files.readAllLines(corpus.resolve("manifest.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode doc = json.readTree(line);
            String id = doc.path("document_id").asText();
            Path parsed = corpus.resolve("parsed").resolve(id);
            if (doc.path("use_restricted").asBoolean(false) || !Files.isRegularFile(parsed.resolve("meta.json"))) {
                counts.merge("manifest documents with no parsed output (or restricted), skipped", 1, Integer::sum);
                continue;
            }
            WorkbookMeta meta = json.readValue(parsed.resolve("meta.json").toFile(), WorkbookMeta.class);
            String sha = doc.path("sha256").asText();
            if (!sha.equalsIgnoreCase(meta.sourceSha256())) {
                // The outputs are of other bytes than the manifest names: never score them as this document.
                counts.merge("parsed outputs whose source sha256 differs from the manifest, skipped", 1, Integer::sum);
                continue;
            }
            JsonNode version = json.readTree(parsed.resolve("meta.json").toFile()).path("workerVersion");
            counts.merge("documents parsed by worker " + version.asText("unknown"), 1, Integer::sum);
            out.add(new Parsed(id, fileName(doc),
                    DocumentFileType.valueOf(doc.path("file_type").asText().toUpperCase(Locale.ROOT)),
                    doc.path("surface").asText(), DocClass.parse(doc.path("document_class").asText()).orElse(null),
                    sha, meta,
                    read(parsed.resolve("pages.jsonl"), b -> ParsedOutputReader.parsePages(b, json), List.of()),
                    read(parsed.resolve("tables.jsonl"), b -> ParsedOutputReader.parseTables(b, json), List.of()),
                    read(parsed.resolve("titleblock.jsonl"), b -> ParsedOutputReader.parseTitleBlocks(b, json), null),
                    read(parsed.resolve("dxf.json"), b -> ParsedOutputReader.parseDxf(b, json), null)));
        }
        return out;
    }

    private interface Reader<T> {
        T read(byte[] bytes) throws IOException;
    }

    private static <T> T read(Path file, Reader<T> reader, T otherwise) throws IOException {
        return Files.isRegularFile(file) ? reader.read(Files.readAllBytes(file)) : otherwise;
    }

    /** The name the file had where it was published, as a person uploading it would keep it. */
    private static String fileName(JsonNode doc) {
        String url = doc.path("source_url").asText();
        int cut = url.length();
        for (char c : new char[] {'?', '#'}) {
            int i = url.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        String path = url.substring(0, cut);
        return URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
    }
}
