/**
 * 平台判定：自绘标题栏按平台切换布局。
 *
 * - 先用 userAgent 给一份同步兜底值，保证首帧渲染就有正确的分支
 *   （浏览器预览里也能看到接近真实的标题栏）；
 * - 应用启动后调后端的 `get_platform_info` 覆盖成编译期 target 的权威结果
 *   （后端用的是 `cfg!(target_os)`，比 parse WebView 的 UA 可靠）。
 */

import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";

import { isTauri } from "./api";

export type Os = "windows" | "macos" | "linux";

export interface PlatformInfo {
  os: Os;
  /** 架构，例如 `aarch64` / `x86_64`；仅浏览器预览时为空串。 */
  arch: string;
}

function fromUserAgent(): PlatformInfo {
  const ua = typeof navigator === "undefined" ? "" : navigator.userAgent;
  let os: Os = "linux";
  if (/Windows/i.test(ua)) os = "windows";
  else if (/Macintosh|Mac OS X/i.test(ua)) os = "macos";
  return { os, arch: "" };
}

let cached: PlatformInfo | null = null;
let loading: Promise<PlatformInfo> | null = null;

/** 读取平台信息（只请求一次；非 Tauri 环境直接用 UA 兜底）。 */
export function loadPlatform(): Promise<PlatformInfo> {
  if (cached) return Promise.resolve(cached);
  if (!isTauri()) {
    cached = fromUserAgent();
    return Promise.resolve(cached);
  }
  loading ??= invoke<PlatformInfo>("get_platform_info")
    .then((info) => {
      cached = info;
      return info;
    })
    .catch(() => {
      cached = fromUserAgent();
      return cached;
    });
  return loading;
}

/** React 侧使用：首帧先用兜底值，拿到后端结果后自动重渲染。 */
export function usePlatform(): PlatformInfo {
  const [info, setInfo] = useState<PlatformInfo>(
    () => cached ?? fromUserAgent(),
  );

  useEffect(() => {
    let alive = true;
    void loadPlatform().then((next) => {
      if (alive) setInfo(next);
    });
    return () => {
      alive = false;
    };
  }, []);

  return info;
}
