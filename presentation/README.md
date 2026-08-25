# Presentation

- **[`final-deck.md`](./final-deck.md)** — the deck for the final delivery: the domain, the final
  architecture, the decisions worth defending, the numbers, and the gaps. Spanish, because it is
  spoken to the cátedra; the rest of the repo is English.
- **[`high-level-definition.md`](./high-level-definition.md)** — the original March problem
  statement the whole program was built against.

## Rendering it

The source is [Marp](https://marp.app/) markdown — a slide is anything between two `---` rules, and
each `<!-- … -->` block is that slide's speaker notes.

```bash
npx --yes @marp-team/marp-cli final-deck.md -o final-deck.html
npx --yes @marp-team/marp-cli final-deck.md --pdf --allow-local-files -o final-deck.pdf
npx --yes @marp-team/marp-cli -p final-deck.md          # live preview while editing
```

Presenter view (speaker notes on a second screen) is `p` in the rendered HTML, or the
`--pdf-notes` flag if the notes should be printed with the PDF.

## Presenting it

**Every number on a slide has a source in the repo**, and the speaker notes say which. If a figure
gets questioned, the answer is a file, not a memory:

| Claim | Where it lives |
|---|---|
| 24/24 from empty, 8 m 53 s | P9's `ESTADO-FINAL.md` and `plan.md` §5.7 — R1, on the exam's own target |
| the same on kind, 12 m 25 s and 17 m 48 s | P8's `ESTADO-FINAL.md` and P9's `plan.md` §4.6 — **quote the range, not either figure**: 798 s of R0's 1068 s were image pulls, so the number is registry-bound |
| 44-job pipeline, 43 + 1 manual | P9's `ESTADO-FINAL.md`, closure section — 43 on a push; a manual run adds `mirror:bases` |
| 179 events in a whole demo | `CHANGELOG-design.md` §13, and P8's alert retuning |
| five services on one `correlationId` | `docs/observability-runbook.md` |
| synchronous room provisioning | `CHANGELOG-design.md` 12.1 — a deliberate deviation from ADR-06 |
| requests/limits and the JVM heap | `specs/2026-08-20-p9-rehearsal-presentation/plan.md` §1.9 |

The deck deliberately carries a "what we did **not** do" slide. The exam's own client spec says an
honestly documented gap is far better than a silent one; a deck claiming coverage that
`ESTADO-FINAL.md` denies is a contradiction a grader can find in one hop.
