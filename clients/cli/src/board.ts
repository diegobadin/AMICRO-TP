// Rendering only. Cards arrive from the backend already in the canonical notation of
// Client-Checkpoint §5.F (`R5`, `BSKIP`, `Y+2`, `WILD+4`) — the CLI prints what it is given rather
// than translating, so the string in the terminal, in the API response and in the game log is one
// and the same.

export interface Opponent {
  playerId: string;
  cardCount: number;
  calledUno: boolean;
  connection: string;
}

export interface GameView {
  roomId: string;
  gameNumber: number;
  status: string;
  sequenceNumber: number;
  discardTop: string;
  activeColor: string;
  direction: string;
  deckSize: number;
  currentPlayerId: string | null;
  yourTurn: boolean;
  hand: string[];
  playable: number[];
  opponents: Opponent[];
  challengeWindow: { targetPlayerId: string; expiresAt: string } | null;
  finishingOrder: string[];
  turnDeadline: string | null;
  drewThisTurn: boolean;
}

const short = (id: string) => id.slice(0, 8);

export function board(view: GameView, me: string): string {
  const arrow = view.direction === "CLOCKWISE" ? "▸" : "◂";
  const others = view.opponents
    .map((o) => {
      const uno = o.calledUno ? " UNO!" : "";
      const gone = o.connection === "connected" ? "" : ` (${o.connection})`;
      const turn = o.playerId === view.currentPlayerId ? "*" : " ";
      return `${turn}${short(o.playerId)} ${o.cardCount}${uno}${gone}`;
    })
    .join("   ");

  // Playable cards are marked from the server's own legality check, so the board can never
  // disagree with what the engine will accept.
  const playable = new Set(view.playable);
  // Fixed-width so the columns line up whatever the cards are: WILD+4 is six characters, R5 is two.
  const hand = view.hand
    .map((card, i) => `${String(i + 1).padStart(2)}) ${(card + (playable.has(i) ? "*" : "")).padEnd(7)}`)
    .join("");

  const lines = [
    `-- room ${short(view.roomId)} - game ${view.gameNumber} - seq ${view.sequenceNumber}`,
    `   discard ${view.discardTop}  color ${view.activeColor}  ${arrow}  deck ${view.deckSize}`,
    `   players  ${others}`,
    `   your hand (${view.hand.length}):`,
    `     ${hand || "(empty)"}`,
  ];
  if (view.challengeWindow && view.challengeWindow.targetPlayerId !== me) {
    lines.push(`   ${short(view.challengeWindow.targetPlayerId)} is on one card - 'challenge' while the window is open`);
  }
  if (view.yourTurn) {
    // Only the actions that are legal right now. `pass` before drawing is a 409, and offering it
    // teaches the player to expect an error — the drill harness fell for exactly that.
    const actions = ["play <n> [R|G|B|Y]", view.drewThisTurn ? "pass" : "draw", "uno", "challenge", "state", "quit"];
    if (view.hand.length === 2) {
      lines.push("   down to your last two - play it as 'play <n> uno' or an opponent can catch you");
    }
    lines.push(`   YOUR TURN${view.drewThisTurn ? " (already drew)" : ""}: ${actions.join(" | ")}`);
  } else {
    lines.push(`   waiting for ${view.currentPlayerId ? short(view.currentPlayerId) : "the next turn"}`);
  }
  return lines.join("\n");
}

/**
 * The live feed §5.C asks for, derived from consecutive polls. P3 polls the state endpoint (E4), so
 * what a player did is inferred from what changed rather than read from an event stream — two
 * things happening inside one poll interval collapse into one line. P4's SSE carries the real
 * events and this goes away; the README says so rather than leaving it to be discovered.
 */
export function feed(before: GameView, after: GameView, me: string): string[] {
  const out: string[] = [];
  const name = (id: string) => (id === me ? "you" : short(id));

  if (after.discardTop !== before.discardTop) {
    const player = before.currentPlayerId ? name(before.currentPlayerId) : "someone";
    const colour = after.activeColor !== before.activeColor ? ` - color ${after.activeColor}` : "";
    out.push(`${player} played ${after.discardTop}${colour}`);
  }
  for (const o of after.opponents) {
    const was = before.opponents.find((b) => b.playerId === o.playerId);
    if (!was) continue;
    if (o.cardCount > was.cardCount) out.push(`${name(o.playerId)} drew ${o.cardCount - was.cardCount}`);
    if (o.calledUno && !was.calledUno) out.push(`${name(o.playerId)} called UNO!`);
    if (o.connection !== was.connection) out.push(`${name(o.playerId)} is ${o.connection}`);
  }
  if (after.hand.length > before.hand.length) {
    out.push(`you drew ${after.hand.slice(before.hand.length).join(" ")}`);
  }
  if (after.status === "COMPLETED" && before.status !== "COMPLETED") {
    const winner = after.finishingOrder[0];
    out.push(`game over - ${winner === me ? "you win!" : `${short(winner ?? "?")} wins`}`);
  }
  return out;
}
