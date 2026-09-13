package org.tornotron.echno_backend.billing.gateway.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.NormalizedMandateStatus;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Turns Razorpay's webhook envelope and entities into the port's vocabulary. Everything
 * Razorpay-specific about the payload shape lives here: the {@code event} name, the
 * {@code payload.<entity>.entity} nesting, epoch-second timestamps, and the {@code notes}
 * map the adapter stamps the organization id and plan code into.
 *
 * <p>Razorpay carries the event id in the {@code X-Razorpay-Event-Id} header, not the body, so
 * the id set here is a SHA-256 digest of the body: deterministic, so a redelivery of the same
 * body dedupes even where the header is missing. The inbox prefers the header when present.
 */
public class RazorpayEventParser {

    /** The notes key the adapter writes the organization id under on customers, plans and subscriptions. */
    public static final String NOTE_ORGANIZATION_ID = "organization_id";
    /** The notes key the adapter writes the internal plan code under. */
    public static final String NOTE_PLAN_CODE = "plan_code";

    private static final Map<String, NormalizedEventType> EVENT_TYPES = Map.ofEntries(
            Map.entry("subscription.authenticated", NormalizedEventType.SUBSCRIPTION_AUTHENTICATED),
            Map.entry("subscription.activated", NormalizedEventType.SUBSCRIPTION_ACTIVATED),
            Map.entry("subscription.charged", NormalizedEventType.SUBSCRIPTION_CHARGED),
            Map.entry("subscription.pending", NormalizedEventType.SUBSCRIPTION_PENDING),
            Map.entry("subscription.halted", NormalizedEventType.SUBSCRIPTION_HALTED),
            Map.entry("subscription.paused", NormalizedEventType.SUBSCRIPTION_PAUSED),
            Map.entry("subscription.resumed", NormalizedEventType.SUBSCRIPTION_RESUMED),
            Map.entry("subscription.cancelled", NormalizedEventType.SUBSCRIPTION_CANCELLED),
            Map.entry("subscription.completed", NormalizedEventType.SUBSCRIPTION_COMPLETED),
            Map.entry("token.confirmed", NormalizedEventType.MANDATE_AUTHORIZED),
            Map.entry("token.rejected", NormalizedEventType.MANDATE_REVOKED),
            Map.entry("token.cancelled", NormalizedEventType.MANDATE_REVOKED),
            Map.entry("payment.failed", NormalizedEventType.PAYMENT_FAILED),
            Map.entry("invoice.paid", NormalizedEventType.INVOICE_PAID));

    private static final Map<String, NormalizedSubscriptionStatus> SUBSCRIPTION_STATUSES = Map.of(
            "created", NormalizedSubscriptionStatus.CREATED,
            "authenticated", NormalizedSubscriptionStatus.AUTHENTICATED,
            "active", NormalizedSubscriptionStatus.ACTIVE,
            "pending", NormalizedSubscriptionStatus.PAYMENT_FAILED_RETRYING,
            "halted", NormalizedSubscriptionStatus.HALTED,
            "cancelled", NormalizedSubscriptionStatus.CANCELLED,
            "completed", NormalizedSubscriptionStatus.COMPLETED,
            "expired", NormalizedSubscriptionStatus.EXPIRED_BEFORE_AUTH,
            "paused", NormalizedSubscriptionStatus.PAUSED);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<NormalizedBillingEvent> parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            throw new BillingGatewayException("Razorpay webhook body is not JSON", e);
        }
        if (root == null || !root.isObject() || !"event".equals(text(root, "entity"))) {
            throw new BillingGatewayException("Razorpay webhook body is not an event envelope");
        }
        String eventName = text(root, "event");
        NormalizedEventType type = EVENT_TYPES.getOrDefault(eventName, NormalizedEventType.IGNORED);
        JsonNode payload = root.path("payload");
        JsonNode subscription = payload.path("subscription").path("entity");
        JsonNode token = payload.path("token").path("entity");
        JsonNode payment = payload.path("payment").path("entity");
        JsonNode invoice = payload.path("invoice").path("entity");

        GatewaySubscription snapshot = subscription.isObject() ? toSubscription(subscription) : null;
        JsonNode notes = firstNotes(subscription, payment, invoice, token);
        String providerSubscriptionId = firstText(
                text(subscription, "id"), text(invoice, "subscription_id"), text(payment, "subscription_id"));
        String providerCustomerId = firstText(
                text(subscription, "customer_id"), text(token, "customer_id"),
                text(payment, "customer_id"), text(invoice, "customer_id"));

        NormalizedMandateStatus mandateStatus = null;
        MandateMethod mandateMethod = null;
        Long mandateMax = null;
        String mandateRef = null;
        if (token.isObject()) {
            mandateRef = text(token, "id");
            mandateMethod = toMandateMethod(text(token, "method"));
            mandateStatus = switch (eventName) {
                case "token.confirmed" -> NormalizedMandateStatus.AUTHORIZED;
                case "token.rejected" -> NormalizedMandateStatus.EXPIRED;
                case "token.cancelled" -> NormalizedMandateStatus.REVOKED;
                case "token.paused" -> NormalizedMandateStatus.PAUSED;
                default -> null;
            };
            JsonNode max = token.path("max_amount");
            mandateMax = max.isNumber() ? max.asLong() : null;
        }

        NormalizedBillingEvent event = new NormalizedBillingEvent(
                ProviderId.RAZORPAY,
                digest(rawBody),
                type,
                epoch(root.path("created_at")),
                parseLong(text(notes, NOTE_ORGANIZATION_ID)),
                providerSubscriptionId,
                providerCustomerId,
                text(subscription, "plan_id"),
                text(notes, NOTE_PLAN_CODE),
                snapshot,
                mandateRef,
                mandateMethod,
                mandateStatus,
                mandateMax);
        return List.of(event);
    }

    /** A Razorpay subscription entity (from a webhook or a fetch) as the port reports it. */
    public GatewaySubscription toSubscription(JsonNode entity) {
        return new GatewaySubscription(
                text(entity, "id"),
                text(entity, "plan_id"),
                text(entity, "customer_id"),
                toStatus(text(entity, "status")),
                epoch(entity.path("current_start")),
                epoch(entity.path("current_end")),
                epoch(entity.path("charge_at")),
                text(entity, "token_id"),
                text(entity, "short_url"));
    }

    public static NormalizedSubscriptionStatus toStatus(String razorpayStatus) {
        if (razorpayStatus == null) {
            return NormalizedSubscriptionStatus.CREATED;
        }
        NormalizedSubscriptionStatus status = SUBSCRIPTION_STATUSES.get(razorpayStatus.toLowerCase());
        if (status == null) {
            throw new BillingGatewayException("Unknown Razorpay subscription status '" + razorpayStatus + "'");
        }
        return status;
    }

    static MandateMethod toMandateMethod(String method) {
        if (method == null) {
            return MandateMethod.UNKNOWN;
        }
        return switch (method.toLowerCase()) {
            case "upi" -> MandateMethod.UPI_AUTOPAY;
            case "emandate", "nach" -> MandateMethod.ENACH;
            case "card" -> MandateMethod.CARD;
            default -> MandateMethod.UNKNOWN;
        };
    }

    static String digest(byte[] rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private static JsonNode firstNotes(JsonNode... entities) {
        for (JsonNode entity : entities) {
            JsonNode notes = entity.path("notes");
            if (notes.isObject() && notes.size() > 0) {
                return notes;
            }
        }
        return entities[0].path("notes");
    }

    private static Instant epoch(JsonNode node) {
        return node != null && node.isNumber() && node.asLong() > 0 ? Instant.ofEpochSecond(node.asLong()) : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
