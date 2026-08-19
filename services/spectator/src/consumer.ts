// The `spectator-view` consumer group, over BOTH room topics.
//
// Architecture §1's consumer table has this service on `room.public.events` alone, which would
// leave the view unable to learn that the game ended: `GameCompleted`, `RoomCompleted` and
// `RoomExpired` are lifecycle events. A spectator would watch a finished room sit there mid-turn
// until the TTL collected it. Reading both is the delta, and it is what makes the interleaving
// problem real — see `store.ts` for why the dedup is a set.

import type { Consumer, IHeaders } from "kafkajs";
import * as metrics from "./metrics.js";
import { privateFields } from "./privacy.js";
import type { Broker } from "./broker.js";
import type { Store } from "./store.js";
import { type PublicEvent, apply, emptyView } from "./view.js";

export const PUBLIC_TOPIC = "room.public.events";
export const LIFECYCLE_TOPIC = "room.lifecycle.events";
export const GROUP_ID = "spectator-view";
export const TOPICS = [PUBLIC_TOPIC, LIFECYCLE_TOPIC];

export type Outcome = "projected" | "duplicate" | "rejected" | "malformed";

/** One Kafka header as a string. kafkajs hands them back as Buffers, and repeated as arrays. */
export function headerValue(headers: IHeaders | undefined, name: string): string {
  const raw = headers?.[name];
  const first = Array.isArray(raw) ? raw[0] : raw;
  return first === undefined ? "" : first.toString();
}

/**
 * Project one event. Everything the consumer does per message, with the broker plumbing passed in
 * so a test can drive it without Kafka.
 */
export async function project(
  topic: string,
  body: PublicEvent,
  store: Store,
  broker: Broker,
): Promise<Outcome> {
  const leaked = privateFields(body as unknown as Record<string, unknown>);
  if (leaked.length > 0) {
    metrics.privateFieldRejections.inc();
    return "rejected";
  }
  if (typeof body.roomId !== "string" || typeof body.sequenceNumber !== "number") {
    metrics.eventsMalformed.inc();
    return "malformed";
  }

  if (!(await store.claim(body.roomId, body.sequenceNumber))) {
    metrics.eventsDeduped.inc();
    return "duplicate";
  }

  try {
    const current = (await store.read(body.roomId)) ?? emptyView(body.roomId);
    const next = apply(current, body);
    next.spectatorCount = await store.spectatorCount(body.roomId);
    await store.write(next);
    metrics.eventsProjected.inc({ topic });
    broker.publish(body.roomId, { event: body, view: next });
    return "projected";
  } catch (error) {
    // The claim is what makes a redelivery a no-op, so an apply that did not land must give it
    // back. Otherwise the retry Kafka is about to perform would be mistaken for a duplicate and
    // the event would be dropped for good — a dedup that eats real events instead of copies.
    await store.release(body.roomId, body.sequenceNumber);
    throw error;
  }
}

export async function start(consumer: Consumer, store: Store, broker: Broker): Promise<void> {
  await consumer.connect();
  // From the beginning: a consumer group that appears after the topic already has traffic has to
  // project the rooms it missed, not only the ones that start from now on.
  await consumer.subscribe({ topics: TOPICS, fromBeginning: true });
  await consumer.run({
    eachMessage: async ({ topic, message }) => {
      try {
        const body = JSON.parse(message.value?.toString() ?? "{}") as PublicEvent;
        const outcome = await project(topic, body, store, broker);
        // The relay carries the originating request's correlation id onto every message
        // (outbox-relay/envelope.go), so one id can be followed from the player's command into the
        // live view. Reading it here is what makes that true — the header has travelled the whole
        // spine since P5 with nobody consuming it.
        metrics.log("info", "projected", {
          outcome,
          offset: message.offset,
          correlationId: headerValue(message.headers, "ce-correlationid"),
        });
      } catch (error) {
        metrics.consumerErrors.inc();
        throw error; // kafkajs retries the message; the claim was released above.
      }
    },
  });
}

/**
 * Lag from the broker's own high watermark, never from a cursor this process holds. A number a
 * consumer derives from its own progress cannot report that it has stopped moving — P5's
 * `outboxrelay_lag_seconds` lesson, one layer over.
 */
export async function refreshLag(admin: {
  fetchTopicOffsets: (topic: string) => Promise<{ partition: number; high: string }[]>;
  fetchOffsets: (options: {
    groupId: string;
    topics: string[];
  }) => Promise<{ topic: string; partitions: { partition: number; offset: string }[] }[]>;
}): Promise<void> {
  const committed = await admin.fetchOffsets({ groupId: GROUP_ID, topics: TOPICS });
  for (const topic of TOPICS) {
    const high = await admin.fetchTopicOffsets(topic);
    const ours = committed.find((entry) => entry.topic === topic)?.partitions ?? [];
    let lag = 0;
    for (const partition of high) {
      const at = ours.find((p) => p.partition === partition.partition);
      const offset = Number(at?.offset ?? -1);
      if (offset < 0) continue;
      lag += Math.max(0, Number(partition.high) - offset);
    }
    metrics.consumerLag.set({ topic }, lag);
  }
  metrics.lagReads.inc();
}
