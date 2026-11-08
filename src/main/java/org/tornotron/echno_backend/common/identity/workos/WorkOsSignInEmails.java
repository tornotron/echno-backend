package org.tornotron.echno_backend.common.identity.workos;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.SignInEmails;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sign-in email on WorkOS, for an environment whose own invitation, password reset and email
 * verification emails are switched off (dashboard, Emails, Configuration).
 *
 * <p>WorkOS documents this path in "Custom emails": each email is announced by an event
 * ({@code invitation.created}, {@code password_reset.created}, {@code email_verification.created}),
 * the event leaves the secret out, and the Get Invitation, Get Password Reset and Get Email
 * Verification calls return it. The events come from the Events API, read oldest first with an
 * {@code after} cursor, so no inbound webhook is needed.
 *
 * <p>{@code invitation.resent} is read as well, so a resend from Echno, which calls WorkOS's resend,
 * sends the same link again. Magic Auth is not handled: it stays switched off as a sign-in method.
 */
class WorkOsSignInEmails implements SignInEmails {

    static final Map<String, Kind> EVENTS = Map.of(
            "invitation.created", Kind.INVITATION,
            "invitation.resent", Kind.INVITATION,
            "password_reset.created", Kind.PASSWORD_RESET,
            "email_verification.created", Kind.EMAIL_VERIFICATION);

    private static final List<String> TYPES = EVENTS.keySet().stream().sorted().toList();

    private final WorkOsApi api;

    WorkOsSignInEmails(WorkOsApi api) {
        this.api = api;
    }

    @Override
    public Page requestsAfter(@Nullable String cursor, Instant since, int limit) {
        WorkOsApi.Page<WorkOsApi.Event> page = api.events(TYPES, cursor, since, limit);
        if (page == null || page.data() == null || page.data().isEmpty()) {
            return new Page(List.of(), cursor);
        }
        List<Request> requests = page.data().stream()
                .filter(event -> EVENTS.containsKey(event.event()) && objectId(event.data()) != null)
                .map(event -> new Request(event.id(), EVENTS.get(event.event()), objectId(event.data()),
                        event.createdAt() == null ? Instant.EPOCH : event.createdAt()))
                .toList();
        // The cursor is the last event read, kept or not, so a type Echno ignores is not read again.
        return new Page(requests, page.data().get(page.data().size() - 1).id());
    }

    @Override
    public Optional<Link> link(Kind kind, String objectId) {
        return switch (kind) {
            case INVITATION -> api.invitationLink(objectId)
                    .filter(found -> found.acceptInvitationUrl() != null)
                    .map(found -> new Link(found.email(), found.acceptInvitationUrl(), found.expiresAt(),
                            "pending".equals(found.state()), found.organizationId(),
                            found.inviterUserId() == null ? null : new ExternalUserId(found.inviterUserId())));
            case PASSWORD_RESET -> api.passwordReset(objectId)
                    .filter(found -> found.passwordResetUrl() != null)
                    .map(found -> new Link(found.email(), found.passwordResetUrl(), found.expiresAt(), true,
                            null, null));
            case EMAIL_VERIFICATION -> api.emailVerification(objectId)
                    .filter(found -> found.code() != null)
                    .map(found -> new Link(found.email(), found.code(), found.expiresAt(), true, null, null));
        };
    }

    @Nullable
    private static String objectId(@Nullable JsonNode data) {
        return data == null || !data.hasNonNull("id") ? null : data.get("id").asText();
    }
}
