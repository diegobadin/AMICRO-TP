import { describe, expect, it } from "vitest";
import { normaliseInput } from "../src/rooms.js";

/**
 * P9's first two-person game: both humans typed `5` at a numbered hand, got the usage line, and
 * lost the turn to the 30-second clock. The game ended in a double forfeit with no card played.
 */
describe("what a player actually types", () => {
  it("treats a bare number as playing that card", () => {
    expect(normaliseInput("5")).toEqual(["play", "5"]);
  });

  it("keeps the colour and the uno call after a bare number", () => {
    expect(normaliseInput("7 R")).toEqual(["play", "7", "R"]);
    expect(normaliseInput("3 uno")).toEqual(["play", "3", "uno"]);
    expect(normaliseInput("7 R uno")).toEqual(["play", "7", "R", "uno"]);
  });

  it("still accepts the long form", () => {
    expect(normaliseInput("play 5")).toEqual(["play", "5"]);
    expect(normaliseInput("  play 5 R  ")).toEqual(["play", "5", "R"]);
  });

  it("expands the one-letter forms a human reached for", () => {
    expect(normaliseInput("c")).toEqual(["challenge"]);
    expect(normaliseInput("s")).toEqual(["state"]);
    expect(normaliseInput("d")).toEqual(["draw"]);
    expect(normaliseInput("u")).toEqual(["uno"]);
    expect(normaliseInput("q")).toEqual(["quit"]);
  });

  it("does not guess at `p`, which could be play or pass", () => {
    expect(normaliseInput("p")).toEqual(["p"]);
  });

  it("leaves a real command and an empty line alone", () => {
    expect(normaliseInput("challenge")).toEqual(["challenge"]);
    expect(normaliseInput("")).toEqual([""]);
  });
});
