// The SSE client. Node 20 has no `EventSource`, and the browser one cannot send an `Authorization`
// header — so this reads the response body itself. It is small enough to be worth reading:
// frames are separated by a blank line, and each line is `field: value`.
//
// Reconnection is part of the client, not an afterthought: the gateway replays from
// `Last-Event-ID`, so a dropped connection costs a round trip rather than a frozen board.

export interface StreamEvent {
  id?: number;
  event: string;
  data: Record<string, unknown>;
}

/** Splits whatever has arrived into whole frames, keeping the unfinished tail for the next read. */
export function parseFrames(buffer: string): { events: StreamEvent[]; rest: string } {
  const events: StreamEvent[] = [];
  const parts = buffer.split("\n\n");
  const rest = parts.pop() ?? "";
  for (const block of parts) {
    let id: number | undefined;
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith(":") || line === "") continue; // comments and padding carry no field
      const separator = line.indexOf(":");
      const field = separator === -1 ? line : line.slice(0, separator);
      const value = separator === -1 ? "" : line.slice(separator + 1).trimStart();
      if (field === "id") id = Number(value);
      else if (field === "event") event = value;
      else if (field === "data") data.push(value);
    }
    if (data.length === 0) continue;
    try {
      events.push({ id, event, data: JSON.parse(data.join("\n")) as Record<string, unknown> });
    } catch {
      // A frame we cannot read is dropped, not fatal: one bad frame must not end the game.
    }
  }
  return { events, rest };
}

export interface StreamOptions {
  url: string;
  token: string;
  /** The sequence number of the state read the caller already holds (D15). */
  from: number;
  onEvent: (event: StreamEvent) => void;
  onNotice: (text: string) => void;
  isOpen: () => boolean;
  /**
   * Called when the stream comes back after a drop — never on the first connection. The client
   * uses it to tell the room it is here again, which cancels a reconnection window if one opened
   * while it was away (P5 E8).
   */
  onReconnect?: () => void;
  fetchImpl?: typeof fetch;
}

/**
 * Reads until the caller says it is done. Each reconnection resumes from the last id actually
 * delivered, so the client never asks for frames it has already applied and never skips the ones
 * it has not.
 */
export async function follow(options: StreamOptions): Promise<void> {
  const doFetch = options.fetchImpl ?? fetch;
  let from = options.from;
  let connected = false;

  while (options.isOpen()) {
    let response: Response;
    try {
      response = await doFetch(options.url, {
        headers: { authorization: `Bearer ${options.token}`, accept: "text/event-stream", "last-event-id": String(from) },
      });
    } catch (e) {
      options.onNotice(`stream unreachable (${String(e)}); retrying`);
      await pause(1000);
      continue;
    }

    if (response.status === 401) {
      // The only way a live session dies mid-game is a second login for the same player.
      options.onEvent({ event: "session-invalidated", data: { reason: "unauthorized" } });
      return;
    }
    if (!response.ok || !response.body) {
      options.onNotice(`stream refused (${response.status}); retrying`);
      await pause(1000);
      continue;
    }

    if (connected) options.onReconnect?.();
    connected = true;

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (options.isOpen()) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const { events, rest } = parseFrames(buffer);
      buffer = rest;
      for (const event of events) {
        if (event.id !== undefined) from = event.id;
        options.onEvent(event);
      }
    }
    await reader.cancel().catch(() => undefined);
    if (options.isOpen()) options.onNotice("stream closed; reconnecting");
  }
}

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
