// How a projected event reaches the spectators watching that room: an in-process fan-out from the
// consumer to the open SSE handlers.
//
// In-process because this service runs one replica (chart values, and the phase's stated gap). With
// a second replica each would only push the rooms its own partitions carry, and a spectator
// connected to the other pod would see nothing. A Redis pub/sub hop between the consumer and the
// streams fixes THAT — it is the shape P4 already uses for session kills.
//
// It does not fix the other half, and it would be dishonest to imply it does: projecting is a
// read-modify-write on `spectator:room:{id}`, and a room's log arrives on TWO topics. Partition
// assignment is per topic, so nothing guarantees the same consumer owns both of a room's
// partitions — with equal partition counts and the range assignor they happen to co-locate today,
// which is an accident of configuration, not a property. Two replicas therefore need the view
// write made atomic (a Lua script, or WATCH/MULTI with a retry) as well as the pub/sub hop. The
// dedup set is already safe: `SADD` is atomic on its own.

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
