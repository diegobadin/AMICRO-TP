# Client Checkpoint Assignment Instructions

> **How this checkpoint is used.** This CLI is the **mechanism the teaching staff uses to
> evaluate your project**. It is **not graded as a standalone deliverable** — its purpose is
> to let us drive and observe *your* backend (play games, run load) through a uniform
> interface. If your CLI does not conform to this contract, we cannot properly exercise your
> system, and that directly limits how much of your project we can evaluate. Build it as the
> harness through which your whole backend is assessed.

## 1) Context (must be included in your analysis)

### UnoArena: Global Real-Time Uno Platform & Massive Tournaments

This checkpoint builds on the **Design Checkpoint** (domain model) and the **Architecture
Checkpoint** (microservices architecture). The authoritative problem statement is in
`presentation/high-level-definition.md`; the domain rules (Uno! call mechanics, 60-second
reconnection window, best-of-three matches, top-3 advancement, single-active-session, Elo
scope) are the same ones you already modeled and must not be contradicted here.

**Why a CLI client.** Each team designed a different architecture, with different wire
protocols (REST+SSE, WebSocket, hybrid), different card encodings, and different service
boundaries. A single shared wire contract across all teams is impractical. Instead, **each
team builds its own CLI client against its own backend.** The contract is therefore the
**CLI interface** — its command surface, observable behavior, output format, and packaging —
**not** the network protocol. Your CLI absorbs your wire protocol and card encoding
internally; the teaching staff only interacts with the CLI.

**Two goals this client must serve:**

1. **Faculty operability** — the teaching staff must be able to connect to *your* system and
   either **play interactively** or **run load tests**, through a uniform command surface,
   without learning your internal protocol.
2. **Team self-testing** — you must be able to exercise your own system end-to-end (play a
   game, drive load) instead of testing blindly. Treat this CLI as your own integration
   harness, not only a deliverable for the faculty.

## 2) Assignment objective

Deliver a **command-line client for your UnoArena backend** that implements the **canonical
command surface** and **output contract** defined in this document. The same binary must
support two modes:

- An **interactive mode** where a human plays a game from the terminal and sees live updates.
- A **headless bot mode** that plays autonomously and emits machine-readable output, so that
  running many instances in parallel produces a load test.

The internals (wire protocol, card serialization, service topology) are yours. The **observable
interface** is the contract. Where your backend names a concept differently, your CLI maps the
canonical command to your backend; the canonical surface is what the faculty runs.

## 3) Scope and constraints

- **In scope:** the canonical command surface (§5), the interactive play view (§5.C), the
  headless bot (§5.E), the JSON-lines output contract (§6), Docker packaging (§7), and test
  account provisioning (§5.A).
- **Not required (do not over-build):**
  - No TUI / full-screen redraw (ncurses-style). A line-based view is expected — building
    TUIs is not a learning objective.
  - No AI bot. A **random valid move** is sufficient for headless play.
  - No local persistence of game history, no internationalization, no backend
    auto-discovery (the target is configured), and no client-side correctness assertions.
- **Absorption rule:** the CLI **must not** impose a wire protocol or a card notation on the
  contract. Whatever your backend uses (struct, `"red-7"`, card ids, SSE, WebSocket) stays
  inside your CLI.

## 4) Modes of operation

| Mode | Entry | Who uses it | Output |
|---|---|---|---|
| **Interactive** | `play …` | A human at the terminal | Human-readable feed + board (line-based) |
| **Headless bot** | `bot …` | Scripts / faculty load tests | JSON lines per action + final summary |

One process equals one session equals one player identity. The authentication token is
obtained and held **inside** the process. A load test is **N parallel `bot` processes**.

## 5) Required deliverables (canonical command surface)

Your CLI **must** implement the following. Command *names and flags* below are the canonical
contract; map them to your backend internally. Where a feature is marked *optional*, it is not
required for a passing submission.

### 5.A — Authentication, session, and account provisioning

- `register --user <u> --pass <p>` — create a usable account.
- `login --user <u> --pass <p>` — authenticate; the obtained token is held by the process (or
  written to a session file for the one-shot utilities).
- `logout`, `whoami`.
- `seed --count <N> [--prefix <p>]` — create or ensure **N test accounts** and emit their
  credentials/tokens as JSON lines. This is what makes load testing possible.
- **Single-active-session:** a new login for the same user must invalidate the previous one
  (this is a domain non-negotiable). The CLI *may* (optional) report when its own stream is
  terminated due to session supersession, via `error_code: "session_superseded"` (see §6).
- **External-IdP teams:** if your design delegates authentication to an external identity
  provider, implement `register`/`seed` against that IdP **or a documented test stub**, and
  describe the procedure in your `README.md`.

### 5.B — Casual room gameplay (core)

- `room create [--max <N>]`, `room join <roomId>`, `room list`, `room leave`.
- `play --casual` — **abstract entry into a casual game**: "put me into a game", regardless of
  whether your backend uses explicit create/join or a matchmaking queue.
- The full casual loop must work end to end: enter a room, observe state, play, draw, call
  Uno!, and **handle the stale-command rejection** (`HTTP 409`-class conflict) by reconciling
  against the authoritative state. The CLI must surface conflicts rather than hide them.

### 5.C — Interactive play view (`play`)

- **Live event feed:** while connected, the CLI prints events as they arrive from your
  realtime channel (e.g. `Bob played R5 · color: RED`, `Carla drew 1`, `Dani called UNO!`),
  so the player follows the game even when it is not their turn. This demonstrates that your
  client consumes the realtime stream.
- **Turn board:** when it becomes the player's turn, render a block showing the discard top +
  **active color**, play direction, draw-pile size, opponents with their **card counts** and a
  `UNO!` flag, and **the player's numbered hand marking which cards are playable**.
- **Playing by index** (the player never types your internal card notation):
  - `play <n>` — play the n-th card in hand.
  - `play <n> <R|G|B|Y>` — for a **wild**, the color must be declared (or prompt for it).
  - `draw`, `uno`, `challenge`, `pass` (best-effort; some backends auto-pass on draw),
    `state` (re-render the board), `quit`.
- **Ordering & resync:** the feed must respect **per-room ordering** (consistent sequence
  numbers). If the stream drops, the client must **reconnect and reconcile** from the last
  seen sequence.

### 5.D — Spectator

- `spectate <roomId>` — connect as an observer and print the **same live feed**, restricted to
  **public information only**. Player hands must **never** appear in spectator output. This
  exercises your spectator projection's privacy boundary.

### 5.E — Headless bot (load) and tournaments

- `bot [--casual | --room <roomId> | --tournament <tournamentId>] (--user/--pass | --token)
  [--seed <rng>]` — play a full game/series autonomously, choosing a **random valid card**
  each turn, and emit one JSON line per action (§6).
- `bot --tournament <id>` must **register and then automatically play** whatever it is
  assigned (rounds, best-of-three series), so the faculty does not orchestrate matches by hand.
- `tournament register <id>`, `tournament status <id>` — explicit utilities.
- **Tournament test threshold:** if your backend requires a large minimum number of players to
  start a tournament, you must expose a **low, configurable threshold for test environments**
  and document it; otherwise tournament play cannot be exercised locally.
- **Mandatory but degradable:** tournament support is required, because the first-round surge
  is the hardest and most valuable part to evaluate. However, if your backend did not reach a
  working tournament implementation, **state this explicitly in your `README.md`** (what works,
  what does not, and why). A missing tournament path does not block evaluation of the rest of
  your system, but an *undocumented* gap will read as a broken contract.

### 5.F — Card display notation (canonical)

When printing cards, use this **canonical display notation** (so the faculty reads logs the
same way across all teams), independent of your internal encoding:

- Color prefix `R` / `G` / `B` / `Y`; number cards as the digit (`R5`, `G0`).
- Specials: `SKIP`, `REV` (reverse), `+2` (draw two), `WILD`, `WILD+4`. Combine with color
  where colored: `BSKIP`, `Y+2`. Wilds are colorless until played: `WILD`, `WILD+4`.

## 6) Output contract (machine-readable, mandatory in bot mode)

In `bot`/headless mode, **every action emits one JSON object per line** on stdout:

```json
{"ts":"2026-06-07T18:00:00Z","action":"play_card","room":"R1","player":"alice","latency_ms":12,"result":"ok","error_code":null,"seq":42,"correlationId":"abc-123"}
```

- Required fields: `ts`, `action`, `room`, `player`, `latency_ms`, `result` (`"ok"` |
  `"error"`), `error_code` (e.g. `409`, `401`, `"timeout"`, `"session_superseded"`, or
  `null`), `seq`, `correlationId`.
- On termination, emit a **final summary line** (total actions, error counts, latency
  aggregates) and set the **process exit code** (`0` on success, non-zero on failure).
- The interactive mode is human-readable, but **must** support a `--json` flag that emits the
  same line format, so the same client can be scripted.

## 7) Packaging and invocation

- **Mandatory:** a `Dockerfile` at a known path that builds the CLI, with the CLI as the
  entrypoint, so it runs uniformly as `docker run <image> <subcommand> <flags>` regardless of
  implementation language.
- **Optional:** a native binary or script with a documented invocation in the `README.md`.
- **Configuration:** the backend target and any credentials are provided via **environment
  variables** (e.g. `UNOARENA_API_URL`) or flags — never hard-coded. The same image must be
  able to target a local instance or a deployed one.

## 8) How this CLI is used to evaluate your project

The CLI itself is **not graded as a separate artifact**. It is the harness through which the
teaching staff exercises your backend, so the items below are what **must work for us to
evaluate your system** — gaps here translate into parts of your project we cannot assess.

- **Contract conformance** — the canonical command surface (§5) and output contract (§6)
  behave as specified, so the faculty can drive your system without reading your internal docs.
- **End-to-end playability** — a human can play a full casual game interactively, following
  the live feed and the turn board, with stale-command (`409`) reconciliation working.
- **Headless correctness & load fitness** — `bot` plays valid moves autonomously, emits clean
  JSON lines, and N parallel instances produce a coherent load run with a usable summary.
- **Spectator privacy** — spectator output never leaks hands.
- **Tournament play** — `bot --tournament` registers and plays assigned matches end to end
  (mandatory but degradable per §5.E; subject to the configurable test threshold).
- **Packaging** — `docker run` works out of the box with environment-based configuration.
- **Traceability & honesty** — your `README.md` documents how each canonical command maps to
  your backend, and **clearly states any command you could not fully implement and why**. An
  honestly documented gap is far better than a silent one: it tells us what to test instead.

## 9) Submission format and deadline

- Deliver the CLI source, its `Dockerfile`, and a root `README.md` in the course repository.
  The `README.md` must:
  - List every canonical command and how to invoke it (Docker and, if provided, native).
  - Map each canonical command to your backend's endpoint/operation.
  - Document the test-account seeding procedure and the tournament test threshold.
  - Note any external-IdP stub (§5.A) and any command not fully implemented.
- Supporting artifacts (sample JSON-lines output, a short asciinema/recording of interactive
  play) are welcome but optional.
