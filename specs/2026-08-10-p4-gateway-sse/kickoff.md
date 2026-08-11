# Kickoff — P4: Gateway + Realtime Fan-out (SSE)

> **Spent.** F8 landed on 2026-08-11 and the phase is closed — see
> [`ESTADO-FINAL.md`](./ESTADO-FINAL.md). Kept as written because the local-stack recipe and the
> trap list below are still the fastest way to work on this code, and because the four F8 steps it
> sequenced are exactly what F8 did. One correction it earned: step 1 said "get the gateway image
> built", and **room-gameplay needed rebuilding too** — it was pinned to a `main`-built image with
> no publisher and the old JWT-validating `Auth.kt`. A phase that changes two services has to
> rebuild both.
>
> Written 2026-08-10 with the triad, rewritten as F0–F6 landed, and again on 2026-08-11 when F7
> did. This is the bridge between sessions: what is done, what is left, and everything the next
> session needs that is not already in `requirements.md` / `plan.md` / `validation.md`.

## Where things stand

Branch `feat/p4-gateway-sse`, **not pushed**. `main` is still `ea1ab15` (P3), so
`git log --oneline ea1ab15..HEAD` is exactly the phase.

| Phase | State |
|-------|-------|
| F0 triad | done — `123152d` |
| F1 gateway made real | done — `58465ce` (route table, HS256 validation, header injection, `/metrics`, chart, CI, `gateway-secrets`) |
| F2 proxy + session kill | done — `1b9fa94` |
| F3 publisher | done — `1c1f16c` (`Streams.kt`, entry id = `sequenceNumber`, 6 h TTL, failure counter) |
| F4 SSE endpoint | done — `a030e28` (replay, `resync`, heartbeat, `session-invalidated`) |
| F6 the collapse | done — `b39d6ae` — **landed before F5**, see the plan for why |
| F5 CLI on the stream | done — `0da0926` |
| review pass | done — `ec0944a`, recorded in `plan.md` |
| F7 `bot --casual` | done — `30a1349` (`bot.ts`, `enterGame`, `scripts/bot-drill.js`; D13 refined in `plan.md`, gate in `validation.md`) |
| F7 review pass | done — `f319feb`, recorded in `plan.md` |
| **F8 drill + closure** | **next** |

Tests: gateway 44, CLI 46, room-gameplay 61 (+ `gradle check`). All green.

## What is left

**F8 — the drill and the closure**, in this order:

1. **Push the branch, and get the gateway image built.** Nothing else can happen first:
   `gitops/apps/gateway/overlays/staging/values.yaml` still says `digest: ""`, because the branch
   has never been pushed and CI has therefore never built or pinned a gateway image. Argo pulls from
   GitLab, so a drill cluster would come up with a gateway it cannot pull. The first push of a branch
   evaluates `rules:changes` as all-changed — `git push -o ci.skip` first, then a second push that
   touches only `services/gateway/**` so change detection runs the gateway's jobs alone. CI pushes
   the digest-pin commit back: `git pull --rebase` before anything local.
2. **The empty-cluster drill (AC-P4.9).** `kind delete cluster --name unoarena-staging` → then
   `TARGET_REVISION=feat/p4-gateway-sse GITOPS_REPO_TOKEN=$(cat ~/.amicro_gitlab_token)
   gitops/bootstrap/install.sh` → the probes in `validation.md` → a full casual game through the
   gateway with two CLI processes started at the same moment. Empty kind to all-Healthy is ~35 min
   on a cold image cache; the services crash-loop until CNPG elects a primary and then self-heal.
3. **The remaining ACs against that cluster:** the session-kill drill (AC-P4.7), the Redis-outage
   bite test (suspend **both** Argo apps first), `bot-drill.js 2` for AC-P4.8, and the mixed
   two-bots-plus-one-human run.
4. **Closure:** transcripts into `validation.md`, `README.md` §9 evidence, `ESTADO-FINAL.md`,
   roadmap marked **SHIPPED** with a handoff block for P5, then the FF merge to `main` and a green
   main pipeline (AC-P4.10).

Already written, so F8 does not have to: **`CHANGELOG-design.md` §9** (the P4 deltas, with
§8.9/§8.10 closed) and `clients/cli/README.md` (the bot section included).

The mixed case in AC-P4.8 — two bots and one human at one table — needs `ROOM_MIN_PLAYERS: "3"` in
`gitops/apps/room-gameplay/overlays/staging/values.yaml` while it runs: the backend starts the game
the moment the room reaches the minimum, so a third player can never join a two-player table. Put it
back to `"2"` afterwards, or AC-P4.9's two-process game will sit and wait.

## Running the whole thing locally

Faster than a cluster for anything that is not the empty-cluster drill, and it is how F3–F6 were
verified. Throwaway containers, three processes, one shared secret:

```bash
docker run -d --rm --name p4-pg -e POSTGRES_USER=room_gameplay -e POSTGRES_PASSWORD=test \
  -e POSTGRES_DB=room_gameplay -p 55432:5432 postgres:16-alpine
docker run -d --rm --name p4-redis -p 63790:6379 redis:7.4.10-alpine
docker exec p4-pg psql -U room_gameplay -c "create database identity"

# room-gameplay (takes ~45 s to compile and boot)
cd services/room-gameplay && DATABASE_HOST=localhost DATABASE_PORT=55432 \
  DATABASE_NAME=room_gameplay DATABASE_USER=room_gameplay ROOM_GAMEPLAY_DB_PASSWORD=test \
  REDIS_URL=redis://localhost:63790 PORT=8081 ROOM_MIN_PLAYERS=2 KAFKA_BROKERS=no-broker:9092 \
  ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process run

# identity
cd services/identity && DATABASE_HOST=localhost DATABASE_PORT=55432 DATABASE_NAME=identity \
  DATABASE_USER=room_gameplay IDENTITY_DB_PASSWORD=test IDENTITY_JWT_SECRET=local-p4-secret \
  REDIS_URL=redis://localhost:63790 KAFKA_BROKERS=no-broker:9092 PORT=8085 node dist/server.js

# gateway — the same secret identity signs with
cd services/gateway && PORT=8099 IDENTITY_URL=http://localhost:8085 \
  ROOM_GAMEPLAY_URL=http://localhost:8081 REDIS_URL=redis://localhost:63790 \
  IDENTITY_JWT_SECRET=local-p4-secret node dist/server.js

# then, from the repo root
export UNOARENA_API_URL=http://localhost:8099
node clients/cli/scripts/casual-drill.js /tmp/a.json /tmp/b.json   # after registering two players
node clients/cli/scripts/bot-drill.js 2                            # seeds its own accounts
```

`KAFKA_BROKERS=no-broker:9092` is deliberate: both services log a counted failure and keep serving,
which is the degradation P2 and P3 already prove. Kill the processes by port (`ss -lptn 'sport =
:8099'`) — a `pkill -f "dist/server.js"` also matches the container's own process.

The Kotlin suites need both throwaway containers:
`TEST_DATABASE_URL=jdbc:postgresql://localhost:55432/room_gameplay TEST_DATABASE_USER=room_gameplay
TEST_DATABASE_PASSWORD=test TEST_REDIS_URL=redis://localhost:63790 ./gradlew test`.

## Traps this phase already walked into

Each of these cost real time and is now guarded by a test — do not undo them by accident.

- **A Redis subscription made before the socket is ready never exists.** `psubscribe` on a client
  with `enableOfflineQueue: false` is rejected outright and the process serves on, deaf. Subscribe
  on `ready` (which re-fires after a reconnect), on a connection of its own.
- **Never render a board from a locally-applied state.** One command commits several events, so
  mid-batch the client legitimately believes it is its turn. That is how a player is handed a `409`.
- **The drill harness dedupes on the board's `seq`,** not on the trailing output block: feed lines
  grow the block under a turn prompt, and it re-types the same command.
- **Node holds response headers until the first body byte** — an SSE response needs
  `res.flushHeaders()` or a subscriber with nothing to replay leaves the client's `fetch` unresolved.
- **Jedis 8 has no `JedisPooled`** — it is `RedisClient.create(URI)` over `UnifiedJedis`. `javap` on
  the jar in `~/.gradle/caches` settles this kind of question faster than guessing.
- **`XADD` goes after the commit, never inside the transaction**, and publishes
  `publicPayload(event)` — the raw encoding leaks the RNG seed, which is the deck.
- **Start the two drill processes simultaneously**, never one after the other (P3's lesson, still
  true).
- **A drill needs a clean room list *and* no clients left over from the last one.** A run killed
  halfway leaves a `WAITING` room whose members are gone: the next `--casual` player joins it, the
  backend starts the game at `ROOM_MIN_PLAYERS`, and the turn parks on somebody who will never move
  — deadlines are only evaluated when a command arrives, and none does. Nothing recovers until P5's
  timer worker. A surviving *process* is worse: it keeps polling, joins the next run's room and
  strands a bot that then times out. Both read exactly like a code defect; they cost three
  false alarms in F7. `bot-drill.js` now kills its bots on `SIGINT`/`SIGTERM`, and locally
  `truncate room_events, outbox, rooms, idempotency_keys, consumed_events;` resets the board.
  (`pgrep -f` matches the shell running it — check with `ps -eo args | grep cli.js | grep -v grep`.)

## Working rules that still apply

- First push of the branch: `git push -o ci.skip`; CI pushes digest-pin commits back, so
  `git pull --rebase` before every local commit. Any change under `ci/templates/**` pulls all ten
  services into the pipeline.
- `test:room-gameplay` now needs a Redis service as well as Postgres — already in its CI fragment.
- Argo reverts fault injection within seconds; suspend **both** `unoarena-root` and the child app
  for the Redis-outage drill.
- No kind cluster recreation is needed for P4: the gateway took the already-published `30080`.
  On a *running* cluster the gateway's Service may fail to claim the port until identity's app syncs
  to `ClusterIP`; Argo retries. From empty there is no conflict at all.
