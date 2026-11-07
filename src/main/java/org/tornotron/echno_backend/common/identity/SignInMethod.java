package org.tornotron.echno_backend.common.identity;

/**
 * How a person signed in, as far as the token says. {@link #UNKNOWN} covers a missing or unreadable
 * value and counts as the weakest, so a rule that requires MFA fails closed.
 */
public enum SignInMethod {
    UNKNOWN,
    PASSWORD,
    SINGLE_SIGN_ON,
    MFA
}
