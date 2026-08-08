import { describe, expect, it } from "vitest";
import { line, parseFlags } from "../src/cli.js";

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
