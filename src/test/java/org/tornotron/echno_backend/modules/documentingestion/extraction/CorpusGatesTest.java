package org.tornotron.echno_backend.modules.documentingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CorpusGates.Pairing;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CorpusGates.Tally;

/** The comparison rules the corpus harness shares with the scorer, on hand-made cases. */
class CorpusGatesTest {

    final ObjectMapper json = new ObjectMapper();

    private JsonNode node(String text) throws Exception {
        return json.readTree(text.replace('\'', '"'));
    }

    @Test
    void numbersCompareAsExactDecimalsAndTextFolds() {
        assertThat(CorpusGates.equal(TextNode.valueOf("1,23,456.00"), TextNode.valueOf("Rs.1,23,456/-"), null)).isTrue();
        assertThat(CorpusGates.equal(TextNode.valueOf("123456"), json.getNodeFactory().numberNode(123456), null)).isTrue();
        assertThat(CorpusGates.equal(TextNode.valueOf("1,23,456.00"), TextNode.valueOf("1,23,465.00"), null)).isFalse();
        assertThat(CorpusGates.equal(TextNode.valueOf("1.25%"), TextNode.valueOf("125%"), null)).isFalse();
        assertThat(CorpusGates.equal(TextNode.valueOf("Plinth (beam) level"), TextNode.valueOf("plinth beam-level"), null))
                .isTrue();
        assertThat(CorpusGates.number(TextNode.valueOf("1.23.456"))).isNull();
        assertThat(CorpusGates.number(TextNode.valueOf("12 cum"))).isNull();
    }

    @Test
    void anAcceptedSpellingCounts() throws Exception {
        assertThat(CorpusGates.equal(TextNode.valueOf("30 days"), TextNode.valueOf("30 days from start"),
                node("['30 days from start']"))).isTrue();
    }

    @Test
    void aRepeatedCodeNeedsTwoPredictionsAndPairsOnTheSameRowFirst() throws Exception {
        List<JsonNode> labels = List.of(
                node("{'kind':'BOQ_ITEM','natural_key':'4.1','payload':{'quantity':'10','rate':'5','amount':'50'},"
                        + "'source_refs':[{'sheet':'Civil','row':12}]}"),
                node("{'kind':'BOQ_ITEM','natural_key':'4.1','payload':{'quantity':'2','rate':'7','amount':'14'},"
                        + "'source_refs':[{'sheet':'Civil','row':40}]}"));
        List<JsonNode> one = List.of(node("{'kind':'BOQ_ITEM','natural_key':'4.1','payload':{'quantity':'2','rate':'7',"
                + "'amount':'14'},'source_refs':[{'sheet':'Civil','row':40}]}"));
        Pairing p = CorpusGates.pair(labels, one);
        assertThat(p.captured()).isEqualTo(new Tally(1, 2));
        assertThat(CorpusGates.boqNumbers(p)).isEqualTo(new Tally(3, 3));
    }

    @Test
    void aNumberWhereTheSheetIsBlankIsWrong() throws Exception {
        List<JsonNode> labels = List.of(node("{'kind':'BOQ_ITEM','payload':{'code':'7','quantity':'1','rate':null,"
                + "'amount':null}}"));
        List<JsonNode> predicted = List.of(node("{'kind':'BOQ_ITEM','natural_key':'7','payload':{'quantity':'1.000',"
                + "'rate':'0','amount':null}}"));
        assertThat(CorpusGates.boqNumbers(CorpusGates.pair(labels, predicted))).isEqualTo(new Tally(1, 2));
    }

    @Test
    void aPredictionOutsideTheLabelledPagesIsIgnored() throws Exception {
        JsonNode coverage = node("{'kind':'MILESTONE','pages':'16-17'}");
        assertThat(CorpusGates.inScope(node("{'source_refs':[{'page':30}]}"), coverage)).isFalse();
        assertThat(CorpusGates.inScope(node("{'source_refs':[{'page':30},{'page':17}]}"), coverage)).isTrue();
        assertThat(CorpusGates.inScope(node("{'source_refs':[{'sheet':'Schedule','row':3}]}"), coverage)).isTrue();
    }

    @Test
    void gatesAreExactAndNothingCountedIsNotAPass() {
        assertThat(new Tally(199, 200).meets("0.995")).isTrue();
        assertThat(new Tally(198, 200).meets("0.995")).isFalse();
        assertThat(Tally.NONE.meets("0")).isFalse();
    }
}
