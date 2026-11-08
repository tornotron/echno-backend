package org.tornotron.echno_backend.common.identity.workos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the WorkOS endpoints the adapter calls, holding state in memory. Every
 * response is a copy of a response recorded from a WorkOS staging environment (under
 * {@code src/test/resources/workos}) with its fields set, and every refusal is the recorded body for
 * that case, so the adapter is read against WorkOS's real shapes. The recorded bearer values are
 * {@link #BEARER_SENTINEL}, so a test can show they never reach a log.
 *
 * <p>Two shapes were not recorded and follow the API reference's examples instead: an email
 * verification ({@code email_verification.json}) and an event, which is the object it is about with
 * the secret fields removed, as the events reference says, wrapped as {@code object}, {@code id},
 * {@code event}, {@code data}, {@code created_at}.
 */
final class WorkOsStub implements AutoCloseable {

    static final String API_KEY = "sk_test_stub_key";
    static final String BEARER_SENTINEL = "SENTINEL-BEARER-VALUE";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[^/]+");

    private final HttpServer server;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, ObjectNode> organizations = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> users = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> memberships = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> invitations = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> sessions = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> passwordResets = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> emailVerifications = new ConcurrentHashMap<>();
    private final List<ObjectNode> events = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicInteger> invitationEmails = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> passwordResetEmails = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger rateLimitedResponses = new AtomicInteger();
    private volatile String retryAfter = "0";

    WorkOsStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    WorkOsSettings settings() {
        return new WorkOsSettings(API_KEY, "client_stub", url());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // --- What a test sets up or reads on the WorkOS side ------------------------------------------

    String addUser(String email, boolean verified, String first, String last) {
        ObjectNode user = template("user.json");
        String id = id("user");
        user.put("id", id).put("email", email).put("email_verified", verified)
                .put("first_name", first).put("last_name", last);
        users.put(id, user);
        return id;
    }

    Optional<String> userByEmail(String email) {
        return users.values().stream().filter(u -> u.get("email").asText().equalsIgnoreCase(email))
                .map(u -> u.get("id").asText()).findFirst();
    }

    boolean isActiveMember(String userId, String externalOrgId) {
        return organizationByExternalId(externalOrgId)
                .map(org -> memberships.values().stream().anyMatch(m -> m.get("user_id").asText().equals(userId)
                        && m.get("organization_id").asText().equals(org.get("id").asText())
                        && m.get("status").asText().equals("active")))
                .orElse(false);
    }

    long membershipsWithStatus(String status) {
        return memberships.values().stream().filter(m -> m.get("status").asText().equals(status)).count();
    }

    /** What WorkOS does when the invited person finishes: verifies the address, activates the membership. */
    String accept(String invitationId) {
        ObjectNode invitation = invitations.get(invitationId);
        String userId = userByEmail(invitation.get("email").asText()).orElseThrow();
        users.get(userId).put("email_verified", true);
        invitation.put("state", "accepted").put("accepted_user_id", userId).put("accepted_at", Instant.now().toString());
        memberships.values().stream()
                .filter(m -> m.get("user_id").asText().equals(userId)
                        && m.get("organization_id").asText().equals(invitation.get("organization_id").asText()))
                .forEach(m -> m.put("status", "active"));
        return userId;
    }

    void expire(String invitationId) {
        invitations.get(invitationId).put("expires_at", Instant.now().minus(1, ChronoUnit.MINUTES).toString());
    }

    int invitationEmailsTo(String email) {
        return invitationEmails.getOrDefault(email.toLowerCase(), new AtomicInteger()).get();
    }

    int passwordResetEmailsTo(String email) {
        return passwordResetEmails.getOrDefault(email.toLowerCase(), new AtomicInteger()).get();
    }

    void startSession(String userId) {
        ObjectNode list = template("sessions.json");
        ObjectNode session = ((ObjectNode) list.get("data").get(0)).deepCopy();
        String id = id("session");
        session.put("id", id).put("user_id", userId).put("status", "active");
        sessions.put(id, session);
    }

    boolean hasActiveSession(String userId) {
        return sessions.values().stream()
                .anyMatch(s -> s.get("user_id").asText().equals(userId) && s.get("status").asText().equals("active"));
    }

    /** What WorkOS does when a person signing up has to confirm their address: a code, and its event. */
    String addEmailVerification(String userId) {
        ObjectNode verification = template("email_verification.json");
        String id = id("email_verification");
        verification.put("id", id).put("user_id", userId).put("email", users.get(userId).get("email").asText())
                .put("expires_at", Instant.now().plus(10, ChronoUnit.MINUTES).toString());
        emailVerifications.put(id, verification);
        event("email_verification.created", verification);
        return id;
    }

    /** An event of a type the caller did not ask about, to show it is passed over. */
    void addUnrelatedEvent() {
        event("user.created", template("user.json").put("id", id("user")));
    }

    /** Events in the order WorkOS made them. */
    List<ObjectNode> events() {
        return List.copyOf(events);
    }

    /** The next {@code count} requests answer 429 with this {@code Retry-After}. */
    void rateLimitNext(int count, String retryAfterValue) {
        rateLimitedResponses.set(count);
        retryAfter = retryAfterValue;
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    // --- The API ---------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            Map<String, List<String>> query = query(exchange.getRequestURI().getRawQuery());
            requests.add(method + " " + path);
            if (!("Bearer " + API_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, JSON.createObjectNode().put("message", "Unauthorized"));
                return;
            }
            if (rateLimitedResponses.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
                send(exchange, 429, JSON.createObjectNode().put("message", "Too many requests"));
                return;
            }
            JsonNode body = body(exchange.getRequestBody());
            route(exchange, method, path, query, body);
        }
    }

    private void route(HttpExchange exchange, String method, String path, Map<String, List<String>> query,
                       JsonNode body) throws IOException {
        Matcher m;
        if (method.equals("POST") && path.equals("/organizations")) {
            String external = body.get("external_id").asText();
            if (organizationByExternalId(external).isPresent()) {
                send(exchange, 400, template("error_external_id_used.json"));
                return;
            }
            ObjectNode org = template("organization.json");
            org.put("id", id("org")).put("name", body.get("name").asText()).put("external_id", external);
            organizations.put(org.get("id").asText(), org);
            send(exchange, 201, org);
        } else if (method.equals("GET") && path.equals("/organizations")) {
            ObjectNode list = JSON.createObjectNode().put("object", "list");
            ArrayNode data = list.putArray("data");
            organizations.values().stream().limit(1).forEach(data::add);
            list.putObject("list_metadata").putNull("before").putNull("after");
            send(exchange, 200, list);
        } else if ((m = match("/organizations/external_id/(ID)", path)) != null) {
            Optional<ObjectNode> org = organizationByExternalId(decode(m.group(1)));
            if (org.isPresent()) {
                send(exchange, 200, org.get());
            } else {
                notFound(exchange);
            }
        } else if (method.equals("DELETE") && (m = match("/organizations/(ID)", path)) != null) {
            String id = m.group(1);
            organizations.remove(id);
            memberships.values().removeIf(x -> x.get("organization_id").asText().equals(id));
            invitations.values().removeIf(x -> x.get("organization_id").asText().equals(id));
            sendText(exchange, 202, "Accepted");
        } else if (method.equals("GET") && path.equals("/user_management/users")) {
            String email = query.getOrDefault("email", List.of("")).get(0);
            list(exchange, users.values().stream().filter(u -> u.get("email").asText().equalsIgnoreCase(email)).toList());
        } else if (method.equals("GET") && (m = match("/user_management/users/(ID)/sessions", path)) != null) {
            String user = m.group(1);
            list(exchange, sessions.values().stream().filter(s -> s.get("user_id").asText().equals(user)
                    && s.get("status").asText().equals("active")).toList());
        } else if (method.equals("GET") && (m = match("/user_management/users/(ID)", path)) != null) {
            ObjectNode user = users.get(m.group(1));
            if (user == null) {
                notFound(exchange);
            } else {
                send(exchange, 200, user);
            }
        } else if (method.equals("POST") && path.equals("/user_management/sessions/revoke")) {
            ObjectNode session = sessions.get(body.get("session_id").asText());
            if (session != null) {
                session.put("status", "revoked");
            }
            sendText(exchange, 200, "");
        } else if (method.equals("POST") && path.equals("/user_management/password_reset")) {
            String email = body.get("email").asText();
            Optional<String> user = userByEmail(email);
            if (user.isEmpty()) {
                notFound(exchange);
                return;
            }
            passwordResetEmails.computeIfAbsent(email.toLowerCase(), k -> new AtomicInteger()).incrementAndGet();
            ObjectNode reset = template("password_reset.json");
            reset.put("id", id("password_reset")).put("user_id", user.get()).put("email", email)
                    .put("expires_at", Instant.now().plus(15, ChronoUnit.MINUTES).toString());
            passwordResets.put(reset.get("id").asText(), reset);
            event("password_reset.created", reset);
            send(exchange, 201, reset);
        } else if (method.equals("GET") && (m = match("/user_management/password_reset/(ID)", path)) != null) {
            ObjectNode reset = passwordResets.get(m.group(1));
            if (reset == null) {
                notFound(exchange);
            } else {
                send(exchange, 200, reset);
            }
        } else if (method.equals("GET") && (m = match("/user_management/email_verification/(ID)", path)) != null) {
            ObjectNode verification = emailVerifications.get(m.group(1));
            if (verification == null) {
                notFound(exchange);
            } else {
                send(exchange, 200, verification);
            }
        } else if (method.equals("GET") && path.equals("/events")) {
            listEvents(exchange, query);
        } else if (method.equals("POST") && path.equals("/user_management/organization_memberships")) {
            createMembership(exchange, body.get("user_id").asText(), body.get("organization_id").asText());
        } else if (method.equals("GET") && path.equals("/user_management/organization_memberships")) {
            String user = query.getOrDefault("user_id", List.of("")).get(0);
            String org = query.getOrDefault("organization_id", List.of("")).get(0);
            List<String> statuses = query.getOrDefault("statuses", List.of("active"));
            list(exchange, memberships.values().stream().filter(x -> x.get("user_id").asText().equals(user)
                    && x.get("organization_id").asText().equals(org)
                    && statuses.contains(x.get("status").asText())).toList());
        } else if (method.equals("DELETE") && (m = match("/user_management/organization_memberships/(ID)", path)) != null) {
            memberships.remove(m.group(1));
            sendText(exchange, 200, "");
        } else if (method.equals("POST") && path.equals("/user_management/invitations")) {
            invite(exchange, body);
        } else if (method.equals("GET") && (m = match("/user_management/invitations/(ID)", path)) != null) {
            ObjectNode invitation = invitations.get(m.group(1));
            if (invitation == null) {
                notFound(exchange);
            } else {
                send(exchange, 200, current(invitation));
            }
        } else if (method.equals("POST") && (m = match("/user_management/invitations/(ID)/(resend|revoke)", path)) != null) {
            changeInvitation(exchange, m.group(1), m.group(2));
        } else {
            send(exchange, 404, JSON.createObjectNode().put("message", "No route " + method + " " + path));
        }
    }

    private void createMembership(HttpExchange exchange, String user, String org) throws IOException {
        if (!organizations.containsKey(org)) {
            notFound(exchange);
            return;
        }
        Optional<ObjectNode> existing = memberships.values().stream()
                .filter(x -> x.get("user_id").asText().equals(user) && x.get("organization_id").asText().equals(org))
                .findFirst();
        if (existing.isPresent()) {
            send(exchange, 201, existing.get());
            return;
        }
        ObjectNode membership = template("organization_membership.json");
        membership.put("id", id("om")).put("user_id", user).put("organization_id", org).put("status", "active");
        memberships.put(membership.get("id").asText(), membership);
        send(exchange, 201, membership);
    }

    private void invite(HttpExchange exchange, JsonNode body) throws IOException {
        String email = body.get("email").asText();
        String org = body.get("organization_id").asText();
        if (!organizations.containsKey(org)) {
            notFound(exchange);
            return;
        }
        boolean pendingAlready = invitations.values().stream().map(this::current)
                .anyMatch(x -> x.get("email").asText().equalsIgnoreCase(email)
                        && x.get("organization_id").asText().equals(org) && x.get("state").asText().equals("pending"));
        if (pendingAlready) {
            send(exchange, 400, template("error_already_invited.json"));
            return;
        }
        String user = userByEmail(email).orElseGet(() -> addUser(email, false, null, null));
        ObjectNode membership = template("organization_membership.json");
        membership.put("id", id("om")).put("user_id", user).put("organization_id", org).put("status", "pending");
        memberships.put(membership.get("id").asText(), membership);
        ObjectNode invitation = template("invitation_pending.json");
        invitation.put("id", id("invitation")).put("email", email).put("organization_id", org)
                .put("expires_at", Instant.now().plus(body.get("expires_in_days").asInt(7), ChronoUnit.DAYS).toString());
        if (body.hasNonNull("inviter_user_id")) {
            invitation.put("inviter_user_id", body.get("inviter_user_id").asText());
        } else {
            invitation.putNull("inviter_user_id");
        }
        invitations.put(invitation.get("id").asText(), invitation);
        invitationEmails.computeIfAbsent(email.toLowerCase(), k -> new AtomicInteger()).incrementAndGet();
        event("invitation.created", invitation);
        send(exchange, 201, invitation);
    }

    private void changeInvitation(HttpExchange exchange, String id, String action) throws IOException {
        ObjectNode invitation = invitations.get(id);
        if (invitation == null) {
            notFound(exchange);
            return;
        }
        String state = current(invitation).get("state").asText();
        if (!state.equals("pending")) {
            send(exchange, 400, template(state.equals("revoked") && action.equals("resend")
                    ? "error_revoked.json" : "error_not_pending.json"));
            return;
        }
        if (action.equals("resend")) {
            invitationEmails.computeIfAbsent(invitation.get("email").asText().toLowerCase(), k -> new AtomicInteger())
                    .incrementAndGet();
            event("invitation.resent", invitation);
        } else {
            invitation.put("state", "revoked").put("revoked_at", Instant.now().toString());
        }
        send(exchange, 200, invitation);
    }

    /** WorkOS reports a pending invitation past its expiry as expired. */
    private ObjectNode current(ObjectNode invitation) {
        if (invitation.get("state").asText().equals("pending")
                && Instant.parse(invitation.get("expires_at").asText()).isBefore(Instant.now())) {
            invitation.put("state", "expired");
        }
        return invitation;
    }

    /** The events reference: the object with its secrets omitted. */
    private void event(String type, ObjectNode object) {
        ObjectNode data = object.deepCopy();
        data.remove(List.of("token", "accept_invitation_url", "password_reset_token", "password_reset_url", "code"));
        ObjectNode event = JSON.createObjectNode().put("object", "event").put("id", id("event")).put("event", type);
        event.set("data", data);
        event.put("created_at", Instant.now().toString());
        event.putObject("context");
        events.add(event);
    }

    /** Oldest first; {@code after} and {@code range_start} together are refused, as the API does. */
    private void listEvents(HttpExchange exchange, Map<String, List<String>> query) throws IOException {
        List<String> types = List.of(query.getOrDefault("events", List.of("")).get(0).split(","));
        String after = query.containsKey("after") ? query.get("after").get(0) : null;
        String rangeStart = query.containsKey("range_start") ? query.get("range_start").get(0) : null;
        if (after != null && rangeStart != null) {
            send(exchange, 422, JSON.createObjectNode().put("message", "after and range_start cannot be combined"));
            return;
        }
        int limit = Integer.parseInt(query.getOrDefault("limit", List.of("10")).get(0));
        List<ObjectNode> all = events();
        int start = 0;
        if (after != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).get("id").asText().equals(after)) {
                    start = i + 1;
                }
            }
        }
        Instant from = rangeStart == null ? Instant.EPOCH : Instant.parse(rangeStart);
        List<ObjectNode> page = all.subList(start, all.size()).stream()
                .filter(e -> types.contains(e.get("event").asText()))
                .filter(e -> !Instant.parse(e.get("created_at").asText()).isBefore(from))
                .limit(limit).toList();
        ObjectNode list = JSON.createObjectNode().put("object", "list");
        ArrayNode data = list.putArray("data");
        page.forEach(data::add);
        ObjectNode metadata = list.putObject("list_metadata");
        if (page.isEmpty()) {
            metadata.putNull("after");
        } else {
            metadata.put("after", page.get(page.size() - 1).get("id").asText());
        }
        send(exchange, 200, list);
    }

    private Optional<ObjectNode> organizationByExternalId(String external) {
        return organizations.values().stream().filter(o -> o.get("external_id").asText().equals(external)).findFirst();
    }

    // --- Plumbing --------------------------------------------------------------------------------

    private String id(String prefix) {
        return prefix + "_01STUB" + String.format("%020d", nextId.getAndIncrement());
    }

    private static ObjectNode template(String name) {
        try (InputStream in = WorkOsStub.class.getResourceAsStream("/workos/" + name)) {
            return (ObjectNode) JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Matcher match(String pattern, String path) {
        Matcher m = Pattern.compile(pattern.replace("ID", ID.pattern())).matcher(path);
        return m.matches() ? m : null;
    }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            params.computeIfAbsent(decode(kv[0]), k -> new ArrayList<>()).add(kv.length > 1 ? decode(kv[1]) : "");
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode body(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        return bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
    }

    private void list(HttpExchange exchange, List<ObjectNode> items) throws IOException {
        ObjectNode list = JSON.createObjectNode().put("object", "list");
        ArrayNode data = list.putArray("data");
        items.forEach(data::add);
        list.putObject("list_metadata").putNull("before").putNull("after");
        send(exchange, 200, list);
    }

    private static void notFound(HttpExchange exchange) throws IOException {
        send(exchange, 404, template("error_not_found.json"));
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }
}
