// How a projected event reaches the spectators watching that room: an in-process fan-out from the
// consumer to the open SSE handlers.
//
// In-process because this service runs one replica (chart values, and the phase's stated gap). With
// a second replica each would only push the rooms its own partitions carry, and a spectator
// connected to the other pod would see nothing — the fix is a Redis pub/sub hop between the
// consumer and the streams, which is the same shape P4 already uses for session kills. Written down
// rather than built, so the day someone raises `replicas` they find the note instead of the bug.

import type { PublicEvent, SpectatorView } from "./view.js";

export interface Update {
  event: PublicEvent;
  view: SpectatorView;
}

export type Listener = (update: Update) => void;

export class Broker {
  private readonly rooms = new Map<string, Set<Listener>>();

  subscribe(roomId: string, listener: Listener): () => void {
    const listeners = this.rooms.get(roomId) ?? new Set<Listener>();
    listeners.add(listener);
    this.rooms.set(roomId, listeners);
    return () => {
      listeners.delete(listener);
      if (listeners.size === 0) this.rooms.delete(roomId);
    };
  }

  publish(roomId: string, update: Update): void {
    for (const listener of this.rooms.get(roomId) ?? []) {
      // One slow or broken subscriber must not stop the others, and must not kill the consumer.
      try {
        listener(update);
      } catch {
        // The stream handler owns its own cleanup; a write to a dead socket is not our problem.
      }
    }
  }
}
