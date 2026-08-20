# Validation — P9: Demo rehearsal + presentation

Every item is binary. A box is checked because it was observed, not because the code looks right.

## 1. The carried checklist (group 1)

- [ ] All five distroless-based Dockerfiles (`gateway`, `identity`, `spectator`, `outbox-relay`,
      `timer-worker`) reference the project registry, pinned by digest. `grep -rn "gcr.io/distroless"
      services/ --include=Dockerfile` returns **nothing**.
- [ ] The mirror job is **manual**, not part of the per-commit pipeline.
- [ ] The ten app containers declare CPU+memory requests and a memory limit; no CPU limit (D4).
- [ ] Sum of the ten requests is written down and compared against 2× `t3.large` allocatable.
- [ ] `kubectl get pod -n unoarena-staging -o …` shows QoS `Burstable` for all ten, and **zero**
      pods Pending on kind.
- [ ] `gradle check` in `room-gameplay` and in `tournament` runs ktlint and both suites are green.
- [ ] `tech-stack.md` §2 no longer names detekt, and the reason is recorded rather than the row
      silently edited.
- [ ] The `gitops-push-bot` token is renewed and a digest pin has landed with the new value.
- [ ] One green pipeline rebuilt and pinned **all ten** services; the kind cluster is 24/24
      Synced/Healthy on the new digests.

## 2. EKS NodePort access (group 2)

- [ ] `create.sh` authorizes 30080 and 30081 scoped to the operator's `/32`, and running it twice
      does not fail.
- [ ] `create.sh` prints both reachable URLs.
- [ ] A re-authorize path for a different source IP exists and is documented in the EKS README.
- [ ] At R1: `curl` reaches the gateway on 30080 and Grafana answers on 30081 **from the laptop**,
      with no port-forward running.
- [ ] `destroy.sh` then `sweep.sh` exits 0 and empty with the SG rule in place.
- [ ] The port-forward fallback is written in the runbook with the exact two commands.

## 3. The demo runbook (group 3)

- [ ] `docs/demo-runbook.md` exists and carries: preconditions, the 48h checklist, the timed
      script, a degrade branch per fragile step, and copy-pasteable commands.
- [ ] Every timing in it came from R0 or R1 — no estimated number is presented as measured.
- [ ] Every step that reads a business counter states the wait, and the narration that fills it.
- [ ] The CLI functional pass in it uses `Client-Checkpoint.md` §5's canonical surface, not our
      drill scripts.
- [ ] It links `docs/observability-runbook.md` for the "looks like a fault" list rather than
      copying it.

## 4. Rehearsals (groups 4–5)

- [ ] **R0 (kind)**: from `kind delete cluster` to a finished demo, following the runbook verbatim.
      Deviations recorded and folded back in.
- [ ] R0's degrade branches were walked: tournament cut, and port-forward instead of NodePorts.
- [ ] A second from-empty pass was run **if** R0's findings were about startup.
- [ ] **R1 (EKS)**: cluster created that day, `install.sh`, 24/24, the full runbook, both URLs.
- [ ] R1 recorded the number that does not exist yet: **time to 24/24 for 24 apps on 2× t3.large**.
- [ ] R1 confirmed no pod Pending on insufficient CPU/memory.
- [ ] R1 ended with `destroy.sh` + an empty `sweep.sh`, exit 0, and the budget banner read.
- [ ] Nothing in service code, charts or CI changed after R1 — or R1 was re-walked.

## 5. Deck and README (groups 6–7)

- [ ] `presentation/` holds Marp markdown in Spanish with speaker notes, plus rendered HTML and PDF.
- [ ] Every number on a slide traces to a file in the repo.
- [ ] The deck states the honest gaps (no tracing backend, seven rules never observed firing, no
      receivers) — no slide claims coverage `ESTADO-FINAL.md` denies.
- [ ] Root `README.md` satisfies every `Client-Checkpoint.md` §9 bullet.
- [ ] Any canonical command not fully implemented is named in the README with the reason.

## 6. Bite tests — prove the safety nets have teeth

Each one deliberately breaks something and confirms the failure is **loud**. Restore from a copy
afterwards; `git stash` reverts to HEAD, which already contains the fix.

- [ ] **The mirror is real.** Point one Dockerfile's `FROM` at a mirrored tag that does not exist.
      The build must fail on the pull — not fall back to `gcr.io`, and not go green.
- [ ] **Requests are actually rendered.** Set one service's CPU request to `100`. Its pod must go
      **Pending** with `Insufficient cpu`. A pod that schedules anyway means the template drops the
      block and the whole item is decorative.
- [ ] **ktlint is wired into `check`, not merely applied.** Introduce a formatting violation in each
      Kotlin service and confirm `gradle check` goes red — a plugin present but not in the `check`
      graph is `tech-stack.md` §2's promise all over again.
- [ ] **The SG rule is what opens the port.** At R1, revoke it and confirm `curl` to 30080 times
      out; re-authorize and confirm it answers. Without this, "it worked" may be some other rule.
- [ ] **The degrade branch lands.** Run the demo skipping the tournament entirely and confirm the
      runbook still reaches a coherent ending.
- [ ] **The runbook has a reader other than its author.** The teammate reads it cold and reports
      every place they would have had to ask a question (N4 makes them the presenter, so this is
      the real test, not a courtesy).
- [ ] **`check-dashboards.js` still bites** after group 1's rebuild: `PROM_URL=… node
      scripts/check-dashboards.js` reports 0 problems, and a deliberately mistyped metric name in a
      board fails it.

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
