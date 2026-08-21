# ESTADO FINAL — P9: Demo rehearsal + presentation

> **The exam has been performed once, on the real target.** R1 created an EKS cluster from nothing,
> installed the whole system into it in **8 minutes 53 seconds**, drove the faculty's own CLI
> through a casual game and a four-player tournament, showed the three business metrics and a
> five-service log trace, answered on **both NodePorts from the operator's laptop with no
> port-forward**, and tore itself down to an empty account for about **$0.30**.
>
> P0–P8 built the system. P9 is the phase that proved somebody can perform it.

## What shipped

- **`docs/demo-runbook.md`** — the script: a 48h checklist, the timed sequence from an empty
  cluster, the narration placed *where the scrape interval is*, and a degrade branch for every step
  that can fail. Every timing in it is tagged with the rehearsal that produced it.
- **Two rehearsals.** R0 on kind and **R1 on a freshly created EKS cluster** — the roadmap's actual
  requirement for this phase.
- **`presentation/`** — a Marp deck in Spanish with speaker notes, rendered to HTML and PDF, whose
  every number has a named source in the repo.
- **The five carried checklist items**, four of them paid: the distroless mirror, resource
  requests/limits, ktlint, and EKS NodePort access. The CI token is the user's to create.
- **README** against `Client-Checkpoint.md` §9: every canonical command mapped to its backend
  operation, the seeding procedure, the tournament threshold, and the gaps stated.

## Evidence

`validation.md` carries it; `plan.md` §1.9, §4.6, §5.7 and §8.5 carry the numbers. The short version:

| | R1 (EKS) | R0 (kind) |
|---|---|---|
| cluster creation | 15 m 44 s | — |
| `install.sh` returns | 2 m 35 s | 3 m 06 s |
| **24/24 Synced/Healthy** | **8 m 53 s** | 17 m 48 s |
| teardown | 10 m 22 s | — |

**EKS converges faster than kind**, which is the opposite of the expectation and the most useful
thing R0 taught: convergence is bound by **image-pull throughput**, not by Kubernetes. R0 spent
**798 of its 1068 seconds** pulling 41 images into a node created that minute; two EC2 nodes in
`us-east-1` pull in parallel and far faster. The runbook now says *budget ten minutes*.

Also verified on R1: **zero** `Insufficient cpu/memory` events with 11/11 pods Running, 11/11 scrape
targets up, the three business metrics reading 4 / 1 / 7 one scrape interval after the last action,
one `correlationId` returning **5 services** from Loki, and a four-client tournament converging on a
single event and playing to a champion.

## What the rehearsals caught that nothing else did

**The faculty's own invocation played the wrong game.** `bot --tournament <id>` — the canonical form
in `Client-Checkpoint.md` §5.E — silently played a **casual** game, because `parseFlags` gives the
flag the id as its *value* and the dispatch tested `=== true`. `tournament status <id>` silently
reported on a **different** tournament. Both are the confidently-wrong shape P6 and P7 each found
once. Found by writing the functional pass against the faculty's document instead of our drills.

**The tournament create race, one layer deeper than P7 fixed it.** Four clients starting together
all create, and the one that finishes **first** can only see its own tournament in a single re-read
— so it registers there while the others converge elsewhere. Threshold never reached, every client
timed out, and every individual call returned `ok`. P3 and P7 both taught "converge on the lowest
id"; both fixes were one snapshot. R1 confirmed the settled re-read on a fresh cluster.

**A memory limit on a JVM container is an input to the runtime.** Both Kotlin services ran with no
heap flag, so `MaxHeapSize` came from the node — **3.83 GiB**. Adding a 768Mi limit alone would have
given room-gameplay a **192 MiB** heap, below its measured **307 MiB** peak: an OOMKill built into
the busiest service, by a change whose entire purpose was to make it safer.

**A business counter is one process's lifetime, not the fact.**
`tournament_tournaments_completed_total` read **0** while Postgres held `COMPLETED|1`, because every
pod had restarted after that tournament ran. The runbook's own claim that "a `0` on a business panel
is a real zero" was too strong and is now qualified.

**A numbered hand that would not take a number.** The one step no drill can cover: two humans, two
terminals. Both typed `5` at a numbered hand, got a usage line, and lost the turn to the 30-second
clock — the game ended in a **double forfeit with no card ever played**, with every rule working
exactly as designed. Fixed (14.11), and the runbook now names the clock and the lever.

**A fallback that bypasses itself.** The runbook's port-forward fallback, `port-forward svc/gateway
30080:80`, binds **only `[::1]`** on kind because the node already publishes `0.0.0.0:30080` — so
`localhost:30080` may reach the real NodePort instead. It appeared to work while not being under
test. Now 18080/18081.

**The review pass, 6 for 6.** Eleven findings, the worst two being a demo step that could never have
run (`tournament register` against session files nothing had authenticated — a 401) and a 48h
checklist that told you to create the EKS cluster two days early, **~$13 of a $50 budget**. Three
more were stale numbers that a *later* measurement in the same document had already contradicted.

## Decisions worth carrying forward

- **A rehearsal tests the document, not the system.** Every deviation is a defect in the runbook.
- **Run the exact lines, in order, from the document.** R0 "exercised" the tournament step with a
  different command and missed a 401 that would have stopped the demo.
- **When a rehearsal produces a number, grep the repo for the number it replaces.**
- **Never start a teardown command with `pkill -f` whose pattern is in its own command line** — it
  killed the shell running it, `destroy.sh` never ran, and an EKS cluster billed for another ~13
  minutes behind an empty log.

## Known gaps, deliberate

- **The tournament-cut degrade branch is verified by analysis, not by a clean run.** No alert fires
  because of the cut — measured: the idle tournament relay keeps reading (`rate ≈ 0.88/s`) and
  `tournament_consumer_starts_total` is 1 from boot, so the two liveness rules that could plausibly
  have fired stay quiet, and the other seven need activity.
- **Seven of nine alert rules still unobserved**, no receivers, no tracing backend — P8's gaps,
  unchanged and still stated.
- **R1 proves the system, not the digests.** Merging re-runs every job on `main`, so all ten
  services rebuild and re-pin; kaniko is not reproducible. Same source, and every phase since P4
  closed this way.

## Closure

**PENDING.** Still to happen: the `gitops-push-bot` token renewal, the exam date, the FF-merge of
`feat/p9-rehearsal-presentation` to `main`, and the closure pipeline. **Both Argo roots must be
repointed at `main` before the branch is deleted** — the drill cluster tracks the branch today.

## Next

**Nothing. P9 is the last phase.** What remains after closure is the exam itself: the date, the
48h hand-over of the repo link, and the runbook.
