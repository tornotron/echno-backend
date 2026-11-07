package org.tornotron.echno_backend.common.identity;

/** What the identity provider knows about a person. */
public record UserProfile(String email, boolean emailVerified, String name) {
}
