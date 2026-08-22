# WebSocket Channels for SHTTPS — API Design

Draft for discussion. Tied to the actual classes and conventions of the repository
(`shttps-oss`), so what's described here fits the existing architecture instead of living a
separate life.

*Revision 2: channel password, guest access, LIST_CHANNELS/LIST_PARTICIPANTS/POST rights,
a participant-list endpoint, one-shot HTTP publish, the `push` command. Diff/`replace` are
dropped from the plan entirely.*

## 0. Key finding: the low-level piece already exists

`server-lib` already has a full, dependency-free RFC 6455 implementation:

- `com.phlox.server.websocket.WebSocketRequestHandler` — an abstract endpoint class: it does
  the handshake itself (`Upgrade`/`Sec-WebSocket-*` checks, version, origin), answers with
  `101 Switching Protocols`, and drives `onOpen/onTextMessage/onBinaryMessage/onClose/onError`.
  It's registered in `Router` like an ordinary `RequestHandler`, so it goes through the same
  middleware stack (auth, CORS, rate limiting) as HTTP endpoints.
- `WebSocketSession` — one connection: `sendText/sendBinary`, thread-safe sending from any
  thread, pings/pongs, idle timeouts, a graceful close with a code and a reason.
- `WebSocketRequestHandler.broadcastText(Iterable<WebSocketSession>, String)` — a static
  helper: "send to everyone still alive, silently drop the rest."
- There's already a live example of a multi-client protocol built on top of this:
  `handlers/database/DBTransactionWebSocketHandler` (+`TransactionCommands`) on
  `/api/db/transaction`. Worth borrowing its protocol style: the envelope
  `{"id":...,"command":"..."}` → `{"id":...,"ok":true,"result":{...}}` /
  `{"id":...,"ok":false,"error":{"kind":"...","message":"..."}}`, close codes in the
  private 4000-4999 range mirroring HTTP statuses.

Bottom line: channels are not new infrastructure — they're a new `WebSocketRequestHandler`
plus a state-management layer (`ChannelManager`) on top of primitives that already exist.

## 1. Channel model

```java
class Channel {
    String id;
    Mode mode;                  // ECHO | STATE
    boolean persistent;         // predefined via config -> not removed by idle/API
    boolean deletable;          // can it be removed via DELETE /api/channels/{id}
    boolean guestsAllowed;      // false = only non-guest authenticated users
    boolean binaryAllowed;      // relay raw binary frames too (§7.1), off by default
    @Nullable String passwordHash; // null = no password
    int maxParticipants;
    JSONObject state;           // STATE only, mutated under a lock on the Channel
    long seq;                   // monotonic per-channel event counter
    Set<Participant> participants; // CopyOnWriteArraySet
    long lastActivityAt;
}

class Participant {
    String participantId;       // unique within the channel, generated on connect
    @Nullable String identity;  // User.identity, if the connection is neither anonymous nor a guest
    WebSocketSession session;
    long joinedAt;
}
```

`ChannelManager` — a `ConcurrentHashMap<String, Channel>`, lives on `SHTTPSApp` next to
`Holder<Database>` and `LockManager`, injected into the new handlers via the constructor —
the same pattern already used for DB and WebDAV.

Predefined channels are built from config when `SHTTPSApp.startServer()` starts
(`persistent = true`). Dynamic ones are created through REST and live until they've been
empty longer than `channelIdleTimeoutMillis` — at which point `ChannelManager` drops them.

Channel password hashing isn't a new primitive: reuse whatever hashing function
`shttps-common/auth` already uses for user passwords, don't add a second one. A channel
password is a deliberately lighter-weight secret (something like a room PIN), but there's
no reason to make it weaker than the mechanism the project has already settled on.

## 2. Server settings (`SHTTPSConfig`)

Following the pattern of the existing typed sections (`getCORSRules()`,
`getWebDavSupport()`, `getDBTransactionInactivityTimeoutMillis()`) — new `KEY_CHANNELS_*`
constants, default methods on the interface, bump `CONFIG_VERSION` (5 → 6) with a branch in
`runMigrations()`.

| Method | Meaning | Default |
|---|---|---|
| `isChannelsEnabled()` | overall feature switch | `false` |
| `getAllowDynamicChannelCreation()` | whether `POST /api/channels` is allowed | `false`, exposed as an explicit setting in the server UI |
| `getMaxDynamicChannels()` | cap on the number of runtime channels | `20` |
| `getMaxParticipantsPerChannel()` | default participant cap (overridden by `ChannelDefinition.maxParticipants`) | `50` |
| `getChannelIdleTimeoutMillis()` | how long an empty, non-persistent channel survives before removal | `600_000` (10 min) |
| `getChannelMessageRateLimitPerSecond()` | incoming-message limit per connection, unless the channel overrides it | `20` |
| `getPredefinedChannels()`/`setPredefinedChannels(...)` | JSON array of predefined channels, like `getCORSRules()` | `[]` |

```java
class ChannelDefinition implements Serializable {
    String id;
    Mode mode;
    JSONObject initialState;      // STATE only
    Integer maxParticipants;      // null = use the global default
    boolean deletable = false;
    boolean notifyPresence = true;
    boolean guestsAllowed = true;
    @Nullable String password;    // stored/compared hashed, see §1
    Integer messageRateLimitPerSecond; // null = use the global default; 0 = no limit at all
}
```

`messageRateLimitPerSecond` is settable both here and at creation time through
`POST /api/channels`, and it is the one numeric setting where **zero is a value rather than an
absence**: `null` means "whatever the server-wide setting says", `0` means "do not throttle this
channel", which is what a firehose channel needs while the rest of the server stays protected.
(Contrast `maxParticipants`, where a zero is read as unset — a channel nobody may join is nothing
anybody asks for.) The server-wide setting is read per connection, so it stays editable without a
restart; a channel's own override is fixed when the channel is created.

Separate limits for message length/buffering don't need to be invented — they already exist
on `WebSocketOptions` (`maxMessagePayloadLength`, `maxFramePayloadLength`,
`idlePingIntervalMillis`), set from `SHTTPSConfig` in the handler's constructor, the same
way `DBTransactionWebSocketHandler` already does it.

## 3. Access rights

A new enum on `User`, following `DBRights`/`SystemRights` (an ordinal bitmask):

```java
public enum ChannelRights {
    CONNECT,            // join existing channels (read/listen only)
    POST,               // ECHO: send messages; STATE: send set/merge/delete/increment/push;
                        // either mode with binaryAllowed: send blobs (§7.1).
                        // Without it — CONNECT only, i.e. read-only in both modes
    CREATE,             // POST /api/channels
    DELETE,             // DELETE /api/channels/{id}
    LIST_CHANNELS,      // GET /api/channels — see the list of all channels
    LIST_PARTICIPANTS   // see who's connected to a given channel
}
```

Thread it through the same places as the other rights: `FIELD_CHANNEL_RIGHTS` on `User`
(serialization), `UserRole`, `UserRightsEvaluator`, defaults in `ConfigBasedUserStore`/
`DBBasedUserStore`. A sensible default for the guest is `CONNECT` (no `POST`) — everything
else only for named users with a role.

## 4. REST: channel management

```
GET    /api/channels                    — list channels (LIST_CHANNELS)
POST   /api/channels                    — create a dynamic channel (CREATE)
GET    /api/channels/{id}               — metadata for one channel (CONNECT)
DELETE /api/channels/{id}               — delete, if deletable (DELETE)
GET    /api/channels/{id}/connect       — WebSocket handshake
GET    /api/channels/{id}/participants  — participant list (LIST_PARTICIPANTS), see §9
POST   /api/channels/{id}/message       — publish without a socket (POST), see §10
```

`POST /api/channels` body:
```json
{
  "id": "lobby-42",
  "mode": "state",
  "initialState": {"players": {}},
  "maxParticipants": 8,
  "guestsAllowed": true,
  "messageRateLimitPerSecond": 0,
  "password": "optional-plaintext-at-creation-time"
}
```
`messageRateLimitPerSecond` is optional (§2): omitted, the channel follows the server-wide limit;
`0` asks for no limit; a negative value is a `400`.
`id` can be omitted — the server generates a short slug. A colliding id → `409`. The
response is the same as `GET /api/channels/{id}` (minus the password, of course), plus a
`wsUrl`.

### Routing `{id}` through the existing `Router`

`Router` matches an exact path, and otherwise the longest registered prefix (`RadixTree`);
it has no `{id}`-style path parameters. So, like `FilesRequestHandler` on `/` and
`StaticAssetsRequestHandler` on `/shttps-static-public`, one handler is mounted on the
`/api/channels/` prefix and parses the tail of the path itself (`{id}`, then `/connect`,
`/participants`, `/message`, or nothing):

```java
router.addRoute("/api/channels", Set.of("GET", "POST"),
        new ChannelsCollectionRequestHandler(channelManager, config, authManager), authMiddlewares);
router.addRouteByPathPrefix("/api/channels/",
        new ChannelsPathRequestHandler(channelManager, config, authManager, channelWsHandler),
        authMiddlewares);
```

`channelWsHandler` — a single shared `ChannelWebSocketHandler extends WebSocketRequestHandler`
for all channels. For `.../connect`, `ChannelsPathRequestHandler` runs every check (§5) as an
ordinary HTTP response **before** the upgrade (404/403/409 without ever opening a socket just
to close it right away), and only on success delegates to
`channelWsHandler.handleRequest(context, request)`, having put `channelId` into
`context.data` — exactly the way `DBTransactionWebSocketHandler` already picks up the
authenticated user from `RequestContext` in `onSessionCreated`. The checks need to be
repeated in `onOpen` (there's a race between the HTTP check and the upgrade: the channel
could disappear, or someone could grab the last slot, in between).

Origin for WS — same story as `DBTransactionWebSocketHandler.isOriginAllowed`: the browser
doesn't apply CORS to the handshake, so an explicit check against `config.getCORSRules()`
via `CORSMiddleware.findRuleForOrigin` is needed in the handler.

## 5. Who can connect: server auth, guests, channel password

Three independent layers, in this order:

1. **Overall server authorization.** If `AuthMode == NONE` — not required, anyone can
   connect (the participant is anonymous, `identity == null`). If authorization is on —
   `/api/channels/**`, including `/connect`, goes through the same `AuthManager` as the rest
   of the API (the same `authMiddlewares` already attached to `/api/db/*` and `/api/file/*`
   in `SHTTPSApp.startServer()` — nothing special needs inventing for channels).
2. **Guest access.** If authorization is on and the server has a guest user configured
   (`User.GUEST_IDENTITY`, exactly as it already works for the rest of the API), an
   unauthenticated visitor connects **as the guest**, getting its `ChannelRights`, provided
   the channel allows it: `ChannelDefinition.guestsAllowed` (default `true`; a channel can
   close itself off to registered users only). If guest mode isn't configured, or the
   channel forbids guests, an unauthenticated visitor gets `403`, even if a guest would
   otherwise be fine elsewhere.
3. **Channel password** — an independent, orthogonal layer: if set, it's always required,
   regardless of `AuthMode` or the user's status (even a fully authenticated user with every
   right still has to know that particular channel's password).

Password delivery is a query parameter, `?password=...`, on both `/connect` and `/message`
(§10). The reasoning for a query param over a header: a browser's `WebSocket`
(`new WebSocket(url)`) can't set arbitrary headers on the handshake — only the URL and
`Sec-WebSocket-Protocol` — so the endpoint has to work through the URL to be usable from an
ordinary browser client. An `X-Channel-Password` header can be accepted as an equally valid
alternative for non-browser clients that care about not exposing the password in the
URL/logs — if both are supplied, the header wins.

## 6. Wire protocol: the common envelope

```
C→S  {"id":1,"command":"..."}                       — client request
S→C  {"id":1,"ok":true,"result":{...}}               — reply, to the sender ONLY
S→C  {"id":1,"ok":false,"error":{"kind":"...","message":"..."}}
S→C  {"type":"...","seq":N, ...}                     — event, to EVERYONE (or a subset)
```

`seq` — a monotonic per-channel counter: after a reconnect, the client can tell it missed
something and just re-request the current state — no event log, no persistence.

An event's sender is tagged as an object, not a bare string, so it can carry both the local
and the server-level identifier at once (see §9):
```json
{"participantId": "p_8f2a", "identity": "alice"}
```
`identity` is absent/`null` if the connection is anonymous or a guest.

## 7. ECHO mode

```
C→S  {"id":1,"command":"send","payload":{"text":"hi"}}
S→C  {"id":1,"ok":true,"seq":41}                                                    — to the sender
S→C  {"type":"message","from":{"participantId":"p_8f2a","identity":"alice"},
      "payload":{"text":"hi"},"seq":41}                                             — to everyone else
```

`payload` is arbitrary JSON: chat, a game "move", a notification — all the same primitive.
`send` requires the `POST` right; without it — `403`/a `4403` close on the attempt,
`CONNECT` still lets you listen to the channel.

Whether the sender gets the event echoed back (not just the ack) is configurable on
connect: `?echoToSelf=true` on `/connect` (default `false`). Different clients of the same
channel may want this differently (a simple test client vs. an app doing optimistic UI), so
it's a connection parameter, not a channel one.

### 7.1 Binary messages

A channel with `binaryAllowed` (off by default) also relays **binary** frames, verbatim:

```
C→S  <raw bytes>
S→C  <the same raw bytes>                                    — to everyone else
```

No envelope, no `id`, no ack. There is nowhere in a binary frame to put an envelope without
inventing a framing both ends have to agree on, and the value of a raw relay is precisely
that it needs no agreement — a browser client is `ws.send(blob)` on one side and a
three-line `onmessage` on the other.

The price is that a recipient learns nothing about a blob it didn't already know: not the
sender, not the position in the channel's order. A sender that needs either puts it in the
bytes, or announces the blob with an ordinary text message first.

**A blob takes no `seq`.** It stays outside the sequence rather than consuming a number
nobody can see — `seq` exists (§6) so a client can notice it missed something, and a counter
that skips for reasons the client can't observe would raise exactly the false alarm it's
there to prevent. Text events stay densely numbered whether or not blobs flow past them.
A blob still counts as activity for the `lastActivityAt` the API reports (`Channel.touch()`) —
not for the idle sweep, which never collects a channel that has participants anyway.

The flag is orthogonal to the mode, not a mode of its own: almost every real use wants JSON
control messages *and* blobs on the same channel ("somebody muted" next to the audio
frames), and a `Mode.BINARY` would make those mutually exclusive. A STATE channel may
therefore carry blobs too; the relay never touches its document.

Sending needs the `POST` right, like everything else a participant sends. A channel without
the flag answers a binary frame with an error frame carrying a null `id` and **stays
connected**, exactly as it would an unknown command. Blobs and messages share one rate
limiter budget, so a client can't get a second allowance by switching frame type.

Size is capped by `channelMaxMessageBytes` (default 1 MiB), which is set on **the endpoint,
not the channel** — all channels share one `ChannelWebSocketHandler` and therefore one set
of WebSocket options, and the limit is enforced by the frame reader before any channel is
consulted (a violation is a `1009` close, not an error frame). Raising it to carry 8 MiB
blobs on one channel lets every other channel's connection buffer 8 MiB too.

Known limit: broadcasts are written synchronously on the sender's thread with no write
timeout, so one slow reader stalls that sender for the duration of the write, times N
recipients. Fine for control blobs and modest image/file relay at the scale this server
targets; not a video fan-out transport.

A future framed variant (a length-prefixed JSON header carrying `from`/`seq` ahead of the
payload) is a strict superset and can be added per connection — `?binaryFraming=header` —
without breaking clients written against the raw relay.

Implementation — `WebSocketRequestHandler.broadcastText(...)` over `channel.participants`
(minus the sender, unless `echoToSelf`). No locking needed: `CopyOnWriteArraySet` gives safe
iteration, and there's no state to share.

## 8. STATE mode

On connect the server immediately sends a snapshot:
```
S→C  {"type":"state","seq":40,"state":{"players":{"alice":{"score":3}}}}
```

Mutation commands are a small, purpose-built set, rather than "send me a new JSON, I'll
sort it out" (the command-based approach means a diff is never computed — see the note at
the end of this section):

```
C→S  {"id":1,"command":"set","path":"players/bob","value":{"score":0}}
C→S  {"id":2,"command":"merge","path":"players/alice","value":{"score":4,"ready":true}}
C→S  {"id":3,"command":"delete","path":"players/bob"}
C→S  {"id":4,"command":"increment","path":"players/alice/score","by":1}
C→S  {"id":5,"command":"push","path":"log","value":{"text":"alice joined"}}
```

`path` — a minimal JSON Pointer (RFC 6901): `/`-separated segments, `~1`→`/`, `~0`→`~`, an
empty string or an absent field means the root. Implementing the subset (parsing +
walking/creating nested `JSONObject`/`JSONArray`) needs no library.

- `set` — creates/overwrites the value at the path (creating intermediate objects as needed).
- `merge` — a shallow `Object.assign` of the `value` object over the object at `path`.
- `delete` — removes the key.
- `increment` — an atomic `+=` on a numeric field (counters/scores without a
  read-modify-write race between two participants).
- `push` — appends `value` to the end of the array at `path` (creates an empty array if the
  path doesn't exist yet; errors if something non-array already lives there). This is the
  only array operation in v1: addressing/deleting/inserting by index is deliberately
  unsupported — it requires shifting neighboring indices, which complicates the protocol
  noticeably for a scenario prototypes rarely need. Append-only use cases (chat log, event
  queue, leaderboard) are fully covered by `push`.

Every applied command is broadcast to everyone else as a normalized event — that IS its
"diff", with no delta computed after the fact:
```
S→C  {"type":"patch","op":"merge","path":"players/alice",
      "value":{"score":4,"ready":true},
      "by":{"participantId":"p_8f2a","identity":"alice"},"seq":42}
```
The sender gets a plain ack, `{"id":2,"ok":true,"seq":42}`, without duplicating the payload.

`set`/`merge`/`delete`/`increment`/`push` require the `POST` right (in STATE channels this
right specifically means "may change the state"); without it the channel is read-only — the
snapshot on connect and other participants' patches still arrive, but you can't send your own.

**On diffing:** deliberately not planned at all, not even as a backlog item. The command
protocol above closes the original requirement ("other participants learn what changed, not
the whole state") without computing a diff anywhere. If a client that wants to hand over a
whole new JSON blob is ever actually needed, that's a separate decision to come back to
later — it's out of scope right now.

### Concurrency

`onTextMessage` runs on its *own* connection's thread — applying an operation is protected
by a lock at the `Channel` level, but **the lock must not be held during the network send**:
under `synchronized(channel)` — apply the operation, bump `seq`, copy the event payload and
a snapshot of `participants`; release the lock; then, without the lock, call
`broadcastText`. Otherwise one slow client would stall `set`/`merge` for everyone else until
its socket write times out.

## 9. Participant list

Two complementary mechanisms, not a choice between them:

- **`GET /api/channels/{id}/participants`** (REST, the `LIST_PARTICIPANTS` right) — the
  primary way: works without opening a socket (a dashboard, a bot, a health check), a
  readable snapshot, and it's consistent with `GET /api/channels` (the channel list) also
  being REST under its own `LIST_*` right.
- **A snapshot on WS connect** — the server already knows the list at `onOpen`, so it sends
  it to the new participant for free, the same way it does the `state` snapshot:
  `{"type":"participants","seq":N,"participants":[...]}`. If the connecting client lacks
  `LIST_PARTICIPANTS`, the frame simply isn't sent — they only know about themselves.
- **Presence events**, `join`/`leave`, keep that snapshot current for already-connected
  clients (who already have the right to see it) without polling; toggled per channel via
  `ChannelDefinition.notifyPresence`.

A separate in-socket `list_participants` command isn't needed — it would just duplicate
what "connect snapshot + presence deltas" already provides, without adding anything.

One participant entry:
```json
{"participantId": "p_8f2a", "identity": "alice", "joinedAt": 1765900000000}
```
`identity` is `User.identity`, if the connection is neither anonymous nor a guest; otherwise
the field is absent. `participantId` is always present, including for anonymous/guest
participants.

## 10. One-shot publish over HTTP (no socket)

```
POST /api/channels/{id}/message
```
Body: `{"payload": {...any JSON...}, "senderLabel": "optional-self-reported-string"}`.

**ECHO** channels only (for STATE this doesn't make sense and wouldn't offer the same
convenience — exactly as you guessed): `409`/`400` on a STATE channel. Requires the `POST`
right, and is subject to the same channel password (§5, the same `?password=`) and the same
guest/auth rules — but unlike a WS connection, it registers nothing: the poster doesn't
become a participant, doesn't show up in `participants`, doesn't count toward
`maxParticipants`.

Broadcast to already-connected WS participants as an ordinary `message` event, but tagged
with its source so a recipient can tell a "live" message apart from a one-shot HTTP push:
```json
{"type":"message","source":"http","from":{"participantId":null,"identity":"alice-or-null",
 "label":"optional-self-reported-string"},"payload":{...},"seq":43}
```
`source` on an ordinary WS message is `"ws"`, for symmetry.
Response: `{"delivered": true, "recipientCount": N, "seq": 43}`, or `404`/`403`/`400`.

The use case this is meant for: a cron job, a webhook, or an IoT sensor sends a single
notification into a channel without standing up or holding a socket.

Rate-limited by the ordinary `RateLimitingMiddleware`, like any other HTTP route — no
separate limiter is needed here, unlike for WS connections (§8).

## 11. Close codes

Following `DBTransactionWebSocketHandler`'s example — a private range mirroring HTTP:

| Code | Meaning |
|---|---|
| 4400 | Bad Request — unrecognized command/path |
| 4403 | Forbidden — missing rights, a wrong/missing channel password, guests not allowed |
| 4404 | Not Found — the channel was deleted while the connection was open |
| 4409 | Full — `maxParticipants` reached |
| 4410 | Channel Closed — the channel was explicitly deleted via `DELETE /api/channels/{id}` |
| 4429 | Too Many Requests — `channelMessageRateLimitPerSecond` exceeded |
| 4500 | Internal Server Error |

Plus one standard code a channel connection can end on: `1009` (Message Too Big), when a frame
or a message goes over `channelMaxMessageBytes` (§7.1). It is not in the private range because
it is not this protocol's decision — the frame reader refuses the frame before any channel code
sees it.

## 12. Observability

Add `channels` to the `scopes` of `GET /api/system/status` (`StatusRequestHandler` is
already built for exactly this — a list of scopes, each behind its own right):
```json
"channels": {"enabled": true, "predefined": 3, "dynamic": 5, "totalParticipants": 12}
```
behind the same `SystemRights.READ_STATUS` as `filesystem`/`database`/`system`.

## 13. Decisions made

Everything that used to be an open question is now resolved:

1. **Connect authorization** — see §5: the server's overall `AuthMode` covers WS too, with
   optional guest access on top of that, and an optional channel password as an independent
   layer on top of both.
2. **`allowDynamicChannelCreation`** — default `false`, exposed in the server UI as an
   explicit setting.
3. **Echo to the sender** — not hardcoded, a connection parameter, `?echoToSelf=` (default
   `false`).
4. **Persistence** — in-memory only, nothing beyond that is planned.
5. **Arrays** — `push` (append) only; addressing/deleting by index is out of v1 scope.

The diff/`replace` command is dropped from the plan entirely (not just deferred) — the
command protocol in §8 closes the original requirement without it.

## 14. Rollout order

1. `Channel`/`ChannelManager` + config + `ChannelRights`, threaded through `User`/
   `UserRole`/`UserRightsEvaluator`/the user stores. **Predefined** channels only, **ECHO**
   only. The full connect-authorization chain (server auth/guests/password, §5) is built in
   from the start, not deferred — retrofitting it later on top of an already-open connect
   path is riskier than building it in up front. `GET /api/channels`,
   `GET /api/channels/{id}`, `GET /api/channels/{id}/participants` for predefined channels.
2. **STATE** mode: `set`/`merge`/`delete`/`increment`/`push`, a snapshot on connect.
3. Dynamic management: `POST`/`DELETE /api/channels` (including `password`/
   `guestsAllowed`/`maxParticipants` at creation time), `maxDynamicChannels`, idle GC.
4. `POST /api/channels/{id}/message` — one-shot publish without a socket (ECHO only).
5. Presence snapshot+events, rate limiting, `/api/system/status` integration — polish.
