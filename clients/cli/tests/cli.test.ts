import { describe, expect, it } from "vitest";
import { line, parseFlags } from "../src/cli.js";
import { positionals } from "../src/api.js";
import { tournamentMode } from "../src/bot.js";

describe("cli flag parsing", () => {
  it("parses value flags and boolean flags", () => {
    const f = parseFlags(["--user", "alice", "--pass", "pw", "--json"]);
    expect(f.user).toBe("alice");
    expect(f.pass).toBe("pw");
    expect(f.json).toBe(true);
  });

  it("treats a trailing flag as boolean", () => {
    expect(parseFlags(["--json"]).json).toBe(true);
  });

  it("parses the seed flags", () => {
    const f = parseFlags(["--count", "5", "--prefix", "drill", "--json"]);
    expect(f.count).toBe("5");
    expect(f.prefix).toBe("drill");
  });
});

describe("output contract", () => {
  // Client-Checkpoint.md §6: the faculty parses one shape across every command, so a field that
  // does not apply must be present and null rather than absent.
  const REQUIRED = ["ts", "action", "room", "player", "latency_ms", "result", "error_code", "seq", "correlationId"];

  it("emits every required field, nulling the ones that do not apply", () => {
    const l = line({ action: "whoami", result: "ok" });
    for (const field of REQUIRED) expect(l).toHaveProperty(field);
    expect(l.room).toBeNull();
    expect(l.seq).toBeNull();
    expect(l.error_code).toBeNull();
  });

  it("keeps the caller's values over the defaults", () => {
    const l = line({ action: "login", result: "error", error_code: 401, latency_ms: 12 });
    expect(l.result).toBe("error");
    expect(l.error_code).toBe(401);
    expect(l.latency_ms).toBe(12);
  });
});

describe("canonical positional arguments (§5)", () => {
  // The faculty drives this CLI from their own document, where the forms are positional:
  // `spectate <roomId>`, `tournament status <id>`, `bot --tournament <tournamentId>`.
  it("does not mistake a flag's value for a positional", () => {
    expect(positionals(["--timeout", "30", "7"])).toEqual(["7"]);
    expect(positionals(["7", "--timeout", "30"])).toEqual(["7"]);
  });

  it("keeps a trailing boolean flag out of the positionals", () => {
    expect(positionals(["status", "12", "--json"])).toEqual(["status", "12"]);
  });

  it("reads the id from the canonical `bot --tournament <id>`", () => {
    // parseFlags gives --tournament the id as its VALUE, so the `=== true` test this replaced was
    // false for exactly this invocation and the bot silently played a casual game instead.
    expect(tournamentMode(parseFlags(["--tournament", "7"]))).toEqual({ on: true, id: "7" });
  });

  it("still treats the bare flag as tournament mode with no id", () => {
    expect(tournamentMode(parseFlags(["--tournament"]))).toEqual({ on: true, id: undefined });
  });

  it("lets an explicit --id win, and leaves casual runs alone", () => {
    expect(tournamentMode(parseFlags(["--tournament", "7", "--id", "9"]))).toEqual({ on: true, id: "9" });
    expect(tournamentMode(parseFlags(["--casual"])).on).toBe(false);
  });
});
