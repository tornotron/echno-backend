package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate.ProposedSource;

/**
 * Turns a read BOQ line into a candidate. Numbers travel as plain decimal strings, so no amount passes
 * through a binary floating point on its way to the contract. The natural key is the sheet and row,
 * which is also how a reviewer finds the line in the workbook.
 */
public final class BoqCandidates {

    public static final String SHEET = "sheet";
    public static final String SHEET_INDEX = "sheetIndex";
    public static final String ROW = "row";
    public static final String SECTION = "section";

    private BoqCandidates() {
    }

    public static String naturalKey(String sheet, int row) {
        return sheet + "!" + row;
    }

    public static ProposedCandidate of(BoqSheetExtractor.Line line, UUID packageId, UUID documentId, UUID segmentId,
                                       String documentSha256, String sheet, int sheetIndex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(BoqField.ITEM_CODE.key(), line.itemCode());
        payload.put(BoqField.DESCRIPTION.key(), line.description());
        payload.put(BoqField.UNIT.key(), line.unit());
        payload.put(BoqField.QUANTITY.key(), plain(line.quantity()));
        payload.put(BoqField.RATE.key(), plain(line.rate()));
        payload.put(BoqField.AMOUNT.key(), plain(line.amount()));
        payload.put(SECTION, line.section());
        payload.put(SHEET, sheet);
        payload.put(SHEET_INDEX, sheetIndex);
        payload.put(ROW, line.row());
        List<ProposedSource> sources = new ArrayList<>();
        line.sources().forEach((field, cell) -> sources.add(new ProposedSource(field.key(), documentId, documentSha256,
                null, null, sheet, line.row(), cell.ref(), null, cell.text(), true)));
        return new ProposedCandidate(packageId, documentId, segmentId, CandidateKind.BOQ_ITEM,
                naturalKey(sheet, line.row()), payload, line.flags(), ExtractionMethod.DETERMINISTIC, sources, null);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
