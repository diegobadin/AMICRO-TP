// The spectator's SSE tier.
//
// Deliberately NOT shared with the gateway's player stream, which tails Redis directly and carries
// four guards for the ways a player can end up believing something false (gap check, resync,
// heartbeat, unreadable tail). This one reads a projection that is already the whole truth, so a
// spectator who falls behind is fixed by re-sending the view — there is no gap to detect, because
// no frame here is a delta the client has to accumulate. Two implementations, because they answer
// two different questions; a shared abstraction would be the "purpose changed after an earlier fix"
// shape the review convention says to look hardest at.

import type { Update } from "./broker.js";
import type { SpectatorView } from "./view.js";

export const HEARTBEAT_MS = 15_000;

export interface Frame {
  id?: number;
  event: string;
  data: unknown;
}

/** SSE wire format: `id`, `event`, one `data` line, blank line to end the frame. */
export function encodeFrame(frame: Frame): string {
  const id = frame.id === undefined ? "" : `id: ${frame.id}\n`;
  return `${id}event: ${frame.event}\ndata: ${JSON.stringify(frame.data)}\n\n`;
}

/**
 * The first frame of every stream. A spectator arriving mid-game gets the whole board rather than
 * whatever happens next — which is the point of keeping a projection instead of replaying a log.
 */
export function snapshotFrame(view: SpectatorView): Frame {
  return { id: view.lastSequence >= 0 ? view.lastSequence : undefined, event: "snapshot", data: view };
}

/**
 * Every subsequent frame carries the domain event AND the whole view. The event is what the client
 * narrates; the view is what it renders. Sending both is what lets the client render only from
 * state the server sent — P4's lesson, where a CLI that applied events to its own board walked into
 * 22 `409 not_your_turn` in one game because a multi-event command left it briefly believing
 * something the server never said.
 */
export function updateFrame(update: Update): Frame {
  return {
    id: update.view.lastSequence,
    event: update.event.type,
    data: { event: update.event, view: update.view },
  };
}

export function heartbeatFrame(view: SpectatorView | null): Frame {
  return { event: "heartbeat", data: { seq: view?.lastSequence ?? -1 } };
}
