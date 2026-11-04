import { describe, expect, it } from "vitest";

import { nextUpdateCheck, shouldCheckOnStartup } from "./schedule";

function at(hour: number, minute: number, day = 4): Date {
  return new Date(2026, 10, day, hour, minute, 0, 0);
}

describe("nextUpdateCheck", () => {
  it("09:59 -> 10:00", () => {
    expect(nextUpdateCheck(at(9, 59)).getHours()).toBe(10);
    expect(nextUpdateCheck(at(9, 59)).getDate()).toBe(4);
  });

  it("10:00 -> 16:00", () => {
    expect(nextUpdateCheck(at(10, 0)).getHours()).toBe(16);
  });

  it("10:01 -> 16:00", () => {
    expect(nextUpdateCheck(at(10, 1)).getHours()).toBe(16);
  });

  it("15:59 -> 16:00", () => {
    expect(nextUpdateCheck(at(15, 59)).getHours()).toBe(16);
  });

  it("16:00 -> 次日 10:00", () => {
    const next = nextUpdateCheck(at(16, 0));
    expect(next.getHours()).toBe(10);
    expect(next.getDate()).toBe(5);
  });

  it("16:01 -> 次日 10:00", () => {
    expect(nextUpdateCheck(at(16, 1)).getDate()).toBe(5);
  });
});

describe("shouldCheckOnStartup", () => {
  it("从未检测时补检", () => {
    expect(shouldCheckOnStartup(1000, null)).toBe(true);
  });

  it("6 小时内不补检", () => {
    expect(shouldCheckOnStartup(6 * 60 * 60 * 1000 - 1, 0)).toBe(false);
  });

  it("超过 6 小时补检", () => {
    expect(shouldCheckOnStartup(6 * 60 * 60 * 1000, 0)).toBe(true);
  });
});
