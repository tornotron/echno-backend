package org.tornotron.echno_backend.common.exception;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID id) {
        super("Account with ID " + id + " was not found in this organization");
    }
    public AccountNotFoundException(String code) {
        super("Account with code '" + code + "' was not found in this organization");
    }
}
