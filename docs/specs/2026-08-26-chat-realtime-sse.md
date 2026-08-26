# Chat real-time delivery over server-sent events

## Problem

The chat module is feature complete except for delivery. Rooms, participants,
text messages, replies, edits, reactions, soft deletes, room archive, mentions
and attachments all work, but the web client learns about them by polling:
`hooks/chat/use-chat-messages.ts` and `hooks/chat/use-chat-rooms.ts` each carry
`refetchInterval: 15 * 1000` with the note "poll every 15s until WebSocket is
wired". A message therefore takes up to fifteen seconds to appear, and every
open tab issues two requests per fifteen seconds whether or not anything
changed.

Two properties of the current architecture constrain any fix.

1. **The web tier is a two-tier BFF over `fetch`.** `app/api/v1/[...path]/route.ts`
   is a Next.js route handler: it decrypts the NextAuth session cookie, checks
   revocation, attaches the Keycloak bearer token and `X-Organization-Id`, and
   forwards with `fetch`. A route handler has no access to the raw socket, so it
   cannot carry an HTTP upgrade. The browser never holds the access token, so it
   cannot authenticate to `backend.echno.*` on its own either.

2. **Chat DTOs are viewer dependent.** `ChatService.toRoomDto(room, viewerEmployeeId)`
   computes that viewer's `unreadCount`, and `toMessageDto` attaches freshly
   presigned attachment URLs. There is no single rendered payload that is correct
   for every recipient of an event.

## Target

Messages, message state (edit, soft delete, reaction) and room list state
(ordering, last message, unread count) reach open clients within a round trip of
the write that caused them, without opening a second authenticated ingress path
and without changing the edge, the tunnel or the content security policy.

Typing indicators and online presence are out of scope. Both would need a
client-to-server channel; nothing in this design provides one.

## Approach

Server-sent events streamed through the existing BFF, with cross-replica fan-out
over the Redis instance already deployed for the shared cache.

A websocket was considered and rejected on cost, not capability. Because the BFF
cannot proxy an upgrade, a websocket would have to run browser-direct to
`backend.echno.*`, which requires a separately minted connection credential, a
handshake interceptor that reconstructs the authentication and tenant context
outside the servlet filter chain, a `connect-src` entry in the CSP, `websockets:
true` on the backend vhost, raised ingress timeouts, and verification of the
gateway tunnel's websocket path. All of that buys a client-to-server channel this
feature does not use.

### Events are notifications, not state

An event carries identifiers only:

```
type            MESSAGE_CREATED | MESSAGE_UPDATED | ROOM_UPDATED
orgId           tenant the event belongs to
roomId          room the event concerns
messageId       message concerned, null for room level events
actorEmployeeId employee who caused it
recipients      employee ids that should receive it
```

The client responds by invalidating the matching TanStack query, and the refetch
goes back through the ordinary authenticated endpoint. Nothing tenant scoped is
rendered into the fan-out path, so authorization stays entirely on the REST path
where `RPTExchangeFilter`, `TenantFilter` and `@orgSecurity` already enforce it.
Given the viewer-dependent DTOs above, pushing rendered state would mean
rendering per recipient on whichever pod holds their connection, which is both
more work and a wider surface for the class of tenant leak that
`TenantIsolationLoadListener` exists to prevent.

`MESSAGE_UPDATED` deliberately covers edit, soft delete and reaction toggle. The
client's reaction to all three is the same invalidation, so distinguishing them
would add vocabulary with no consumer.

## Changes

### Backend: stream endpoint

New `GET /api/{version}/chat/stream` on `ChatControllerWeb`, producing
`text/event-stream` and returning `SseEmitter`. It carries the same
`@PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")` as every other chat
endpoint, so it is authenticated and tenant resolved by the existing filter
chain with no new code path.

The response sets `X-Accel-Buffering: no`. The compose edge vhost
(`ansible/roles/edge/templates/vhost.conf.j2`) sets `proxy_buffering on` for
every site, and nginx and ingress-nginx both honour that header to disable
buffering for a single response, which avoids editing the vhost.

Measured against those exact directives (see Verification below), nginx forwards
a flushing chunked stream promptly whether or not the header is present. It is
therefore a safeguard rather than the thing that makes this work, and it is kept
as one: it costs a response header and removes any dependence on buffer sizing
staying as it is today.

**Emitter lifetime is ten minutes**, after which the emitter is completed and the
browser's `EventSource` reconnects. This is a security property, not a resource
one. Spring Security authenticates a request once, at the start; a stream open
for hours would outlive both the access token and any session revocation. A ten
minute ceiling means a revoked session loses delivery within ten minutes, because
the reconnect re-runs the BFF revocation check and the whole filter chain. The
stream opens with a `retry: 1000` field so the reconnect is prompt.

### Backend: connection registry

New `ChatStreamRegistry`, a `ConcurrentHashMap` keyed by `(orgId, employeeId)`
holding the set of that employee's live emitters. One employee legitimately has
several (multiple tabs), so the value is a set, capped at **five** per employee;
beyond the cap the oldest is completed. Without a cap, a client that reconnects
faster than it releases accumulates emitters until the heap gives out.

Registration removes itself on completion, timeout and error.

A `@Scheduled(fixedRate = 15000)` heartbeat writes an SSE comment to every live
emitter. Fifteen seconds sits under the two idle timeouts on the path: nginx
`proxy_read_timeout 90s` on the backend site, and Cloudflare's 100 second idle
ceiling. Because `proxy_read_timeout` measures the gap between reads rather than
total duration, a heartbeat under the gap keeps a stream alive indefinitely with
no timeout raised anywhere.

This requires `@EnableScheduling` on `EchnoBackendApplication`, which is
currently absent. Two `@Scheduled(fixedRate = 60000)` methods already exist and
consequently never run: `RPTCache.logStats` and `SubscriptionCache.logStats`.
Both only log cache statistics at DEBUG, so enabling scheduling starts those log
lines and changes nothing else.

### Backend: fan-out

`ChatEventPublisher` interface with two implementations, selected by the existing
`echno.cache.provider` switch so real-time delivery follows the same single
control as the cache and the rate limiter:

- `RedisChatEventPublisher` (`provider=redis`) publishes the event as JSON to
  channel `echno:chat:events`. A `RedisMessageListenerContainer` on every replica
  subscribes and hands each event to its local `ChatStreamRegistry`, which
  delivers to whichever recipients it happens to hold. This is what makes a
  message written on one pod reach a subscriber on another.
- `LocalChatEventPublisher` (default, `provider=caffeine`) dispatches straight to
  the local registry. Correct for the single-replica compose deployment, and it
  keeps the feature working with no Redis at all.

A single channel with local filtering, rather than a channel per tenant: with
production deployed as a per-client instance, per-tenant sharding would reduce
noise that does not exist.

Redis pub/sub does not persist, so an event published while a replica is
momentarily unsubscribed is lost. This is why polling is retained (see below)
rather than removed, and why reconnect invalidates wholesale.

### Backend: publication points

`ChatService` raises a `ChatEvent` through `ApplicationEventPublisher`, and a
`ChatEventListener` in `common/events/listeners` handles it with
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` before
handing it to the `ChatEventPublisher`. This is the pattern `InventoryEventListener`
and `ComplianceGenerationListener` already use, and it is what stops a rolled back
write from notifying anyone.

Recipients are resolved inside the transaction, while the room and its
participants are loaded and the tenant context is set, and travel on the event.
The listener therefore performs no tenant scoped read of its own.

Publication is added to `sendMessage` (both overloads converge on one),
`editMessage`, `deleteMessage`, `toggleReaction`, `setArchived` and `markRead`.
The first four are `MESSAGE_*`; the last two are `ROOM_UPDATED`.

`markRead` publishes only to the actor: an unread count is per viewer, and no
other participant's view changes when someone else reads a room.

New repository method on `ChatParticipantRepository`:

```java
List<Long> findEmployeeIdsByRoom_Id(Long roomId);
```

### Web: BFF streaming branch

One branch in `app/api/v1/[...path]/route.ts`. When the upstream response
content type is `text/event-stream`:

- return `response.body` directly rather than `await response.text()`, which
  today would buffer the stream forever,
- do not arm the 30 second abort timer, which would otherwise cut the stream,
- pass `X-Accel-Buffering: no` through to the browser.

Everything before that point is unchanged and reused: session decryption,
`isSessionRevoked`, the bearer token and `X-Organization-Id`. Because the stream
is same origin from the browser's point of view, no CSP `connect-src` entry and
no CORS configuration are needed.

### Web: client

New `ChatStreamProvider`, mounted once in the dashboard layout. It must be
mounted once and only once: both the chat page and the dashboard-wide floating
chat consume chat data, and one `EventSource` per component would multiply
connections by the number of mounted consumers.

On each event it invalidates `['chat-messages', roomId]` and `['chat-rooms']`.
On reconnect it invalidates both wholesale without a room filter, which repairs
anything missed while disconnected, including a dropped pub/sub event.

The provider exposes connection state. `use-chat-messages` and `use-chat-rooms`
move their `refetchInterval` to 60s while the stream is connected and back to 15s
when it is not. Polling is demoted rather than deleted: it is the recovery path
for a lost event, and the fallback for a client whose stream cannot be
established at all.

### Infrastructure

No change. No `websockets: true` on the backend vhost, no ingress annotation, no
tunnel configuration, no CSP entry, no timeout raised. Routing the stream through
the BFF over the path that already works is the reason this design was chosen.

The claim still gets verified rather than assumed: the stream is exercised end to
end on `echno.in` (compose, single replica, local publisher) and on
`ui-k8s.echno.in` (k3s, HPA up to four replicas, Redis publisher) before the work
is called done.

## Testing

Unit:

- `ChatStreamRegistry`: register, deliver, remove on completion, evict oldest at
  the five emitter cap, and isolation between two tenants holding the same
  employee id.
- `ChatEventPublisher`: recipients resolve to room participants; `markRead`
  targets the actor only.
- Event JSON round trip.

Integration:

- `ChatStreamIT`: a write through `ChatService` reaches an emitter registered for
  a participant, and does not reach a non-participant.
- A Redis container test with two registries standing in for two replicas,
  asserting an event published against one is delivered by the other. This is the
  property the whole fan-out design exists for, so it gets a test that would fail
  if the listener container were misconfigured.
- Publication happens after commit: a write that throws produces no event.

Web:

- The provider invalidates the expected query keys per event type.
- Reconnect triggers unfiltered invalidation.
- `refetchInterval` follows connection state.

Manual, on `echno.in` and `ui-k8s.echno.in`: two browsers in one room, message
appears without a poll interval elapsing; reaction, edit and delete propagate;
stream survives more than ten minutes across at least one emitter recycle;
killing the backend pod holding one client's stream results in reconnection and a
repaired view.

## Verification

The proxy assumptions above were measured rather than assumed, against an nginx
carrying the same directives as the backend site in the edge vhost, in front of a
flushing chunked SSE responder. Two results, both from a raw-socket client (a
shell pipeline through `awk` or `head` buffers the stream and reports every frame
arriving at once, which looks exactly like the failure being tested for):

1. Frames arrive at the client as they are produced, one second apart, whether or
   not `X-Accel-Buffering: no` is set. `proxy_buffering on` does not delay a
   stream whose upstream flushes.
2. With `proxy_read_timeout` compressed to 3s so the behaviour is observable in
   seconds: a stream carrying a frame every second survives past the timeout
   indefinitely, and a stream that goes silent after its opening frame is cut.
   This is the property the heartbeat interval rests on, that the timeout bounds
   the gap between reads rather than the life of the response. Scaled up, a
   comment every 15s clears the real 60s (`ui`) and 90s (`backend`) values.

What this does not cover is Cloudflare and the tunnel. `cloudflared` runs with
`protocol: http2` (forced, since QUIC is not proxyable through the lab's Squid
egress), and whether that path passes a long-lived stream unbuffered can only be
established on a deployed environment.

## Out of scope

- Typing indicators and presence, as above.
- `echno-core`. Chat has no presence in that library: the types, services and
  hooks all live in `echno-web` (`types/chat.ts`, `services/chat-*.ts`,
  `hooks/chat/`). No version bump or release is involved.
- Push notifications to a device when no client is connected.
- Message history replay on reconnect via `Last-Event-ID`. Redis pub/sub holds no
  history, so replay would need a durable log; refetch on reconnect achieves the
  same visible result.
