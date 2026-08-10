import { describe, expect, it } from "vitest";
import { follow, parseFrames } from "../src/stream.js";

const body = (chunks: string[]): ReadableStream<Uint8Array> => {
  const encoder = new TextEncoder();
  let i = 0;
  return new ReadableStream({
    pull(controller) {
      if (i < chunks.length) controller.enqueue(encoder.encode(chunks[i++]));
      else controller.close();
    },
  });
};

const response = (chunks: string[], status = 200): Response =>
  ({ status, ok: status >= 200 && status < 300, body: body(chunks) }) as unknown as Response;

describe("frame parsing", () => {
  it("reads id, event and data, and keeps an unfinished frame for the next chunk", () => {
    const first = parseFrames('id: 42\nevent: CardPlayed\ndata: {"card":"R5"}\n\nid: 43\nevent: Card');
    expect(first.events).toEqual([{ id: 42, event: "CardPlayed", data: { card: "R5" } }]);
    expect(first.rest).toBe("id: 43\nevent: Card");

    const second = parseFrames(`${first.rest}Drawn\ndata: {"playerId":"p1"}\n\n`);
    expect(second.events).toEqual([{ id: 43, event: "CardDrawn", data: { playerId: "p1" } }]);
  });

  it("handles a control frame with no id", () => {
    const { events } = parseFrames('event: heartbeat\ndata: {"seq":12}\n\n');
    expect(events).toEqual([{ id: undefined, event: "heartbeat", data: { seq: 12 } }]);
  });

  it("ignores keep-alive comments", () => {
    const { events } = parseFrames(': ping\n\nevent: resync\ndata: {"reason":"trimmed"}\n\n');
    expect(events.map((e) => e.event)).toEqual(["resync"]);
  });

  it("drops a frame it cannot read instead of ending the game", () => {
    const { events } = parseFrames("event: CardPlayed\ndata: {not json\n\nevent: heartbeat\ndata: {}\n\n");
    expect(events.map((e) => e.event)).toEqual(["heartbeat"]);
  });
});

describe("following the stream", () => {
  it("resumes from the caller's baseline, not from the tail", async () => {
    const asked: (string | undefined)[] = [];
    let open = true;
    await follow({
      url: "http://gw/rooms/r1/stream",
      token: "t",
      from: 12,
      onEvent: () => {
        open = false;
      },
      onNotice: () => undefined,
      isOpen: () => open,
      fetchImpl: async (_url, init) => {
        asked.push((init?.headers as Record<string, string>)["last-event-id"]);
        return response(['id: 13\nevent: CardDrawn\ndata: {}\n\n']);
      },
    });
    expect(asked).toEqual(["12"]);
  });

  it("reconnects from the last frame it actually delivered", async () => {
    const asked: string[] = [];
    let attempt = 0;
    let open = true;
    await follow({
      url: "http://gw/rooms/r1/stream",
      token: "t",
      from: 5,
      onEvent: () => undefined,
      onNotice: () => undefined,
      isOpen: () => open,
      fetchImpl: async (_url, init) => {
        asked.push((init?.headers as Record<string, string>)["last-event-id"]);
        attempt++;
        if (attempt === 1) return response(['id: 6\nevent: CardDrawn\ndata: {}\n\nid: 7\nevent: TurnPassed\ndata: {}\n\n']);
        open = false; // the second connection is the one under test; stop after it
        return response([]);
      },
    });
    // Not 5 again: asking for frames it already applied would replay them.
    expect(asked).toEqual(["5", "7"]);
  });

  it("turns a 401 into the session notice rather than retrying forever", async () => {
    const seen: string[] = [];
    await follow({
      url: "http://gw/rooms/r1/stream",
      token: "t",
      from: 1,
      onEvent: (e) => seen.push(e.event),
      onNotice: () => undefined,
      isOpen: () => true,
      fetchImpl: async () => response([], 401),
    });
    expect(seen).toEqual(["session-invalidated"]);
  });

  it("delivers frames in order, across chunk boundaries", async () => {
    const ids: (number | undefined)[] = [];
    let open = true;
    await follow({
      url: "http://gw/rooms/r1/stream",
      token: "t",
      from: 0,
      onEvent: (e) => {
        ids.push(e.id);
        if (e.id === 3) open = false;
      },
      onNotice: () => undefined,
      isOpen: () => open,
      fetchImpl: async () =>
        response(["id: 1\nevent: A\ndata: {}\n\nid: 2\nev", "ent: B\ndata: {}\n\nid: 3\nevent: C\ndata: {}\n\n"]),
    });
    expect(ids).toEqual([1, 2, 3]);
  });
});
