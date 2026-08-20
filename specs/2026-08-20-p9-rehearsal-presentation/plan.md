# Plan — P9: Demo rehearsal + presentation

Ordering rule for the whole phase: **everything that changes the running system lands first, then
the system freezes, then it is rehearsed.** A rehearsal against a build that changed afterwards is
not a rehearsal. Groups 1–2 change things; groups 4–5 must never be re-opened by a later group.

---

## 1. Pay the carried checklist's system-touching debts

The four items from E3 beyond the EKS one. They land together because they rebuild the same ten
services anyway, and because editing `ci/templates/**` pulls every service into one pipeline.

- **1.1 Mirror the two `gcr.io/distroless` bases into the project registry** (D5). The users are
  `gcr.io/distroless/nodejs20-debian12` (gateway, identity, spectator) and
  `gcr.io/distroless/static-debian12` (outbox-relay, timer-worker). Add a **manual** CI job that
  copies each by digest to `registry.gitlab.com/<group>/amicro-tp/base/<name>:<tag>`, run it once,
  then repoint the five `FROM` lines at the mirrored tag pinned by digest.
- **1.2 Verify the five builds now pull from the project registry**, and that a broken mirror
  reference fails the build rather than silently resolving elsewhere (bite test in `validation.md`).
- **1.3 Measure actual CPU/memory usage of the ten app containers** on the running kind cluster
  (`kubectl top pod -n unoarena-staging`) under load, not at idle — run the casual drill and a
  tournament first. Record the numbers in this file; they are the input for 1.4.
- **1.4 Add requests and limits to the ten charts** (D4: requests on CPU+memory, limits on memory
  only). The chart templates have no `resources` block at all today, so this is a values key plus a
  template line per chart. Sum the requests and compare against 2× `t3.large` allocatable **before**
  pushing — R2 in `requirements.md` is a scheduling risk, not a hypothetical.
- **1.5 Wire ktlint on `room-gameplay` and `tournament`** (D3), with `ktlintCheck` reachable from
  `gradle check`. Run `ktlintFormat`, review the diff as a diff (it will be mechanical; that is not
  a reason to skip reading it), and confirm both suites stay green.
- **1.6 Correct `tech-stack.md` §2**: detekt struck from both Kotlin rows with the reason, in the
  same shape §9 used when P8 superseded it — the table stops promising what the builds do not do.
- **1.7 Renew the `gitops-push-bot` CI token** (expires 2026-09-30). Creating it is the user's
  action; wiring the new value into the project's CI variables and confirming a pin lands is this
  group's.
- **1.8 Push and let the full pipeline run.** Expect all ten services to rebuild and pin. This run
  is also the first real test of 1.1: a distroless rejection here would be the seventh, and it
  should now be impossible.

**Done when:** one green pipeline on `feat/p9-rehearsal-presentation` has rebuilt and pinned all
ten services, the kind cluster is back to 24/24 Synced/Healthy on the new digests, no pod is
Pending, and `tech-stack.md` §2 matches what the builds actually run.

### 1.9 Results (2026-08-20) — **DONE except 1.7**

Pipeline **2776462081: success**, all ten services rebuilt and pinned, including the five now
building from the mirrored bases. The kind cluster was repointed at this branch and reached
**24/24 Synced/Healthy with no pod Pending**; two headless bots then played a full casual game,
**121 actions, 0 errors**.

**Measured peaks under load** (13 h of Prometheus history including P8's drill), and what was set:

| Service | peak mem | peak cpu | req cpu | req mem | limit mem | now |
|---|---|---|---|---|---|---|
| room-gameplay | 307 MiB | 21m | 100m | 384Mi | 768Mi | 24.5% |
| tournament | 219 MiB | 23m | 100m | 256Mi | 640Mi | 22.2% |
| identity | 140 MiB | 24m | 50m | 192Mi | 384Mi | 19.6% |
| gateway | 48 MiB | 8m | 50m | 96Mi | 256Mi | 13.9% |
| spectator | 66 MiB | 29m | 50m | 96Mi | 256Mi | 12.2% |
| ranking / analytics-workers / analytics-api | 41/35/28 MiB | ≤3m | 25m | 64Mi | 192Mi | 14–19% |
| outbox-relay ×2 / timer-worker | ≤18 MiB | ≤1m | 25m | 32Mi | 128Mi | 6–9% |

**Totals: 500m / 1312Mi requested, 3264Mi limited** across 11 containers. Platform requests today
are 2100m / 3356Mi, so the committed total becomes **2600m / 4668Mi** against roughly 3860m /
14100Mi allocatable on 2× `t3.large` — **67% CPU, 33% memory committed**. R2 is answered on paper;
**5.3 still has to answer it on EKS**, where `kube-system` differs.

**The landmine 1.4 nearly shipped.** Both Kotlin services ran with **no JVM heap flag**, so the JVM
sized `MaxHeapSize` from the node: **3.83 GiB** measured. A memory limit changes that input — under
a 768Mi limit the default 25% gives a **192 MiB** heap, *below* room-gameplay's measured 307 MiB
peak. Verified directly with `docker run -m 768m`: 192 MiB without the flag, 576 MiB with
`-XX:MaxRAMPercentage=75`. Live now: room-gameplay **576 MiB**, tournament **480 MiB**. A memory
limit on a JVM container is not a config value, it is an input to the runtime.

**1.7 (CI token) is not done** — creating it is the user's action.

---

## 2. Open the EKS NodePort path (E1)

- **2.1 Extend `create.sh`** to authorize TCP 30080 and 30081 on the nodegroup's shared security
  group, scoped to the operator's current public IP `/32` (D2). It must be idempotent — a second
  run cannot fail on `InvalidPermission.Duplicate`.
- **2.2 Print the two reachable URLs** at the end of `create.sh`, built from a node's public IP, so
  the operator does not assemble them by hand under pressure.
- **2.3 Add a re-authorize path** for a different network on the day — a small `authorize-ports.sh`
  or a documented `aws ec2 authorize-security-group-ingress` one-liner in the EKS README, since the
  exam will very likely run from a different IP than R1.
- **2.4 Confirm `destroy.sh` and `sweep.sh` are unaffected** — the rule lives on a security group
  eksctl owns and deletes with the stack; verify at R1 that `sweep.sh` still exits 0 and empty.
- **2.5 Write the fallback into the runbook**, not into a memory: the exact `kubectl port-forward`
  pair for gateway and Grafana, and the sentence that says why it is there.

**Done when:** `create.sh` applies the rule idempotently, and R1 proves both URLs answer from the
operator's laptop — or proves they do not, and the fallback becomes the plan with evidence behind it.

---

## 3. Write `docs/demo-runbook.md` (before R0, so R0 tests it)

- **3.1 Preconditions and the 48h-before checklist.** Repo link handed to the faculty, date
  coordinated, lab budget banner read, `~/.amicro_secrets.env` and the sealing key + cert present,
  CI token valid, `main` green, every overlay digest-pinned, EKS cluster created and empty, SG
  authorized from the presenting network.
- **3.2 The timed script**, T+0 at `install.sh`, with the reference timings from R0/R1 filled in
  after they run: install returns → Argo converges → 24/24 → CLI functional pass → business board
  → correlationId query → tournament → champion.
- **3.3 Put the narration where the wait is** (R5). Every step that reads a number states what is
  said while the scrape interval passes. A business counter is read **one scrape interval after**
  the last action that moves it; a correlationId trace after the consumers have polled. This is the
  script's shape, not a footnote.
- **3.4 A degrade branch per step.** Tournament cut to casual-only (D1); NodePort cut to
  port-forward (2.5); Loki down and the demo continues, because P8 required it to be non-load-
  bearing and verified it by taking it away.
- **3.5 The "looks like a fault" pointer** — one line linking `docs/observability-runbook.md`, not
  a second copy of it (D6). Add only what is demo-specific: schema-owning services restarting 5–7×
  on a cold start is the thing most likely to be seen live and misread.
- **3.6 The exact commands, copy-pasteable**, including the CLI functional pass as the faculty
  would drive it (`Client-Checkpoint.md` §5 surface) rather than as our drills drive it.

**Done when:** somebody who did not write it can follow it without improvising.

---

## 4. R0 — rehearsal on kind (shakedown)

- **4.1 `kind delete cluster`, then run the runbook verbatim from empty**, stopwatch running. Ask
  before deleting the cluster (standing instruction).
- **4.2 Record every deviation** — anything typed that the runbook did not say is a runbook defect.
- **4.3 Fix the runbook, not the operator's memory.** Fill in the real timings.
- **4.4 Run the degrade branches**: cut the tournament, and drive the demo through port-forward
  instead of NodePorts, so both paths have been walked at least once.
- **4.5 Second from-empty pass if 4.1's findings are about startup** — the standing rule; a
  cold-start fix confirmed on a warm cluster is confirmed under the one condition where it cannot
  appear.

**Done when:** the runbook ran end to end with no improvisation and carries real kind timings.

---

## 5. R1 — rehearsal on EKS (the roadmap's requirement)

**Needs the user's go-ahead and a live lab session before anything in this group runs.**

- **5.1 `create.sh`** with the 2.1 change. Record the time; confirm the SG rule and the printed URLs.
- **5.2 `install.sh`** against the EKS kubeconfig, then converge to 24/24. Record it — **this
  number does not exist yet for 24 apps on 2× t3.large.**
- **5.3 Check scheduling before anything else**: no pod Pending on insufficient CPU/memory. If any
  is, R2's escape hatches apply, in order — third node, then drop the requests.
- **5.4 Run the runbook verbatim**, including the CLI functional pass, both NodePort URLs from the
  laptop, the business board, the correlationId query and a tournament.
- **5.5 `destroy.sh` then `sweep.sh`**, confirm empty and exit 0, and read the budget banner
  (it lags 8–12h, so the reading is a baseline for next time, not this run's cost).
- **5.6 Fold the EKS timings and every deviation back into the runbook.**

**Done when:** the demo has been performed once, start to finish, on a cluster created that day, and
the runbook's numbers are AWS numbers rather than kind numbers.

---

## 6. The presentation deck (E4)

- **6.1 Structure** — the problem and the domain, the final architecture (ten deployables, one
  door, the log as authority, the async spine), the decisions worth defending (drawn from
  `docs/architecture/08-adrs.md` and `CHANGELOG-design.md`, not re-derived), the delivery pipeline
  and promotion by digest, observability and the three business metrics, and what we would do next.
- **6.2 Speaker notes carry the numbers** so the presenter never has to remember one: 24 apps,
  12m25s from empty, 11 scrape targets, 5 services on one correlationId, 43-job pipeline, 179
  events in a demo.
- **6.3 Include the honest gaps** — no tracing backend, seven alert rules never observed firing,
  no receivers. A deck that claims coverage the ESTADO-FINAL denies is a contradiction a grader can
  find in one hop.
- **6.4 Render to HTML and PDF**, commit the markdown source, and document the render command in
  `presentation/README.md`.

**Done when:** the teammate can present it cold, and every number on a slide has a source in the repo.

---

## 7. README final pass + the honesty ledger

- **7.1 Root `README.md` against `Client-Checkpoint.md` §9**: every canonical command and its
  invocation, each mapped to a backend endpoint, the seeding procedure, the tournament threshold,
  and any command not fully implemented stated plainly. Much of this already lives in
  `clients/cli/README.md` — link rather than duplicate, but §9 asks the *root* README to carry it.
- **7.2 Add the demo runbook and the deck** to the README's "Running the system" table.
- **7.3 Re-read the P8 additions** for anything that stopped being true after group 1.

**Done when:** every `Client-Checkpoint.md` §9 bullet has a home, and gaps are named rather than absent.

---

## 8. Freeze, review pass, closure

- **8.1 Freeze.** After R1, no change to service code, charts or CI unless a rehearsal found a
  defect — and any such change invalidates R1 until the affected path is re-walked.
- **8.2 Self-review pass**, aimed at the runbook and the deck (R6). Read the runbook as somebody
  who has never run it; read the deck as somebody who did not build the system. Its own table in
  this file, including a "not changed, and why" list.
- **8.3 Coordinate the exam date** with the faculty (user action) and record it in the runbook.
- **8.4 Close**: `ESTADO-FINAL.md`, `CHANGELOG-design.md` §14, roadmap markers, FF-merge to `main`
  after the user names the merge, branch deleted after checking `git log main..branch` for orphan
  pin commits, both Argo roots repointed at `main` **before** the branch goes.

**Done when:** P9 is closed the way P4–P8 were closed, and the roadmap has no unchecked phase left.

---

## What this plan deliberately does *not* include

- Any change to domain behaviour, events, schemas, topics or tables.
- Alert receivers, SLOs, a tracing backend, or persistent storage for observability.
- Firing the seven untested alert rules to promote them to "covered".
- Production overlays or prod promotion in the demo path.
- New CLI commands; documented gaps instead.
- A recording or asciinema of the demo.
- detekt (D3 strikes it from the table rather than wiring it).
