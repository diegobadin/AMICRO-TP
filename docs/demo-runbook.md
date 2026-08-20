# Demo runbook — the final delivery

> The script for the exam. `docs/final/consigna.md` asks for an empty Kubernetes cluster deployed
> into a running system with observability and at least three business metrics, and a functional
> test driven through the Client CLI. This is the sequence that does that, in order, with the
> waits named and a fallback for every step that can go wrong.
>
> The operational half of the observability story is
> [`observability-runbook.md`](./observability-runbook.md) — the boards, the LogQL query, and the
> readings that look like faults and are not. This file does not repeat it; it says when to open it.

**Timings marked _(measured)_ come from a rehearsal. Timings marked _(unmeasured)_ do not exist
yet** — R0 and R1 fill them in, and nothing here should be read aloud as a number until it does.

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
- [ ] **EKS cluster created and empty**, per §2.
- [ ] **NodePorts authorized from the presenting machine's address** — §2, and it is almost
      certainly a different address than the rehearsal used.
- [ ] The deck renders: `presentation/`.

## 2. Before the slot — the cluster exists and is empty

The consigna says *"arrancar de un cluster de k8s vacío"* — an **empty** cluster, not a nonexistent
one. Creating it live costs **16m58s** _(measured, P1)_ of `eksctl` output before anything can be
shown, so the cluster is created ahead of the slot and the demo starts at `install.sh`. Cluster
creation is still ours and still demonstrable: `gitops/bootstrap/eks/create.sh` is in the repo and
its rehearsal log is the evidence.

```bash
cd gitops/bootstrap/eks
./create.sh                      # ~17 min; also opens 30080/30081 for this machine's address
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
| **3:06** _(R0)_ | `install.sh` returns | The script is done; the cluster is not. What is running now is Argo reconciling git. |
| → | `kubectl get app -n argocd -w` | Sync waves: secrets at −1, then operators, then the stateful set — Kafka, Postgres, Redis — then the ten services. The schema-owning services **restart 5–7 times here and that is correct**: they exit rather than serve against a database that is not migrated yet. R0 measured **7** for each of the five, and **0** for `analytics-api`, which reads a schema it does not own and so connects lazily. |
| **17:48** _(R0, kind)_ | **24/24 Synced/Healthy** | Twenty-four Argo applications: the ten services, twelve platform components, and the two app-of-apps roots that own them. |

> **Treat that as a range, not a budget.** R0 spent **798 s of its 1068 s pulling images** — 41 pulls
> into a node that has just been created. The number is bound by registry throughput, not by
> Kubernetes: P8 measured 12 m 25 s for the same 24 apps, and the difference between the two runs is
> network, not work. **EKS has no measurement at all yet** (P1's "~8 min" was a platform-only
> install), and its nodes are always brand new. Say "somewhere between twelve and twenty minutes,
> mostly image pulls" rather than a figure — and start the narration early.
>
> R0 recorded **zero** `Insufficient cpu/memory` events: the requests added in P9 cost nothing on a
> single kind node. The ten `FailedScheduling` events it did record are all the node's own
> not-ready taint in the first seconds, before the kubelet registers.

```bash
kubectl get app -n argocd            # 24/24 Synced Healthy
kubectl get pod -n unoarena-staging  # 11 containers — the outbox relay runs twice
```

**The EKS number does not exist yet.** P1's "~8 min convergence" was a platform-only install; this
is 24 apps on 2× `t3.large`. R1 measures it.

## 4. The functional pass — the faculty's CLI

This is the surface `Client-Checkpoint.md` §5 defines, driven the way the faculty would drive it,
not the way our drill scripts do.

**Who types what.** The casual game below is *interactive* — two people, two terminals — and that
half cannot be rehearsed headlessly. R0 exercised it with `bot --casual` in both seats (54 and 60
actions, 0 errors, one winner) and verified the rest of the surface directly. Rehearse the
interactive form with a human before the day; it is the only step in this runbook that has not been.

```bash
cd clients/cli && npm install && npm run build
export UNOARENA_API_URL=http://<node-ip>:30080     # or http://localhost:30080 with the fallback
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
UNOARENA_SESSION=/tmp/c.json node dist/cli.js spectate --room <roomId>
```

**A tournament (§5.E)** — four terminals, one command each:

```bash
for i in 1 2 3 4; do
  UNOARENA_SESSION=/tmp/t$i.json node dist/cli.js tournament register &
done
```

They converge on the lowest open tournament id, so four processes started together join one event.
Nothing else is typed: rooms are provisioned round by round, matches are best-of-three, survivors
are reseeded, and it ends with one champion.

> **Two things R0 learned here.**
>
> **Start from a cluster with no open tournaments, or name the id.** A tournament left in
> `REGISTRATION` by an earlier run absorbs the next player who registers, and the event then starts
> with a roster that includes clients nobody is running. On a from-empty demo this cannot happen; on
> a second run against the same cluster it will. The safe form is explicit:
> `tournament register <id>` / `bot --tournament <id>` against a tournament created for the demo.
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

**If a reading looks wrong, open [`observability-runbook.md`](./observability-runbook.md) §"Things
that look like faults and are not"** rather than debugging live. The short list: the `Watchdog`
alert always fires by design, schema-owning services restart 5–7× on a cold start, `ranking`
logging `outcome: ignored` is correct, empty tournaments in `REGISTRATION` are clients that lost a
create race, and a `0` on a business panel is a real zero.

**Say the gaps before being asked.** Seven of the nine alert rules have never been observed firing
and are listed as untested; there is no tracing backend; there are no alert receivers. All three
are recorded in `specs/2026-08-18-p8-observability/ESTADO-FINAL.md`.

## 6. Degrade branches

Each of these is a decision made now, not on the day.

| If | Then | Cost |
|---|---|---|
| **The NodePorts do not answer** (SG, VPC, or a changed address) | Two port-forwards, and say why: `kubectl -n unoarena-staging port-forward svc/gateway 30080:80` and `kubectl -n monitoring port-forward svc/monitoring-grafana 30081:80`. `UNOARENA_API_URL=http://localhost:30080`. | Two processes to keep alive. Nothing else changes. |
| **The slot is running short** | Cut the tournament (§4's last block). The casual game, the boards and the Loki trace are the spine. | `Client-Checkpoint.md` §5.E marks tournament play mandatory **but degradable**. |
| **Convergence stalls below 24/24** | `kubectl get app -n argocd` for the app that is not Synced, then its pods. Do not `kubectl apply` anything — Argo owns it and `selfHeal` will undo you. | Open the async-spine board while diagnosing. |
| **A pod is Pending on insufficient CPU/memory** | The requests added in P9 do not fit this cluster. `kubectl -n unoarena-staging patch` is undone by Argo; the honest move is to say so and scale the nodegroup. | Should be impossible — R1 checks it — but it is the one new failure mode P9 introduced. |
| **Loki is down** | Skip the correlationId step and say Loki is deliberately **non-load-bearing**: all three boards and all nine alert rules still work, because they read Prometheus. P8 verified this by taking Loki away. | One demo step. |
| **The cluster is unrecoverable** | Fall back to kind: `gitops/bootstrap/install.sh` on the laptop, same script, same commit, ~12m25s _(measured)_. | The demo is local, and the EKS story becomes the rehearsal evidence. |

## 7. After

```bash
cd gitops/bootstrap/eks
./destroy.sh                     # ~10 min; drops stateful namespaces first to free EBS
./sweep.sh                       # MUST print nothing and exit 0
```

Billing continues while the lab is **off** for the control plane, EC2, EBS and any ELB. This step
is never optional, and the budget banner is checked after it.
