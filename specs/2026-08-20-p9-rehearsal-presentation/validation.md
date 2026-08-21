# Validation — P9: Demo rehearsal + presentation

Every item is binary. A box is checked because it was observed, not because the code looks right.

> **Progress 2026-08-20.** Groups 1–7 done, plus the review pass (8.5, eleven findings) and **R1 on
> EKS — E1 proven with a bite test, 24/24 in 8 m 53 s, account swept clean**. Remaining: the CI-token
> renewal (user), the tournament-cut branch end to end, the interactive two-human game, and closure
> (8.1/8.3/8.4). `[~]` marks a partial.

## 1. The carried checklist (group 1)

- [x] All five distroless-based Dockerfiles (`gateway`, `identity`, `spectator`, `outbox-relay`,
      `timer-worker`) reference the project registry, pinned by digest. `grep -rn "gcr.io/distroless"
      services/ --include=Dockerfile` returns **nothing**.
- [x] The mirror job adds nothing to a normal pipeline: it exists only where `ci/mirror-bases.yml`
      itself changed (D5′). Verified — the push carrying it produced a **one-job** pipeline.
- [x] The ten app containers declare CPU+memory requests and a memory limit; no CPU limit (D4).
- [x] Sum of the ten requests is written down and compared against 2× `t3.large` allocatable.
- [x] `kubectl get pod -n unoarena-staging -o …` shows QoS `Burstable` for all ten, and **zero**
      pods Pending on kind.
- [x] `gradle check` in `room-gameplay` and in `tournament` runs ktlint and both suites are green.
- [x] `tech-stack.md` §2 no longer names detekt, and the reason is recorded rather than the row
      silently edited.
- [ ] The `gitops-push-bot` token is renewed and a digest pin has landed with the new value.
- [x] One green pipeline rebuilt and pinned **all ten** services; the kind cluster is 24/24
      Synced/Healthy on the new digests.

## 2. EKS NodePort access (group 2)

- [x] `create.sh` authorizes 30080 and 30081 scoped to the operator's `/32`, and running it twice
      does not fail.
- [x] `create.sh` prints both reachable URLs.
- [x] A re-authorize path for a different source IP exists and is documented in the EKS README.
- [x] At R1: `curl` reached the gateway on 30080 and Grafana on 30081 **from the laptop**, no
      port-forward, on **both** node public IPs — `200` four times.
- [x] `destroy.sh` then `sweep.sh` exited 0 and empty with the SG rule in place (one leaked EBS
      volume auto-deleted by destroy's own sweep).
- [x] The port-forward fallback is written in the runbook with the exact two commands.

## 3. The demo runbook (group 3)

- [x] `docs/demo-runbook.md` exists and carries: preconditions, the 48h checklist, the timed
      script, a degrade branch per fragile step, and copy-pasteable commands.
- [x] Every timing in it came from R0 or P1 and is tagged with which — the review pass removed the
      last three stale figures, and the legend now matches the notation actually used.
- [x] Every step that reads a business counter states the wait, and the narration that fills it.
- [x] The CLI functional pass in it uses `Client-Checkpoint.md` §5's canonical surface, not our
      drill scripts.
- [x] It links `docs/observability-runbook.md` for the "looks like a fault" list rather than
      copying it.

## 4. Rehearsals (groups 4–5)

- [x] **R0 (kind)**: from `kind delete cluster` to a finished demo, following the runbook verbatim.
      3 m 06 s to `install.sh` returning, **17 m 48 s to 24/24**. Deviations folded back in.
- [x] R0's **port-forward** degrade branch was walked live, and found the runbook's own spelling
      bypassed itself on kind (bound `[::1]` only). Now 18080/18081, both verified `200`.
- [~] The **tournament-cut** branch is analysed, not performed end to end: every non-tournament step
      was executed independently in R0, and cutting it makes
      `tournament_tournaments_completed_total` read 0 — now written into §6 as something to say out
      loud. A clean run with the block skipped has **not** been done.
- [x] A second from-empty pass was **not** required: R0's defect was a client-side convergence race,
      not a startup defect, so 4.5's trigger did not fire.
- [x] **R1 (EKS)**: cluster created that day, `install.sh`, 24/24, the full runbook, both URLs.
- [x] R1 recorded it: **8 m 53 s** to 24/24 for 24 apps on 2× t3.large — faster than either kind run.
- [x] R1 confirmed no pod Pending on insufficient CPU/memory — **zero** such events, 11/11 Running.
- [x] R1 ended with `destroy.sh` + an empty `sweep.sh`, exit 0. Budget before: **$0.2 of $50**.
- [ ] Nothing in service code, charts or CI changed after R1 — or R1 was re-walked.

## 5. Deck and README (groups 6–7)

- [x] `presentation/` holds Marp markdown in Spanish with speaker notes, plus rendered HTML and PDF.
- [x] Every number on a slide traces to a file in the repo.
- [x] The deck states the honest gaps (no tracing backend, seven rules never observed firing, no
      receivers) — no slide claims coverage `ESTADO-FINAL.md` denies.
- [x] Root `README.md` satisfies every `Client-Checkpoint.md` §9 bullet.
- [x] Any canonical command not fully implemented is named in the README with the reason.

## 6. Bite tests — prove the safety nets have teeth

Each one deliberately breaks something and confirms the failure is **loud**. Restore from a copy
afterwards; `git stash` reverts to HEAD, which already contains the fix.

- [ ] **The mirror is real.** Point one Dockerfile's `FROM` at a mirrored tag that does not exist.
      The build must fail on the pull — not fall back to `gcr.io`, and not go green.
- [x] **Requests are actually rendered**, not silently dropped by the template: the eleven running
      pods report the exact intended values and QoS `Burstable`. The stronger form — an unschedulable
      request must go **Pending** — is R1's 5.3, where it is a real question rather than a staged one.
- [x] **The JVM heap follows the limit.** `docker run -m 768m` gives a 192 MiB default heap and
      576 MiB with `-XX:MaxRAMPercentage=75`; the running pods report 576/480 MiB. Without the flag
      the limit would have put room-gameplay's heap **below its measured 307 MiB peak**.
- [x] **ktlint is wired into `check`, not merely applied.** Introduce a formatting violation in each
      Kotlin service and confirm `gradle check` goes red — a plugin present but not in the `check`
      graph is `tech-stack.md` §2's promise all over again.
- [x] **The SG rule is what opens the port.** Revoked → `http 000`; re-authorized → `http 200`.
      A second authorize run reported `already open`, so it is idempotent as designed.
- [~] **The degrade branch lands.** Port-forward: walked, and it found a defect. Tournament-cut:
      verified by measurement (no alert can fire because of the cut), not by a clean run.
- [x] **A human plays a game.** Two people, two terminals — and it **failed**: a numbered hand would
      not take a number, both players lost their turns to the 30 s clock, double forfeit, no card
      played. Fixed and unit-tested (14.11); **needs one more two-human run to confirm.**
- [ ] **The runbook has a reader other than its author.** The teammate reads it cold and reports
      every place they would have had to ask a question (N4 makes them the presenter, so this is
      the real test, not a courtesy).
- [x] **`check-dashboards.js` still bites** after group 1's rebuild — 86 queries, 0 problems; a
      mistyped `roomgameplay_games_completd_total` produced 2 `UNKNOWN METRIC` failures and rc=1: `PROM_URL=… node
      scripts/check-dashboards.js` reports 0 problems, and a deliberately mistyped metric name in a
      board fails it.

## 6b. CLI conformance with `Client-Checkpoint.md` §5 (found while writing the runbook)

- [x] `spectate <roomId>`, `tournament register|status|bracket <id>` and `bot --tournament <id>` —
      the **canonical positional forms** — work; the `--room`/`--id` spellings still do and win when
      both are given.
- [x] Bite-checked: restoring `flags.tournament === true` and the naive positional filter turns
      **3 of the new tests red**, and restoring the fixes turns them green. 83 CLI tests pass.
- [x] A flag's value is never mistaken for a positional (`--timeout 30 7` yields `["7"]`).
- [x] The CLI README documents `spectate`, `rating`, `leaderboard` and `stats`, which it named
      nowhere before, and labels tournaments §5.E rather than §5.D.

## 7. Out-of-scope confirmation — must NOT appear

- [ ] No change to any event, schema, topic or table.
- [ ] No new domain behaviour or CLI command.
- [ ] No alert receivers, no SLO objects, no tracing backend, no PVC in `monitoring`.
- [ ] No alert rule promoted from "untested" to "covered" without having been observed firing.
- [ ] No production overlay or prod promotion in the demo path.
- [ ] No detekt plugin (D3 struck it from the table instead).
- [ ] No estimated timing presented as a measured one.

## 8. Mission check

- **Does the decomposition survive being watched?** On the day, an empty cluster becomes ten
  independently built, digest-pinned services plus their platform, in one script, with the faculty
  driving the CLI. `mission.md` §2 argued that a decomposition which cannot be built, delivered and
  deployed per service is a distributed monolith. P9 is where that argument is performed instead of
  asserted.
- **Can somebody else tell the story?** N4 puts the teammate in front of the room. If the deck and
  the runbook only work when their author is holding them, P9 shipped a rehearsal and not a handoff.
