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

### 4.6 Results (2026-08-20) — **R0 DONE**

`install.sh` returned at **3 m 06 s**, **24/24 Synced/Healthy at 17 m 48 s** — against P8's 12 m 25 s
for the same 24 apps. The difference is **not** work and **not** P9's requests: R0 logged **zero**
`Insufficient cpu/memory` events, and **798 s of the 1068 s were image pulls** (41 of them, into a
node created that minute). The ten `FailedScheduling` events are the node's own not-ready taint in
the first seconds. **The convergence time is registry-bound and belongs in the runbook as a range.**

Everything documented as a not-a-fault reading held: the five schema-owning services restarted
exactly **7** times, `analytics-api` **0**.

Functional pass: `register`/`whoami` ok · two `bot --casual` played a full game (54 + 60 actions,
**0 errors**, one winner) · `spectate <roomId>` **in the canonical positional form** · a four-bot
tournament to a champion in two rounds via `bot --tournament <id>`, the form that used to play a
casual game instead · the three business metrics read **7 / 1 / 11** after one scrape interval ·
one `correlationId` returned **5 services** from Loki.

**R0's defect — the tournament create race.** Four clients started together, all found nothing open,
all created. The client that finished first could only see its own tournament when it re-read, so
three registered on one and one on another; neither reached the threshold of four and all four timed
out, with every individual call reporting `ok`. P3 and P7 each taught this lesson and the fix
carried it as far as "re-read after creating" — a single snapshot, which converges everybody except
the winner of the create race. The re-read now settles (two consecutive agreeing reads) before
choosing. Bite-checked: restoring the single re-read turns the new test red.

**Deviations found in the runbook**, all folded back: §3's timings were P8's rather than measured
here; §4's casual game is interactive and cannot be rehearsed headlessly (now stated, and it is the
one step still unrehearsed with a human); §4's tournament needed the warning that a leftover
`REGISTRATION` tournament absorbs the next player.

**Not done in R0:** the degrade branches (4.4) and the second from-empty pass (4.5). R0's defect was
*not* a startup defect, so 4.5's trigger did not fire.

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

### 5.7 Results (2026-08-20) — **R1 DONE, and E1 is proven**

Budget before: **$0.2 of $50**; account swept clean beforehand. Cluster created, demo performed,
torn down, swept. **≈55 minutes, ≈$0.30.**

| Step | R1 | For comparison |
|---|---|---|
| `create.sh` | **15 m 44 s** | P1: 16 m 58 s |
| `install.sh` returns | **2 m 35 s** | R0 on kind: 3 m 06 s |
| **24/24 Synced/Healthy** | **8 m 53 s** | R0 kind 17 m 48 s · P8 kind 12 m 25 s |
| `destroy.sh` | **10 m 22 s** | P1: 10 m 22 s |

**EKS is the fast one**, which retires the worry behind R2's timing half: convergence is bound by
registry throughput, and two EC2 nodes in `us-east-1` pull from `registry.gitlab.com` far faster
than one kind node on a domestic uplink, in parallel. The runbook now says *budget ten minutes*.

**E1 is proven, with a bite test.** Both NodePorts answered from the operator's laptop with **no
port-forward**, on **both** node public IPs (`200` on 30080 and 30081, ×2). Revoking the rule made
30080 return `http 000`; re-authorizing brought it back to `200`. A second `authorize-nodeports.sh`
run reported `already open` — idempotent as designed. This path had never run on AWS before today.

**R2 is answered on real hardware.** **Zero** `Insufficient cpu/memory` events, 11/11 pods Running.
The requests sized from kind measurements fit 2× `t3.large` with room; no third node needed.

**Functional pass, all on EKS:** `register`/`whoami` ok · casual game 21 + 17 actions, **0 errors**
(max latency ~500 ms — it is Buenos Aires to `us-east-1`, worth knowing before the day) ·
`spectate <roomId>` positional · **four `tournament register` clients converged on ONE tournament**
— the exact fresh-cluster scenario R0 split on, so the convergence fix is validated where it failed
— `COMPLETED`, a champion, 2 rounds, 4 registered, full placements · `tournament status <id>`
positional · **11/11 scrape targets up** · business metrics **4 / 1 / 7** after one scrape interval ·
one `correlationId` returning **5 services** from Loki.

**Teardown left one EBS volume behind** and `destroy.sh`'s own sweep deleted it (`vol-0a9f…`) — P1's
lesson still true, its mitigation still working. `sweep.sh` printed nothing, exit 0.

**Process failure worth recording:** the first teardown attempt never ran. The command began with
`pkill -f "port-forward …"`, which **matched and killed the shell running it** — the `pgrep -f`
trap in the drill notes, in its `pkill` form. `destroy.sh` was never reached and the cluster billed
an extra ~13 minutes before the empty log gave it away. **Never start a teardown command with a
`pkill -f` whose pattern appears in its own command line.**

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

### 8.5 Review pass (2026-08-20) — runbook and deck

Aimed where R6 said to aim it: the runbook read as somebody who has never run it, the deck as
somebody who did not build the system. **Eleven findings, all fixed.** The pass is now 6 for 6 at
finding the phase's worst defect in the work the phase existed to do.

| # | Finding | Why it mattered |
|---|---|---|
| 1 | **§4's tournament block could not run.** `tournament register` against `/tmp/t$i.json` — session files nothing had ever authenticated. Verified live: it exits `tournament_create: error (401)`. | The demo's headline step, dead on arrival, in the one part of the runbook R0 had exercised with `bot --user/--pass` instead of session files. Now seeds four accounts and logs each session in. |
| 2 | **The 48h checklist said "EKS cluster created and empty".** At ~$0.27/h that is **~$13 of a $50 budget** burned before the exam starts, and it contradicts the EKS README's own cost rules. | Budget exhaustion deactivates the account permanently. Now an explicit *do not create it yet*; creation is a same-day step. |
| 3 | `UNOARENA_API_URL`'s comment still said `localhost:30080` after the fallback moved to 18080. | §4 and §6 disagreed about the fallback in the same document. |
| 4 | §6's kind fallback still quoted **12m25s** after §3 of the same file had been updated to R0's 17m48s. | A stale number three screens from its own correction. |
| 5 | **The deck quoted 12m25s as *the* number**, in both a slide and its speaker notes. | R0 superseded it and showed the figure is registry-bound. Now a 12–18 min range in the deck, and `presentation/README.md` says *quote the range, not either figure*. |
| 6 | §3 said "the EKS number does not exist yet" **twice, three lines apart**. | Two copies of one statement is the shape that drifts. Deduplicated. |
| 7 | The header legend described a `(measured)`/`(unmeasured)` convention the file had stopped using. | A legend for a notation that is not there is worse than none. |
| 8 | `spectate --room <roomId>` in the runbook — the *non*-canonical form, contradicting D8 and the CLI README. | The runbook exists to be typed by the faculty; it should show what §5.D writes. |
| 9 | §4 said "they converge on the lowest open id"; the note under it said "name the id instead". | Instruction and advice contradicting each other. The note is now scoped to a re-run against a used cluster. |
| 10 | **`authorize-nodeports.sh` would have authorized `/32`** if the public-IP lookup failed: an assignment's command substitution does not trip `set -e`, so the failure would have surfaced later as an opaque AWS parameter error. | Guarded, with the failure named. |
| 11 | "11 containers" where it meant 11 pods. | Wrong noun in a line read aloud while pointing at `kubectl` output. |

**Not changed, and why.**

- **The mirror job's `check()` spanning two script lines** — GitLab runs a job's script entries in
  one shell, so the function survives. Not assumed: the job ran and printed both `pin as:` lines.
- **The 1,377-entry ktlint baseline** — deliberate (D3′), and recorded in `tech-stack.md` §2 rather
  than hidden. Reformatting instead would move 5,870 lines with no behavioural content.
- **No CPU limits** — D4. A CPU limit throttles exactly when a demo bursts.
- **Thirteen slides** — not trimmed. Each maps to something the consigna or the grading lens asks
  about, and the "what we did not do" slide is load-bearing for the honesty criterion.
- **The runbook is EKS-first with kind as a degrade branch** — N2 locks the exam to AWS; kind is the
  harness, not the delivery.
- **`docs/demo-runbook.md` still does not restate the observability runbook** (D6). Two copies of
  the "looks like a fault" list is the two-copies-of-one-rule bug in prose.

---

## What this plan deliberately does *not* include

- Any change to domain behaviour, events, schemas, topics or tables.
- Alert receivers, SLOs, a tracing backend, or persistent storage for observability.
- Firing the seven untested alert rules to promote them to "covered".
- Production overlays or prod promotion in the demo path.
- New CLI commands; documented gaps instead.
- A recording or asciinema of the demo.
- detekt (D3 strikes it from the table rather than wiring it).
