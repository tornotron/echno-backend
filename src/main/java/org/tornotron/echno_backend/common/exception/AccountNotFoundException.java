package org.tornotron.echno_backend.common.exception;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID id) {
        super("Account not found: "+ id);
    }
    public AccountNotFoundException(String code) { super("Account not found: "+ code); }
}
