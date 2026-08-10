# Validation — P3: Uno Engine + Room-Gameplay Core

> One executable check per acceptance criterion. Phase gates and drill transcripts get recorded
> here as F1–F10 run.

## Verification matrix

| AC | Check | Pass condition |
|----|-------|----------------|
| AC-P3.1 | `POST /rooms` twice with the same `Idempotency-Key`; join twice; join a full room; leave twice; `GET /rooms`. | `201` then `200` with the same body; second join `409`/no-op; full room `409`; second leave `204`; the list shows only joinable rooms. |
| AC-P3.2 | `./gradlew :engine:test` — property suite over generated games + replay suite. | Card multiset conserved at every step; one open challenge window at most; turn order respects direction and skips; replaying the log reproduces the exact final state **including deck order**; replaying twice is identical. |
| AC-P3.3 | Failure-injection against a **real** Postgres (CI smoke's ephemeral one, or kind): force the outbox insert to fail mid-command. | Zero `room_events` rows, zero `outbox` rows, sequence number unconsumed, and the client got a `5xx` — never a `2xx`. A fake store cannot prove this, so it is not verified in the unit stage. |
| AC-P3.4 | Stale/missing `If-Match`; out-of-turn move; two concurrent writers on the same seq. | `412` / `428` / `409`; exactly one writer commits, the loser gets `412` and the returned state lets it reconcile. |
| AC-P3.5 | Advance the injected clock past each deadline, then send any command. | Uno! window closes, `TurnTimedOut` auto-draws and passes, the 60 s window forfeits — each recorded as an event before the incoming command is processed. |
| AC-P3.6 | Log in twice as a player who is sitting in an active room. | `PlayerDisconnected` appears in that room's log, sourced from `identity.session-events`; the 60 s window opens. |
| AC-P3.7 | Two CLI processes play a full casual game against a from-empty cluster. | A winner, `GameCompleted` in the log, and the transcript below shows a wild colour, a draw, an Uno! call and a successful challenge. |
| AC-P3.8 | `kind delete cluster` then `install.sh`, then the probes below. | room-gameplay `Synced/Healthy` with its own DB role, decrypted secrets, migrated schema, `/metrics` scraped. |
| AC-P3.9 | The `main` pipeline of the P3 merge. | Green; stage list identical to P2's; `test:room-gameplay` runs the property and replay suites. |

## Probes (used by AC-P3.8)

```bash
# Per-service credentials, like identity's. Read as the superuser: the per-service role has no
# peer authentication over the container's unix socket, which is the point of it.
kubectl -n postgres exec unoarena-pg-1 -c postgres -- psql -U postgres -c "\du"     # room_gameplay present
kubectl -n postgres exec unoarena-pg-1 -c postgres -- psql -U postgres -d room_gameplay -c "\dt"
#   → room_events, outbox, rooms, idempotency_keys

# Image pinned by digest, secrets decrypted
kubectl -n unoarena-staging get pod -l app=room-gameplay \
  -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'
kubectl -n unoarena-staging get secret room-gameplay-secrets

# Metrics scraped
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090 &
curl -s 'http://localhost:9090/api/v1/targets?state=active' | grep room-gameplay
```

## The casual game drill (AC-P3.7)

Two terminals, two registered players, one room. The point is not that it runs — it is that the
log and the served state agree at every step.

```bash
export UNOARENA_API_URL=http://localhost:30080

# terminal 1
UNOARENA_SESSION=/tmp/a.json unoarena register --user alice --pass pw
UNOARENA_SESSION=/tmp/a.json unoarena play --casual

# terminal 2
UNOARENA_SESSION=/tmp/b.json unoarena register --user bob --pass pw
UNOARENA_SESSION=/tmp/b.json unoarena play --casual     # joins alice's room, game auto-starts

# afterwards: the log is the authority
kubectl -n postgres exec unoarena-pg-1 -c postgres -- psql -U room_gameplay -d room_gameplay \
  -c "select sequence_number, type from room_events where room_id = '<id>' order by sequence_number"
```

Record in the transcript: the wild colour declaration, a draw, the Uno! call, a successful
challenge, and the final `GameCompleted`.

## Definition of done

- [x] AC-P3.1 … AC-P3.9 all green, with transcripts below.
- [x] `engine/` has no framework dependency and its suites run without a database or a container.
      `:engine:dependencies --configuration runtimeClasspath` is `kotlin-stdlib` plus
      kotlinx-serialization (D15, recorded in `ESTADO-FINAL.md` with its reason); 70 tests, no
      database, no container, ~20 s.
- [x] No player's hand appears in another player's game view, in any outbox payload, or in any log
      line — checked, not assumed. `MovesHttpTest` strips the requester's own hand and the public
      discard top from the raw JSON and asserts no card notation survives anywhere else; the
      cluster drill confirms `leaked_seeds=0` across 355 outbox rows; no event payload carries hand
      contents or deck order by construction.
- [x] `clients/cli/README.md` maps every new command to its endpoint and states what is still
      missing (live feed via SSE, bot, reconciliation UX — all P4), plus the lazy-timer consequence
      of E2 and the two-URL shape.
- [x] Cards are printed in the canonical notation of Client-Checkpoint §5.F — and it is the wire
      format, so the CLI does no translation. Round-tripped for every card in the deck.
- [x] `CHANGELOG-design.md` §8 records the auto-start delta, `GET /rooms` as an additive read, the
      polling stand-in for SSE, the shared JWT secret, and the two-URL CLI shape — plus
      `PlayerLeft`, the sequence-number-per-event choice, the initial-Wild colour narrowing,
      `consumed_events`, and why `StaleCommandRejected` is not appended.
- [x] `ESTADO-FINAL.md` written and the north-star roadmap marks P3 **SHIPPED**.

## Phase gates

| Phase | Commit | Evidence |
|---|---|---|
| F1 | `cb2978e` | On a cluster built from empty: `room_gameplay` role present and able to log in, `room_gameplay` database owned by it, `room-gameplay-secrets` decrypted with both keys, `room-gameplay-db-role` of type `basic-auth`, all three KafkaTopics `Ready`. The role can reach identity's database but is refused its tables. |
| F2 | `83f20d3` | 9 tests. `/health` and `/metrics` open; `/rooms` `401` without a token, with a token signed by another secret, and with an expired one; `200` with a valid identity token. Route labels collapse to a fixed template list, so an unknown path can never become a metric label. |
| F3 | `549d3a9` | 16 tests. Deck composition is exactly the 108-card multiset; shuffling is a permutation and the same seed reproduces the order over 1000 generated seeds; dealing conserves the deck; a Wild Draw Four never starts the discard pile. `:engine:dependencies` shows `kotlin-stdlib` alone. |
| F4 | `6fb0250` | 66 engine tests. Property suites over 1000 generated games each: the card multiset is conserved at **every** transition, the active colour always admits a play, at most one challenge window is open and only on a one-card hand, the ring stays valid, and every table size 2–10 plays to an ending. Replay reproduces the served aggregate exactly, deck order included, and replaying twice is identical. 39 rules tests cover the behaviour contract case by case. |
| F5 | `8d41f77` | 9 store tests against a real Postgres. A trigger that raises on any outbox insert leaves **zero** `room_events`, **zero** `outbox` rows, no projection row and the sequence number unconsumed — and the same command then succeeds once the fault is removed. Two writers on one sequence number: exactly one commits, the loser gets `Conflict` and wins its retry after reloading. |
| F6 | `833e29f` | 12 HTTP tests against the real store. `201` then `200` for a replayed `Idempotency-Key` with the same room; the same key from a different player is a different room; second join `409`; a started room refuses a third player; leaving twice is `204` twice; the list shows only joinable rooms; auto-start fires on the second join. |
| F7 | `5c813fd` | 10 HTTP tests. Missing `If-Match` → `428`, stale → `412` carrying the current player-scoped state and its `ETag`, out-of-turn → `409`, wild without a colour → `409`. `If-None-Match` → `304` with an empty body, then `200` once a move lands. A full game played to a winner over HTTP, ending `GameCompleted` → `RoomCompleted` as the last rows of the log. Privacy is checked on raw JSON: with the requester's own hand and the public discard top removed, no card notation survives anywhere in the response. |
| F8 | `e968d62` | 5 tests with an injected clock. Past the turn timer, a move that would have been out of turn is **accepted**, because `TurnTimedOut` is settled and logged first. An expired challenge window closes itself. A superseded session produces `PlayerDisconnected` in the player's active room and opens the 60-second window; a redelivery of the same `oldSessionId` changes nothing. An expired reconnection window forfeits, which ends a two-player game with the last player standing. |
| F9 | `44681cf` | 17 CLI tests plus the two-process drill. The drill found three defects no unit test would have: both players creating a room and waiting for each other forever, a board offering `pass` before drawing (a `409`), and the CLI calling Uno! on the player's behalf — which makes every player permanently safe and deletes the call-and-challenge mechanic. |
| F10 | this commit | The from-empty cluster drill below. |


---

## Drill: from an empty cluster (2026-08-10)

`kind delete cluster` → `install.sh` with `TARGET_REVISION=feat/p3-room-gameplay`, on a machine
whose image cache had been wiped with the cluster.

### What the drill found

**`install.sh` aborted before it registered anything.** `kubectl rollout status deploy/argocd-server`
was capped at 180 s; on a cold image cache argocd-server took about six minutes, so the script hit
`set -e` and exited **before applying the projects and both app-of-apps**. Argo CD was left running
with nothing to reconcile — a failure that reads as "the GitOps layer is broken" rather than "an
image was slow". The timeout is 600 s now, and the rerun was clean.

This is exactly the failure mode the phase gate exists for: P2's drill passed only because the
images were already local. The faculty's machine will not have them.

### AC-P3.8 — it comes up from an empty cluster

```
$ kubectl -n argocd get applications
room-gameplay-staging   Synced   Healthy
identity-staging        Synced   Healthy
postgres                Synced   Healthy
monitoring              Synced   Healthy
secrets-staging         Synced   Healthy
kafka / redis / cnpg-operator / strimzi-operator / both roots   Synced   Healthy
(the seven remaining placeholders are Degraded — no images built for them yet, unchanged from P2)

$ psql -U postgres -tAc "select rolname from pg_roles where rolname in ('identity','room_gameplay')"
identity
room_gameplay

$ psql -U postgres -tAc "select datname, pg_get_userbyid(datdba) from pg_database where datname='room_gameplay'"
room_gameplay owned by room_gameplay

$ psql -U postgres -d room_gameplay -tAc "select tablename from pg_tables where schemaname='public'"
consumed_events
idempotency_keys
outbox
room_events
rooms

$ kubectl -n unoarena-staging get pod -l app=room-gameplay -o jsonpath='{...imageID}'
registry.gitlab.com/.../unoarena/room-gameplay@sha256:208b19b2d74a94e87b8987c25e31933ebdd6ef6529b91d2887e94e8527584ab7

$ kubectl -n unoarena-staging get secret room-gameplay-secrets -o jsonpath='{.data}'
IDENTITY_JWT_SECRET   ROOM_GAMEPLAY_DB_PASSWORD          # both decrypted from the committed SealedSecret

$ curl -s http://localhost:30081/health
{"status":"ok","service":"room-gameplay"}                # the second NodePort, published by kind

$ curl -s 'http://localhost:9090/api/v1/targets?state=active' | grep room-gameplay
room-gameplay  up  http://10.244.0.24:8081/metrics
$ curl -s 'http://localhost:9090/api/v1/query?query=roomgameplay_games_started_total'
{"job":"room-gameplay", ...}                             # already being collected
```

The service crash-looped for about nine minutes first, waiting for CNPG to elect a primary, then
self-healed — the same shape P2 recorded, and the reason the chart has a `startupProbe`.

### AC-P3.1 — rooms behave like resources

```
POST /rooms  Idempotency-Key: drill-key-1   -> 201  room cd7d23f3-…
POST /rooms  Idempotency-Key: drill-key-1   -> 200  room cd7d23f3-…   (same room, not a second one)
POST /rooms/{id}/players/{bob}              -> 201
POST /rooms/{id}/players/{bob}              -> 409  already_joined
DELETE /rooms/{id}/players/{bob}            -> 204
DELETE /rooms/{id}/players/{bob}            -> 204  (idempotent)
POST /rooms/{id}/players/{alice}  as bob    -> 403  not_your_membership
```

### AC-P3.4 — concurrency is honest

```
POST …/moves  (no If-Match)                 -> 428  if_match_required
POST …/moves  If-Match: "3"  (stale)        -> 412  + the current player-scoped state and its ETag
POST …/moves  by the player not on turn     -> 409  not_your_turn
GET  …/games/1  If-None-Match: "4"          -> 304  (empty body)

two writers, same If-Match: "4", concurrently:
  -> 201
  -> 412                                          exactly one commits; the loser reconciles
```

### AC-P3.5 / AC-P3.6 — deadlines and session kill

A second login for a player seated in an active room, via the CLI against identity:

```
$ UNOARENA_SESSION=/tmp/ca2.json node dist/cli.js login --user alice-11678 --pass pw
login: ok (user=alice-11678)

room 27bcb538-… log, twelve seconds later:
  1 RoomCreated   2 PlayerJoined   3 PlayerJoined   4 GameStarted
  5 PlayerDisconnected
  6 TurnSkipped

  {"type":"PlayerDisconnected","playerId":"38b0424a-…",
   "reconnectionDeadline":"2026-08-10T15:36:42Z"}      # the 60-second window, opened

room-gameplay's own log:
  {"action":"session-invalidated","playerId":"38b0424a-…","rooms":2,"reason":"superseded"}
```

It reached the room over `identity.session-events` — the topic P2 already published to, consumed
here without either side changing the contract. The turn was alice's, so it was skipped rather than
left to stall. The lazy expiry of AC-P3.5 is covered by `DeadlinesTest` against a real Postgres with
an injected clock (F8), which is the only way to cross a 30- and a 60-second deadline without
sleeping through them.

### AC-P3.7 — a full casual game through two CLI processes

Two accounts registered through the CLI against identity on `:30080`, then
`scripts/casual-drill.js` driving two `play --casual` processes against room-gameplay on `:30081`.
The harness reads the boards back out of the CLI's own output and types into it; it never calls the
API itself.

```
[alice] -- room 110fee8b - game 1 - seq 6
[alice]    discard G5  color GREEN  ▸  deck 92
[alice]    players   52fe0358 8
[alice]    your hand (7):
[alice]       1) B3      2) BSKIP   3) R6      4) G1*     5) RREV    6) Y6      7) B7
[alice]    YOUR TURN: play <n> [R|G|B|Y] | draw | uno | challenge | state | quit
[alice] > play 4
...
[bob]    down to your last two - play it as 'play <n> uno' or an opponent can catch you
...
[alice]    52fe0358 is on one card - 'challenge' while the window is open
[alice] > challenge
[alice] challenge_uno: ok (user=38b0424a-…)
[alice]   52fe0358 drew 2
...
[alice] > play 1
[alice] play_card: ok (user=38b0424a-…)
[alice]   you played Y5
[alice]   game over - you win!
[bob]   38b0424a played Y5
[bob]   game over - 38b0424a wins

== game completed. observed: wild, draw, uno, challenge, completed
```

Cards print in the canonical §5.F notation (`B3`, `BSKIP`, `RREV`, `Y+2`, `WILD+4`) because that is
the notation the backend sends — the CLI does not translate. The `*` marks come from the server's
own legality check.

The log is the authority:

```
$ psql -U postgres -d room_gameplay -tAc "select type, count(*) from room_events where room_id='110fee8b-…' group by 1"
CardPlayed 97   CardDrawn 46   TurnPassed 30   TurnSkipped 27   ForcedDraw 12
DirectionReversed 8   ChallengeWindowOpened 5   ChallengeWindowClosed 5
UnoChallengeIssued 4   UnoChallengeResolved 4   UnoCallMade 1
RoomCreated 1   PlayerJoined 2   GameStarted 1   GameCompleted 1   RoomCompleted 1

$ … order by sequence_number desc limit 3
245 RoomCompleted
244 GameCompleted
243 CardPlayed

$ … where type='UnoChallengeResolved' limit 1
{"challengerId":"38b0424a-…","targetPlayerId":"52fe0358-…",
 "challengeSucceeded":true,"penaltyPlayerId":"52fe0358-…","penaltyCardCount":2}

$ … where type='CardPlayed' and payload->>'chosenColor' is not null limit 2
WILD -> RED
WILD+4 -> RED

$ … GameCompleted: finishingOrder[0] = 38b0424a-…, isAbandoned = false
```

### Log before broadcast, and the privacy boundary

```
$ psql -U postgres -d room_gameplay -tAc "select
    (select count(*) from room_events), (select count(*) from outbox),
    (select count(*) from outbox where payload::text like '%seed%'),
    (select count(*) from outbox where published_at is null)"

events=355   outbox=355   leaked_seeds=0   unpublished=355
```

One outbox row per event, written in the same transaction — and **no seed reaches the outbox**, so
the deck order never leaves the service. Every row is unpublished because nothing drains the outbox
until P5's relay; that is the seam, not a gap.


### Re-run against exactly what `main` ships

The drill above ran against the image built from `e83e945`; the review pass then made one
behaviour-neutral change (`Outcome.Stale` no longer takes a parameter it could never carry). Rather
than claim the drilled image and the shipped one are the same, the cluster was re-pointed at `main`
and the game replayed against the digest `main` actually pins:

```
$ TARGET_REVISION=main gitops/bootstrap/install.sh          # idempotent re-run
$ kubectl -n unoarena-staging get pod -l app=room-gameplay -o jsonpath='{…imageID}'
…/unoarena/room-gameplay@sha256:5fe45c32bae5bd57be39277983a6b79f90ba2f018c5e0c42b5acf256b239d42c
$ kubectl -n argocd get applications room-gameplay-staging identity-staging
room-gameplay-staging   Synced   Healthy
identity-staging        Synced   Healthy

$ node scripts/casual-drill.js /tmp/ma.json /tmp/mb.json
== game completed. observed: wild, draw, uno, challenge, completed

room 0aca1b29-… :
  359 RoomCompleted
  358 GameCompleted
  3 successful Uno! challenges

events=844  outbox=844  leaked_seeds=0
```

Same result on the shipped digest: a full casual game to a winner, one outbox row per event, and no
seed leaving the service.

### AC-P3.9 — pipeline unchanged in shape and green

`main` pipeline `2747977775`: **34 success, 2 manual gates, 0 failed**. Stage list identical to
P2's (`test`, `build`, `deliver`, `deploy-staging`, `integration-staging`, `deliver-production`,
`deploy-production`); no new stage and no new job kind — `deploy-staging:room-gameplay` is the same
job P0 declared, with its manual gate removed now that the service is real.
`integration-staging:identity` is green, so the CLI refactor did not disturb the auth smoke.

Two pipeline defects were fixed to get there, both of which only appear once there are **two**
fully-wired services:

- **The digest pins raced.** Every wired service pins in the same stage, so the two pushes to
  `main` landed milliseconds apart and the loser was rejected with `cannot lock ref … incorrect old
  value`. The pin now re-reads the head the winner wrote and retries, and still fails loudly if it
  loses five times — a pin that silently does not land would leave the overlay pointing at an older
  image than the pipeline just tested.
- **`tournament` had the same kaniko/Kotlin-daemon bug** as room-gameplay, latent until a
  `ci/templates/**` change pulled every service into the pipeline and rebuilt it. Both Kotlin
  images carry the fix now.


### Review pass (2026-08-10)

The branch was read back end to end as if it were someone else's PR. Net **-36 lines**, two real
defects fixed, and one flaky test in P2 code that P3's pipeline exposed:

| Finding | Verdict |
|---|---|
| `sweepIdempotencyKeys` defined and tested but never called | **Bug.** The table grew unbounded despite D5's 24-hour retention. Now runs at startup and daily. |
| `countBusiness` missing on `DELETE`/`PATCH` | **Bug.** Leaving a two-player game ends it (invariant 7); `games_completed_total` — a P8 business metric — never counted that path. Fixed, with a test that fails without the fix. |
| `play --room <id>` used the casual convergence logic | **Bug.** Could move a player out of the room they named. Convergence is now casual-only. |
| identity's `not.toContain("pw")` on a base64 hash | **Flaky, 3.5% of runs** (measured over 5000 hashes). Took `main` red on a pipeline that changed nothing in identity. |
| `POST /rooms` routed through `submit`'s retry loop | **Overengineering.** A fresh id cannot lose a sequence race; three futile attempts reported as `Stale`. Replaced by `Rooms.create`. |
| Repeated membership guard, `ETag` construction, JSON log writers, test fixtures | **Duplication.** One helper each; the consumer's log writer also did not escape its input. |

`main` pipeline `2748157103` after all of it: **34 success, 1 manual, 0 failed**, same stage list.
115 → 116 Kotlin tests (the new regression test), 17 CLI tests, and a full two-process game replayed
against the refactored build: 242 events, outbox parity, no seed leaked.
