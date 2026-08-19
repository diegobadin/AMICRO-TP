import { describe, expect, it } from "vitest";
import { headerValue } from "../src/consumer.js";

// The relay writes ce-correlationid as a Buffer (outbox-relay/envelope.go). Reading it wrong is
// the quiet kind of wrong: `String(buffer)` on an array, or a missing header, both produce
// something printable, and the log line would carry "[object Object]" or "undefined" while looking
// perfectly healthy in Loki.
describe("reading a Kafka header", () => {
  it("decodes the Buffer kafkajs actually hands back", () => {
    expect(headerValue({ "ce-correlationid": Buffer.from("abc-123") }, "ce-correlationid")).toBe("abc-123");
  });

  it("takes the first value when a header is repeated", () => {
    expect(headerValue({ "ce-id": [Buffer.from("first"), Buffer.from("second")] }, "ce-id")).toBe("first");
  });

  it("is an empty string, not the word undefined, when the header is absent", () => {
    expect(headerValue({ "ce-type": Buffer.from("x") }, "ce-correlationid")).toBe("");
    expect(headerValue(undefined, "ce-correlationid")).toBe("");
  });
});
