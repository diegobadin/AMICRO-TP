// SessionInvalidated goes out on two transports, for two different consumers
// (docs/architecture/01-service-architecture.md §5.5):
//
//   Redis pub/sub `session:invalidated:{playerId}` — the gateway needs sub-second notice to close
//     the superseded SSE stream, so this one is awaited on a fail-fast client.
//   Kafka `identity.session-events` — room-gameplay tolerates seconds, so this one is fired and
//     forgotten; awaiting a broker retry loop would put Kafka's availability on the login path.
//
// Neither can fail a login: the database invalidation is the source of truth, and a failed publish
// is reported, not raised. The consumers arrive in P4 (gateway) and P3 (room-gameplay); publishing
// now means neither has to change this contract when it does.

import type { Kafka, Producer } from "kafkajs";
import type Redis from "ioredis";

export const TOPIC = "identity.session-events";

export interface SessionEvents {
  invalidated(playerId: string, oldSessionId: string, newSessionId: string | null, reason: string): Promise<void>;
}

export class NoEvents implements SessionEvents {
  async invalidated(): Promise<void> {}
}

export class PublishingEvents implements SessionEvents {
  private connecting?: Promise<Producer>;

  constructor(
    private readonly redis: Redis,
    private readonly kafka: Kafka,
    private readonly onFailure: (transport: "redis" | "kafka") => void,
  ) {}

  async invalidated(playerId: string, oldSessionId: string, newSessionId: string | null, reason: string): Promise<void> {
    try {
      await this.redis.publish(
        `session:invalidated:${playerId}`,
        JSON.stringify({ oldSessionId, newSessionId }),
      );
    } catch {
      this.onFailure("redis");
    }

    void this.sendToKafka(playerId, oldSessionId, reason).catch(() => this.onFailure("kafka"));
  }

  private async sendToKafka(playerId: string, oldSessionId: string, reason: string): Promise<void> {
    const producer = await this.connect();
    await producer.send({
      topic: TOPIC,
      // Partitioned by playerId so a player's session events stay ordered (§7.2).
      messages: [{ key: playerId, value: JSON.stringify({ playerId, oldSessionId, reason }) }],
    });
  }

  // Connect once, but forget a failed attempt so the next event retries instead of reusing a
  // producer that never came up.
  private connect(): Promise<Producer> {
    if (!this.connecting) {
      const producer = this.kafka.producer();
      this.connecting = producer
        .connect()
        .then(() => producer)
        .catch((e) => {
          this.connecting = undefined;
          throw e;
        });
    }
    return this.connecting;
  }
}
