package org.tornotron.echno_backend.finance.ledger;

public enum AccountType {
    ASSET(true),
    LIABILITY(false),
    EQUITY(false),
    INCOME(false),
    EXPENSE(true);

    private final boolean debitNormal;

    AccountType(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    public boolean isDebitNormal() {
        return debitNormal;
    }

    public int normalSign() {
        return debitNormal ? 1 : -1;
    }
}
