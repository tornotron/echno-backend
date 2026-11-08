package org.tornotron.echno_backend.common.identity;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Optional extra: a provider that lets Echno send the sign-in email it would otherwise send itself.
 * Only the WorkOS adapter has it. Reach it through {@link IdentityProvider#capabilities()}.
 *
 * <p>The provider reports each email it wants sent as a {@link Request}, which carries nothing secret.
 * The link or code goes into the email and nowhere else: {@link #link} reads it from the provider at
 * the moment of sending, so Echno never stores it.
 */
public interface SignInEmails {

    enum Kind { INVITATION, PASSWORD_RESET, EMAIL_VERIFICATION }

    /**
     * One email the provider wants sent.
     *
     * @param id        the provider's id for the request, unique, so a request seen twice is sent once.
     * @param kind      which email.
     * @param objectId  the provider's id for the invitation, reset or verification it is about.
     * @param createdAt when the provider made the request.
     */
    record Request(String id, Kind kind, String objectId, Instant createdAt) {
    }

    /**
     * Requests in the order they were made.
     *
     * @param requests the requests in this page.
     * @param cursor   where the next page starts, or null when this page was empty.
     */
    record Page(List<Request> requests, @Nullable String cursor) {
    }

    /**
     * What goes into one email. {@link #toString()} leaves the secret out.
     *
     * @param email          the address the provider holds for it, which is where the email must go.
     * @param secret         the accept or reset link, or the verification code.
     * @param expiresAt      when the secret stops working.
     * @param open           false when it can no longer be used for another reason (an invitation that is
     *                       no longer pending).
     * @param organizationId the provider's organization, for an invitation.
     * @param inviter        the provider's id for whoever sent an invitation, if anyone did.
     */
    record Link(String email, String secret, @Nullable Instant expiresAt, boolean open,
                @Nullable String organizationId, @Nullable ExternalUserId inviter) {

        @Override
        public String toString() {
            return "Link[email=" + email + ", expiresAt=" + expiresAt + ", open=" + open + "]";
        }
    }

    /**
     * The requests made after {@code cursor}, or, with no cursor, those made since {@code since}.
     *
     * @param limit the most to return.
     */
    Page requestsAfter(@Nullable String cursor, Instant since, int limit);

    /** What to send for one request, or empty when the provider no longer has it. */
    Optional<Link> link(Kind kind, String objectId);
}
