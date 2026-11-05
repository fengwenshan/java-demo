import { describe, expect, it } from "vitest";

import { updateButtonState } from "./button";

describe("updateButtonState", () => {
  it("没有待处理更新时整块隐藏", () => {
    expect(updateButtonState({ phase: "idle", version: null, percent: 0, error: null }).visible).toBe(false);
    expect(updateButtonState({ phase: "checking", version: null, percent: 0, error: null }).visible).toBe(false);
  });

  it("检查更新失败（没有版本号）时不显示按钮", () => {
    const state = updateButtonState({ phase: "error", version: null, percent: 0, error: "超时" });
    expect(state.visible).toBe(false);
  });

  it("检测到新版本时显示「更新」，并带上目标版本号", () => {
    const state = updateButtonState({ phase: "available", version: "1.0.4", percent: 0, error: null });
    expect(state).toMatchObject({ visible: true, label: "更新", busy: false });
    expect(state.title).toContain("v1.0.4");
  });

  it("下载中显示进度且不可点击", () => {
    const state = updateButtonState({ phase: "downloading", version: "1.0.4", percent: 42.7, error: null });
    expect(state).toMatchObject({ visible: true, label: "下载中 42%", busy: true });
  });

  it("下载进度按 0-100 截断", () => {
    expect(updateButtonState({ phase: "downloading", version: "1.0.4", percent: 130, error: null }).label).toBe("下载中 100%");
    expect(updateButtonState({ phase: "downloading", version: "1.0.4", percent: -5, error: null }).label).toBe("下载中 0%");
  });

  it("安装完成后提示重启", () => {
    const state = updateButtonState({ phase: "ready", version: "1.0.4", percent: 100, error: null });
    expect(state).toMatchObject({ visible: true, label: "重启生效", busy: false });
  });

  it("下载失败保留版本号时显示「重试」，并带上错误原因", () => {
    const state = updateButtonState({ phase: "error", version: "1.0.4", percent: 0, error: "网络中断" });
    expect(state).toMatchObject({ visible: true, label: "重试", busy: false });
    expect(state.title).toContain("网络中断");
  });
});
