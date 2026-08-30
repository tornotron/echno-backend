package org.tornotron.echno_backend.user;

/**
 * The parts of a user row a document needs in order to say who did something.
 *
 * <p>A projection rather than the entity: resolving a page of approval stamps wants three columns
 * and nothing else, and loading {@link User} would drag its attachments and employments behind it
 * for a string.
 *
 * @param id The user's id, the value a document's {@code *By} column holds.
 * @param name The user's name.
 * @param email The user's email, used when the name is missing.
 */
public record UserDisplayName(Long id, String name, String email) {

    /**
     * The best name this row can offer.
     *
     * <p>{@code name} is non-null in the schema but not guaranteed non-empty, and email is the
     * only other thing every account has, so it is what a nameless row falls back to rather than
     * an empty string that would render as a gap on the screen.
     *
     * @return The name, or the email when the name is blank, or null when the row has neither.
     */
    public String bestEffortName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return null;
    }
}
