package org.tornotron.echno_backend.finance.ledger;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Drives automatic chart-of-accounts code generation. Tunable per environment
 * via {@code finance.account-numbering.*} without code changes.
 *
 * <p>{@code levelSteps} is the increment between sibling codes at each depth,
 * indexed by depth (0 = roots). With {@code [1000, 100, 10, 1]} the tree grows
 * 1000 → 1100 → 1110 → 1111. Depths beyond the list reuse the last step.
 *
 * <p>{@code rootBlocks} is the starting code for the first root account of each
 * {@link AccountType} (e.g. ASSET → 1000, LIABILITY → 2000).
 */
@Data
@Component
@ConfigurationProperties(prefix = "finance.account-numbering")
public class AccountNumberingProperties {

    private List<Integer> levelSteps = List.of(1000, 100, 10, 1);

    private Map<AccountType, Long> rootBlocks = new EnumMap<>(AccountType.class);

    /** Step between siblings for a node at the given depth (0 = root). */
    public long stepForDepth(int depth) {
        if (levelSteps.isEmpty()) {
            throw new IllegalStateException("finance.account-numbering.level-steps must not be empty");
        }
        int index = Math.min(depth, levelSteps.size() - 1);
        return levelSteps.get(index);
    }

    /** Starting code for the first root of the given type. */
    public long rootBlock(AccountType type) {
        Long base = rootBlocks.get(type);
        if (base == null) {
            throw new IllegalStateException(
                    "No finance.account-numbering.root-blocks entry for type " + type);
        }
        return base;
    }
}
