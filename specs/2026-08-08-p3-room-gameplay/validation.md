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
# Per-service credentials, like identity's
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

- [ ] AC-P3.1 … AC-P3.9 all green, with transcripts below.
- [ ] `engine/` has no framework dependency and its suites run without a database or a container.
- [ ] No player's hand appears in another player's game view, in any outbox payload, or in any log
      line — checked, not assumed.
- [ ] `clients/cli/README.md` maps every new command to its endpoint and states what is still
      missing (live feed via SSE, bot, reconciliation UX — all P4).
- [ ] Cards are printed in the canonical notation of Client-Checkpoint §5.F.
- [ ] `CHANGELOG-design.md` records the auto-start delta, `GET /rooms` as an additive read, the
      polling stand-in for SSE, the shared JWT secret, and the two-URL CLI shape.
- [ ] `ESTADO-FINAL.md` written and the north-star roadmap marks P3 **SHIPPED**.

## Phase gates

_(F1–F10 record their evidence here as they land.)_
