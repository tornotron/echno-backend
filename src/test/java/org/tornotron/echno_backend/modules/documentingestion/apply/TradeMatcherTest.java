package org.tornotron.echno_backend.modules.documentingestion.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort.TradeRef;

class TradeMatcherTest {

    static TradeRef trade(String code, String name) {
        return new TradeRef(UUID.randomUUID(), code, name, code, true);
    }

    final List<TradeRef> trades = List.of(trade("rcc", "RCC"), trade("reinforcement", "Reinforcement"),
            trade("masonry", "Masonry"), trade("plastering", "Plastering"), trade("doors-windows", "Doors and windows"),
            trade("shuttering-formwork", "Shuttering and formwork"));

    @Test
    void anActivityNamingOneTradeMatchesIt() {
        assertThat(code(TradeMatcher.match("Brick masonry", null, trades))).isEqualTo("masonry");
        assertThat(code(TradeMatcher.match("Steel reinforcement", null, trades))).isEqualTo("reinforcement");
        assertThat(code(TradeMatcher.match("Plastering of walls", null, trades))).isEqualTo("plastering");
        assertThat(code(TradeMatcher.match("Doors and windows", null, trades))).isEqualTo("doors-windows");
    }

    @Test
    void theWordsTendersUseForACatalogueTradeCount() {
        assertThat(code(TradeMatcher.match("Concreting", null, trades))).isEqualTo("rcc");
        assertThat(code(TradeMatcher.match("Reinforced cement concrete", null, trades))).isEqualTo("rcc");
        assertThat(code(TradeMatcher.match("Formwork", null, trades))).isEqualTo("shuttering-formwork");
        assertThat(code(TradeMatcher.match(null, "Concrete", trades))).as("the material when there is no activity")
                .isEqualTo("rcc");
    }

    @Test
    void noMatchOrTwoMatchesLeaveTheTradeEmpty() {
        assertThat(TradeMatcher.match("Cement", null, trades)).isEmpty();
        assertThat(TradeMatcher.match("Concrete and plastering", null, trades)).as("two trades").isEmpty();
        assertThat(TradeMatcher.match("Masonry", null, List.of())).isEmpty();
        assertThat(TradeMatcher.match(null, null, trades)).isEmpty();
    }

    static String code(Optional<TradeRef> t) {
        return t.map(TradeRef::code).orElse(null);
    }
}
