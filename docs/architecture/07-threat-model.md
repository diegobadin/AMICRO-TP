# Threat Model (Lightweight STRIDE)

> Strongly recommended deliverable (Architecture Checkpoint §7). STRIDE-based analysis of public APIs, session takeover, event tampering, and rate-limit bypass.

---

## 1. Scope

This threat model covers the external attack surface of UnoArena and the most impactful internal threats, focused on:

- Public REST + SSE APIs (player-facing, spectator-facing, admin)
- Authentication and session management
- Domain event integrity (game log, Kafka)
- Rate-limit enforcement across trust boundaries
- Tournament-specific abuse (registration spam, collusion)

Out of scope: physical infrastructure, supply-chain attacks, insider threats at the cloud-provider level.

---

## 2. STRIDE Analysis

### 2.1 Spoofing

| Threat | Attack Vector | Mitigation | Owner |
|--------|--------------|-----------|-------|
| **S1. Impersonate another player** | Forge or steal JWT to issue commands as another `playerId` | JWTs signed with RS256 (asymmetric); short expiry (15 min); refresh token rotation. Gateway validates signature on every request. | Identity & Session + Gateway |
| **S2. Session hijacking (token theft)** | XSS, network sniffing, or stolen refresh token | HTTPS-only (HSTS); `HttpOnly` + `Secure` + `SameSite=Strict` cookies for refresh token; no token in URL. Single-active-session: new login invalidates old token + kills old SSE (see 02 §SK1–SK4). | Identity & Session + Gateway |
| **S3. Reconnect as another player** | Use a valid session to reconnect to a room the attacker is not a member of | `ReconnectPlayer` validates `playerId` from JWT matches room membership; membership stored in Room aggregate, not client-provided. | Room Gameplay Service |
| **S4. Spectator posing as player** | Spectator crafts requests to player endpoints | Gateway enforces route-level RBAC: player endpoints require `role: player` + room membership check. Spectator SSE uses a structurally separate endpoint (`/spectate/`). | Gateway + Room Gameplay |

### 2.2 Tampering

| Threat | Attack Vector | Mitigation | Owner |
|--------|--------------|-----------|-------|
| **T1. Modify game log entries** | Direct DB access or MitM on internal network | Event store is append-only (DB trigger blocks UPDATE/DELETE); application role is INSERT + SELECT only. Each entry HMAC-signed; replay jobs verify chain. Internal network uses mTLS. | Room Gameplay + DB + ops |
| **T2. Tamper with Kafka events** | Malicious producer or MitM on broker traffic | Kafka ACLs: only the Outbox Relay can produce to `room.*` topics. Broker-to-broker and client-to-broker TLS. Event payloads carry `eventId` + `sequenceNumber`; consumers validate ordering. | Kafka ops + Outbox Relay |
| **T3. Modify command in flight** | MitM between client and gateway | All client traffic over TLS 1.3 (HSTS). `If-Match` ETag prevents replay of stale commands. | Gateway (TLS termination) |
| **T4. Forge timer expiry callback** | External request to `/internal/rooms/{roomId}/timer-expired` | Internal-only endpoint: not routed through the public gateway. Protected by network policy (pod-to-pod only) + optional mTLS. Timer `timerId` is validated against aggregate state. | Room Gameplay + network policy |

### 2.3 Repudiation

| Threat | Attack Vector | Mitigation | Owner |
|--------|--------------|-----------|-------|
| **R1. Player denies an action** | Claim they never played a card or called Uno | Every accepted command is appended to the immutable, HMAC-signed game log. `correlationId` traces the command back to the HTTP request with player identity. | Room Gameplay (game log) |
| **R2. Admin denies audit action** | Claim they never accessed a game log | All game log reads are logged in the platform audit log (Identity context) with actor, timestamp, and scope. Break-glass access requires multi-party approval. | Identity & Session (audit log) |

### 2.4 Information Disclosure

| Threat | Attack Vector | Mitigation | Owner |
|--------|--------------|-----------|-------|
| **I1. Spectator sees player hands** | Subscribe to the player SSE stream or exploit a bug in event filtering | Three-layer defense: (1) Public Event Publisher strips hand data at source; (2) Spectator View ACL rejects events containing private fields; (3) `SpectatorRoomView` data model has no hand fields. Spectator SSE endpoint is structurally separate from player endpoint. | Room Gameplay + Spectator View |
| **I2. Player sees another player's hand** | Query the game state endpoint for another player | `GET /rooms/{roomId}/games/{gameId}` returns the **requesting player's** hand only. Authorization: `request.playerId == JWT.playerId`. No endpoint exposes another player's hand. | Room Gameplay |
| **I3. Leak RNG seed / deck order** | Inspect public events for shuffle state | Public events never contain `rngSeed`, `deckOrder`, or drawn card identity. `CardDrawn` only reports `newCardCount`. `DeckRecycled` only reports `newDeckSize`. | Room Gameplay (Public Event Publisher) |
| **I4. PII leakage** | Cross-context data exposure | Only the Identity context stores PII (email, credentials). All other contexts use opaque `playerId` UUIDs. GDPR deletion only needs to scrub Identity DB. | Identity & Session |

### 2.5 Denial of Service

| Threat | Attack Vector | Mitigation | Owner |
|--------|--------------|-----------|-------|
| **D1. Command spam (per-IP)** | Flood the gateway from one or many IPs | L1: per-IP token bucket (50 req/s default) at the gateway. Adaptive throttling: repeated 429s → progressive reduction → temporary block. | Gateway |
| **D2. Command spam (per-user)** | Rotate IPs but use same account | L2: per-user sliding window (10 game actions/s, 5 tournament/s) enforced by Identity via gRPC from gateway. | Identity & Session |
| **D3. Room-level flooding** | Spam a single room to overload its aggregate | L3: per-room in-process rate limiter (30 commands/s aggregate). Sequence numbers make most spam fail with 412. | Room Gameplay |
| **D4. Tournament registration spam** | 100k bot accounts register for a tournament | L4: per-tournament rate limiter (100 reg/s). Registration may require email verification / CAPTCHA (Identity). Tournament enforces `maxPlayers`. | Tournament Orchestration + Identity |
| **D5. Spectator connection storm** | Open millions of spectator SSE connections | Spectator cap per room (configurable, default 50k). Regional edge / CDN for fan-out beyond cap. Gateway connection limits per IP. | Gateway + Spectator View |
| **D6. Bypass rate limiting via direct service access** | Circumvent gateway and hit Room Gameplay directly | Services are in private network (not exposed to internet). Gateway is the only public ingress. Kubernetes NetworkPolicy / security groups block external access to service ports. | Infrastructure / network policy |

### 2.6 Elevation of Privilege

| Threat | Attack Vector | Mitigation | Owner |
|--------|--------------|-----------|-------|
| **E1. Player gains admin access** | Exploit JWT claims or endpoint authorization | JWTs carry `role` claim; gateway validates role before routing to admin endpoints. Admin gateway is a separate deployment with stricter access controls. Token signing key is not shared with client-facing infra. | Gateway + Identity |
| **E2. Manipulate tournament advancement** | Forge `MatchCompleted` events to inject fake results | Only the Outbox Relay (CDC) can produce to `room.lifecycle.events` (Kafka ACL). Tournament Orchestration validates `roomId` belongs to the current round. Idempotency guard prevents duplicate `RecordRoomResult`. | Kafka ACLs + Tournament |
| **E3. Access game log without authorization** | Call the audit API without proper role | Audit API served through admin gateway only; requires `role: operator` or `role: admin` in JWT. Break-glass access requires multi-party approval logged in the audit trail. | Room Gameplay + Identity |

---

## 3. Trust Boundaries

```
┌─────────────────────────────────────────────────────────────────┐
│  INTERNET (untrusted)                                           │
│  Clients: players, spectators, admins                           │
└───────────────────────┬─────────────────────────────────────────┘
                        │ TLS 1.3
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│  DMZ: API Gateway (public) + Admin Gateway (restricted)         │
│  • TLS termination                                              │
│  • JWT validation (signature + expiry)                          │
│  • L1 rate limiting (per-IP)                                    │
│  • L2 rate limiting (per-user, via gRPC to Identity)            │
│  • Route-level RBAC (player vs spectator vs admin)              │
│  • Session-invalidation subscriber (Redis pub/sub)              │
└───────────────────────┬─────────────────────────────────────────┘
                        │ mTLS / private network
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│  INTERNAL SERVICES (trusted after gateway validation)           │
│  Room Gameplay, Tournament, Ranking, Identity, Spectator View,  │
│  Analytics, Timer Worker, Outbox Relay                           │
│  • Trust X-Player-Id / X-Session-Id headers from gateway        │
│  • L3/L4 rate limiting (per-room, per-tournament)               │
│  • Internal endpoints (timer callbacks) not routed via gateway  │
│  • Kafka ACLs per producer context                              │
└───────────────────────┬─────────────────────────────────────────┘
                        │ application credentials
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│  DATA STORES (per-context, isolated credentials)                │
│  PostgreSQL (per context), Redis, Kafka, ClickHouse             │
│  • No cross-context DB access                                   │
│  • Event store: INSERT + SELECT only for application role       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Risk Summary

| Risk | Severity | Likelihood | Residual risk after mitigations |
|------|----------|-----------|-------------------------------|
| Session takeover (S2) | High | Medium | Low — single-active-session + live SSE kill + short token expiry |
| Hand data leakage (I1) | High | Low | Very Low — three-layer structural defense |
| Game log tampering (T1) | Critical | Low | Very Low — append-only + HMAC + mTLS + DB triggers |
| Rate-limit bypass (D6) | High | Low | Very Low — network policy enforces gateway-only ingress |
| Tournament result forgery (E2) | Critical | Very Low | Very Low — Kafka ACLs + domain validation + idempotency |
| Bot registration spam (D4) | Medium | High | Medium — rate limits + CAPTCHA help but determined attackers may still accumulate accounts slowly |
| Collusion between players | Medium | Medium | Medium — cannot be prevented technically; game log enables post-hoc detection (see design §6.5.5) |
