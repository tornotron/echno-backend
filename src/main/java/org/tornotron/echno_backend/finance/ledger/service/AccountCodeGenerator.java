package org.tornotron.echno_backend.finance.ledger.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.finance.ledger.AccountNumberingProperties;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;

import java.util.List;

/**
 * Derives the next chart-of-accounts code from the parent and existing siblings,
 * so data entry only requires a parent and a name. Widths are configured via
 * {@link AccountNumberingProperties}.
 */
@Component
@RequiredArgsConstructor
public class AccountCodeGenerator {

    private final AccountNumberingProperties props;

    /**
     * Next free code under {@code parent} (or the next root code when {@code parent}
     * is {@code null}). Siblings are supplied by the caller to keep this side-effect free.
     */
    public String nextCode(Account parent, AccountType type, List<Account> siblings) {
        long step = props.stepForDepth(parent == null ? 0 : depthOf(parent) + 1);

        // First code in an empty group: roots start at the type's block; a first
        // child starts one step past its parent (e.g. 1100 -> 1110).
        long firstCode = parent == null
                ? props.rootBlock(type)
                : parseCode(parent.getCode()) + step;

        long next = siblings.stream()
                .mapToLong(s -> parseCode(s.getCode()))
                .max()
                .stream()
                .map(maxSibling -> maxSibling + step)
                .findFirst()
                .orElse(firstCode);

        return String.valueOf(next);
    }

    private int depthOf(Account account) {
        int depth = 0;
        for (Account p = account.getParent(); p != null; p = p.getParent()) {
            depth++;
        }
        return depth;
    }

    private long parseCode(String code) {
        try {
            return Long.parseLong(code);
        } catch (NumberFormatException e) {
            throw new InvalidJournalException(
                    "Cannot auto-generate a code next to non-numeric code '" + code
                            + "'; supply the code explicitly");
        }
    }
}
