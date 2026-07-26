# Kickoff — P1 implementation (session bridge)

> Written 2026-07-26, when the session that spec'd and reviewed P1 closed before implementing.
> A fresh session starts here.

## Where things stand

- **P0 (checkpoint closure): shipped.** Evidence: `devops-checkpoint/README.md` §9 + the
  closure spec's `ESTADO-FINAL.md`. Pipeline green, promotion run linked, drills linked.
- **P1: triad written AND review-passed** — the critical review already amended D3 (Redis = raw
  manifests, not Bitnami), D5/AC-P1.6 (convergence, not strict wave ordering) and R4 (develop on
  a branch; `main` runs the full pipeline on every push). **No implementation exists yet: F1 has
  not started.** D1–D7 stand unless the user objects at session start.
- Local tooling verified: docker daemon, kind, kubectl, helm, terraform present; `aws` CLI v2 in
  `~/bin`; `eksctl`/`argocd`/`kubeseal` still to be installed (user-mode, no sudo).

## How to start the new session

1. Read `requirements.md`, `plan.md`, `validation.md` in this directory — they are current;
   trust them over memory.
2. Create `feat/p1-platform` from `main`. All F1–F7 commits land there; docs-only commits carry
   `[skip ci]`.
3. Execute the phase table in `plan.md` one commit at a time, starting with **F1** (platform
   AppProject + `unoarena-platform-root` + `install.sh` wiring incl. `TARGET_REVISION`).
   Validate each phase against local kind before moving on; record drill transcripts in
   `validation.md`.
4. Merge fast-forward into `main` after F6 (one full pipeline for the whole phase, AC-P1.5).
5. **F7 (EKS rehearsal) needs the user**: Learner Lab started + fresh CLI credentials under the
   `amicro` profile (they rotate every lab start). Never end a rehearsal without
   `destroy.sh` + an empty `sweep.sh` — billing survives lab shutdown.

## Contact points with the user

- Ask before: creating any new credential/token, or triggering anything production-shaped —
  name the exact action.
- Ping them for: lab start + credential refresh (F7), and the budget banner reading afterwards.
