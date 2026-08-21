# Demo runbook — the final delivery

> The script for the exam. `docs/final/consigna.md` asks for an empty Kubernetes cluster deployed
> into a running system with observability and at least three business metrics, and a functional
> test driven through the Client CLI. This is the sequence that does that, in order, with the
> waits named and a fallback for every step that can go wrong.
>
> The operational half of the observability story is
> [`observability-runbook.md`](./observability-runbook.md) — the boards, the LogQL query, and the
> readings that look like faults and are not. This file does not repeat it; it says when to open it.

**Every timing here is tagged with the rehearsal that produced it** — `_(R1)_` is P9's **EKS**
rehearsal and is the one that matters, `_(R0)_` its kind drill, `_(P1)_` the platform-only AWS run.
An untagged number would be a guess, and there are none.

---

## 1. Forty-eight hours before

The consigna asks for the repo link ~48h ahead so the faculty can read the documentation. That is
the deadline that matters; everything below hangs off it.

- [ ] **Exam date and time coordinated with the faculty**, and written here: `__________`
- [ ] **Repo link handed over** — `https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp`
- [ ] **`main` is green** and every staging overlay carries a real digest:
      `grep -L 'digest: "sha256:' gitops/apps/*/overlays/staging/values.yaml` prints nothing.
- [ ] **`gitops-push-bot` CI token still valid** (expires **2026-09-30** — renew if the exam is near it).
- [ ] **Secrets present on the presenting machine**: `~/.amicro_secrets.env`,
      `~/.amicro_sealing_key` **and** `~/.amicro_sealing_cert.pem`. Without the cert half, every
      committed SealedSecret stays sealed forever on a cluster that has never existed.
- [ ] **Learner Lab budget banner read** (lags 8–12h) — a deactivated account is permanent.
- [ ] **Do NOT create the EKS cluster yet.** It bills ~$0.27/h from the moment it exists — two days
      early is ~$13 of a $50 budget, for nothing. Creating it is a **same-day** step (§2).
- [ ] `gitops/bootstrap/eks/` reviewed and the lab credentials known-good, so §2 is uneventful.
- [ ] The deck renders: `presentation/`.

## 2. Before the slot — the cluster exists and is empty

The consigna says *"arrancar de un cluster de k8s vacío"* — an **empty** cluster, not a nonexistent
one. Creating it live costs **15m44s** _(R1; P1 measured 16m58s)_ of `eksctl` output before anything
can be shown, so the cluster is created ahead of the slot and the demo starts at `install.sh`. Cluster
creation is still ours and still demonstrable: `gitops/bootstrap/eks/create.sh` is in the repo and
its rehearsal log is the evidence.

```bash
cd gitops/bootstrap/eks
./create.sh                      # 15m44s (R1); also opens 30080/30081 for this machine's address
export KUBECONFIG=~/.kube/unoarena-eks
kubectl get nodes                # 2 Ready
kubectl get ns                   # nothing of ours — this is the "empty cluster"
```

If the presenting machine is not the one that ran `create.sh`:

```bash
./authorize-nodeports.sh         # prints the two URLs
```

## 3. The demo, T+0 at `install.sh`

```bash
export KUBECONFIG=~/.kube/unoarena-eks
GITOPS_REPO_TOKEN=<PAT with read_repository> USE_KIND=false gitops/bootstrap/install.sh
```

| T | What | Say while it happens |
|---|---|---|
| 0:00 | `install.sh` starts | One command, and it is the same one on kind and on EKS. It installs Argo CD and the Sealed Secrets controller, then applies **two** app-of-apps — platform and services — and stops. Nothing after this point is `kubectl apply`. |
| **2:35** _(R1)_ | `install.sh` returns | The script is done; the cluster is not. What is running now is Argo reconciling git. |
| → | `kubectl get app -n argocd -w` | Sync waves: secrets at −1, then operators, then the stateful set — Kafka, Postgres, Redis — then the ten services. The schema-owning services **restart 5–7 times here and that is correct**: they exit rather than serve against a database that is not migrated yet. R0 measured **7** for each of the five, and **0** for `analytics-api`, which reads a schema it does not own and so connects lazily. |
| **8:53** _(R1, EKS)_ | **24/24 Synced/Healthy** | Twenty-four Argo applications: the ten services, twelve platform components, and the two app-of-apps roots that own them. |

> **EKS is the fast one, and the reason is image pulls.** R1 reached 24/24 in **8 m 53 s**, against
> R0's 17 m 48 s on kind and P8's 12 m 25 s. Convergence is bound by registry throughput, not by
> Kubernetes — R0 spent **798 of its 1068 seconds** pulling — and two EC2 nodes in `us-east-1`
> pull from `registry.gitlab.com` far faster than one kind node behind a domestic uplink, and
> pull in parallel. **Budget ten minutes and be pleased**; if the venue's network is poor the
> number moves, so keep the narration ready rather than the stopwatch.
>
> **Nothing went Pending on resources**, on either rehearsal. R1 logged **zero**
> `Insufficient cpu/memory` events with all 11 pods Running, which answers on real hardware the one
> risk P9's requests introduced. R0's ten `FailedScheduling` events were the node's own not-ready
> taint in the first seconds, before the kubelet registers.

```bash
kubectl get app -n argocd            # 24/24 Synced Healthy
kubectl get pod -n unoarena-staging  # 11 pods — the outbox relay runs twice
```

## 4. The functional pass — the faculty's CLI

This is the surface `Client-Checkpoint.md` §5 defines, driven the way the faculty would drive it,
not the way our drill scripts do.

**Who types what.** The casual game below is *interactive* — two people, two terminals — and that
half cannot be rehearsed headlessly. R0 and R1 exercised it with `bot --casual` in both seats;
**the first two-human run was P9's, and it failed**, which is why the paragraph below exists.

> **The clock is real, and it is 30 seconds.** `TURN_TIMEOUT_SECONDS=30` with
> `IDLE_TIMEOUTS_BEFORE_FORFEIT=3` (`gitops/apps/room-gameplay/overlays/staging/values.yaml`). Three
> lapsed turns and the player **forfeits**. In P9's first two-person game both players spent their
> turns discovering the syntax, and it ended in a double forfeit with **no card ever played** — the
> rules working exactly as designed, and a demo nobody would want to watch.
>
> Two things follow. **Play the numbered card by typing its number** — `5`, not `play 5`; both
> players reached for the bare number, and since P9 that is what it means. `d`, `u`, `c`, `s` and
> `q` are the short forms of draw, uno, challenge, state and quit.
>
> **And rehearse it once before the day.** If the pace still feels tight, raise
> `TURN_TIMEOUT_SECONDS` in the overlay **before** the demo — it is a committed value Argo syncs, so
> it is a prep step, not something to change live. The overlay says as much next to it.

```bash
cd clients/cli && npm install && npm run build
export UNOARENA_API_URL=http://<node-ip>:30080     # or http://localhost:18080 on the §6 fallback
```

**Authentication (§5.A)** — one player, then the single-active-session rule:

```bash
UNOARENA_SESSION=/tmp/a.json node dist/cli.js register --user alice --pass pw
UNOARENA_SESSION=/tmp/a.json node dist/cli.js whoami
```

**A casual game (§5.B/§5.C)** — two terminals, started together:

```bash
UNOARENA_SESSION=/tmp/a.json node dist/cli.js play --casual    # terminal 1
UNOARENA_SESSION=/tmp/b.json node dist/cli.js register --user bob --pass pw
UNOARENA_SESSION=/tmp/b.json node dist/cli.js play --casual    # terminal 2
```

The game auto-starts at `ROOM_MIN_PLAYERS`. Worth saying out loud while playing: the board only
ever renders from a state the server sent, the playable cards are marked by the **server's** own
legality check, and only legal actions are offered — so the client cannot teach anyone to expect a
409.

**A spectator sees no hand (§5.D)** — from a third session:

```bash
UNOARENA_SESSION=/tmp/c.json node dist/cli.js spectate <roomId>
```

**A tournament (§5.E)** — four terminals, one command each:

Four accounts first — a session file holds a token, and an unauthenticated one stops at
`log in first`:

```bash
node dist/cli.js seed --count 4 --prefix t --json      # ensures t-1 … t-4, password t-pw
```

Then one terminal per player, started together:

```bash
UNOARENA_SESSION=/tmp/t$i.json node dist/cli.js login --user t-$i --pass t-pw
UNOARENA_SESSION=/tmp/t$i.json node dist/cli.js tournament register
```

They converge on the lowest open tournament id, so four processes started together join one event.
Nothing else is typed: rooms are provisioned round by round, matches are best-of-three, survivors
are reseeded, and it ends with one champion.

> **Two things R0 learned here.**
>
> **The bare form above is correct on the demo's from-empty cluster, and only there.** A tournament
> left in `REGISTRATION` by an earlier run absorbs the next player who registers, and the event then
> starts with a roster full of clients nobody is running. So if the demo is ever re-run against a
> cluster that has already served one, name the id instead — `tournament register <id>` — after
> reading `tournament status` to see what is already open.
>
> **R0 hit the create race and P9 fixed it.** Four clients starting together all found nothing open
> and all created; the one that finished first could only see *its own* tournament when it re-read,
> so three registered on one and one on another, the threshold of four was never reached, and every
> client timed out with nothing reporting a problem. The re-read now settles before choosing. The
> re-run played to a champion in two rounds — `actions` 86/22/175/117, all four `ok`.

## 5. Observability — and where the waits go

**Read a business counter one scrape interval after the last action that moves it.** Prometheus
scrapes every 30 s and the consumers poll; a counter read immediately after a game ends is being
measured in flight. This bit twice in one P8 drill and both times it looked like a defect — a
champion declared while `tournament_tournaments_completed_total` still read 0, and a correlationId
trace showing three services that was five a minute later. **Put the narration here, not the
number.**

| Order | Do | Say while the scrape lands |
|---|---|---|
| 1 | Open `/d/unoarena-business` | The three metrics the consigna asks for are named on the board itself, in a text panel — games completed, tournaments completed, players registered. Each is counted from a **committed domain event**, not from the request that appeared to cause it, so a game that ends by forfeit still counts. |
| 2 | *Now* read the numbers | They moved because the events were committed, not because the HTTP call returned. |
| 3 | Paste a `correlationId` into Explore → Loki | Every CLI command prints one. `{namespace="unoarena-staging"} \|= "<id>"` returns **five services** for one `POST /rooms`: the gateway that routed it, the aggregate that owned it, and all three read models that projected the event it produced. |
| 4 | `/d/unoarena-golden-signals` | Latency, traffic, errors, saturation. 4xx here is the rules working — a 409 is an illegal move refused. 5xx is the panel to watch. |
| 5 | `/d/unoarena-async-spine` | Both relays, six consumer groups, the reconciler, the timer worker, and which alerts are firing. The board to open if the demo stalls. |

Grafana: `http://<node-ip>:30081`, `admin` / `GRAFANA_ADMIN_PASSWORD` from `~/.amicro_secrets.env`.

**Either node's public IP works.** A NodePort answers on every node and kube-proxy routes to the pod
wherever it landed — R1 verified both, `200` on both ports from both IPs. `authorize-nodeports.sh`
prints only the first, so if that instance is ever replaced, re-run it for the current address.

**If a reading looks wrong, open [`observability-runbook.md`](./observability-runbook.md) §"Things
that look like faults and are not"** rather than debugging live. The short list: the `Watchdog`
alert always fires by design, schema-owning services restart 5–7× on a cold start, `ranking`
logging `outcome: ignored` is correct, and empty tournaments in `REGISTRATION` are clients that lost
a create race.

> **The one reading that can lie.** Business counters are **in-process**: a container restart resets
> them to 0 while the fact stays in the database. P9 measured exactly that on kind —
> `tournament_tournaments_completed_total` at **0** with `COMPLETED|1` in Postgres, because every pod
> had restarted after the tournament ran. On a demo this only bites if something restarts between
> the action and the reading, but if the headline board disagrees with what the room just watched,
> **check restart counts before believing the board**. The log is the authority; the counter is one
> process's lifetime.

**Say the gaps before being asked.** Seven of the nine alert rules have never been observed firing
and are listed as untested; there is no tracing backend; there are no alert receivers. All three
are recorded in `specs/2026-08-18-p8-observability/ESTADO-FINAL.md`.

## 6. Degrade branches

Each of these is a decision made now, not on the day.

| If | Then | Cost |
|---|---|---|
| **The NodePorts do not answer** (SG, VPC, or a changed address) | Two port-forwards on **different local ports**, and say why: `kubectl -n unoarena-staging port-forward svc/gateway 18080:80` and `kubectl -n monitoring port-forward svc/monitoring-grafana 18081:80`, then `UNOARENA_API_URL=http://localhost:18080` and Grafana on `http://localhost:18081`. | Two processes to keep alive. Nothing else changes. |
| **The slot is running short** | Cut the tournament (§4's last block). The casual game, the boards and the Loki trace are the spine. **Say that `tournament_tournaments_completed_total` therefore reads 0** — it is one of the consigna's three business metrics, and an unexplained zero on the board it is named on reads as a broken metric rather than a skipped step. **No alert fires because of the cut**: P9 verified that the idle tournament relay keeps reading its outbox (`rate ≈ 0.88/s` with nothing to drain) and that `tournament_consumer_starts_total` is 1 from boot, so the two liveness rules that could plausibly have fired stay quiet. The other seven key on errors or lag, which need activity to trigger. | `Client-Checkpoint.md` §5.E marks tournament play mandatory **but degradable**. |
| **Convergence stalls below 24/24** | `kubectl get app -n argocd` for the app that is not Synced, then its pods. Do not `kubectl apply` anything — Argo owns it and `selfHeal` will undo you. | Open the async-spine board while diagnosing. |
| **A pod is Pending on insufficient CPU/memory** | The requests added in P9 do not fit this cluster. `kubectl -n unoarena-staging patch` is undone by Argo; the honest move is to say so and scale the nodegroup. | Should be impossible — R1 checks it — but it is the one new failure mode P9 introduced. |
| **Loki is down** | Skip the correlationId step and say Loki is deliberately **non-load-bearing**: all three boards and all nine alert rules still work, because they read Prometheus. P8 verified this by taking Loki away. | One demo step. |
| **The cluster is unrecoverable** | Fall back to kind: `gitops/bootstrap/install.sh` on the laptop, same script, same commit, **17m48s** _(R0)_ — and pull-bound, so budget the range in §3. | The demo is local, and the EKS story becomes the rehearsal evidence. |

> **Why 18080 and not 30080.** R0 walked this branch and found the obvious spelling is a trap on
> kind: the node already publishes 30080/30081 on `0.0.0.0`, so `port-forward … 30080:80` binds
> **only `[::1]`** — it prints one `Forwarding from` line instead of two, and `localhost:30080` may
> then reach the real NodePort rather than the forward. The fallback appears to work while
> bypassing itself, which is the one thing a fallback must never do. Distinct ports are unambiguous
> on kind and on EKS alike; both were verified answering `200`.

## 7. After

```bash
cd gitops/bootstrap/eks
./destroy.sh                     # 10m22s (R1); drops stateful namespaces first to free EBS
./sweep.sh                       # MUST print nothing and exit 0
```

Billing continues while the lab is **off** for the control plane, EC2, EBS and any ELB. This step
is never optional, and the budget banner is checked after it.

R1's teardown **did** leave one EBS volume behind — namespace deletion races the stack teardown —
and `destroy.sh`'s own sweep deleted it (`vol-0a9f…`). That is the P1 lesson still being true, and
the mitigation still working. `sweep.sh` then printed nothing and exited 0. **A full R1 cycle is
about 55 minutes and roughly $0.30.**
