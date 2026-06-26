import { describe, expect, it } from "vitest";
import { parseFlags } from "../src/cli.js";

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
});
