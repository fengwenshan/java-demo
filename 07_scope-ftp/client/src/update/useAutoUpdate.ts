import { App as AntdApp } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { relaunch } from "@tauri-apps/plugin-process";
import { check } from "@tauri-apps/plugin-updater";

import { nextUpdateCheck, shouldCheckOnStartup } from "./schedule";

const LAST_CHECK_KEY = "scopeftp.update.lastCheck";

export type UpdatePhase =
  | "idle"
  | "checking"
  | "available"
  | "downloading"
  | "ready"
  | "error";

export interface AutoUpdateState {
  phase: UpdatePhase;
  /** 检测到的新版本号；null 表示当前没有待处理的更新。 */
  version: string | null;
  /** 下载进度 0-100。 */
  percent: number;
  /** 最近一次失败原因（用于按钮 tooltip）。 */
  error: string | null;
  /** 检查一次更新；manual 为 true 时会提示「已是最新 / 检查失败」。 */
  checkNow: (manual?: boolean) => Promise<void>;
  /** 下载并安装已检测到的新版本，完成后按钮变成「重启生效」。 */
  installNow: () => Promise<void>;
  /** 重启应用，让已安装的更新生效。 */
  restart: () => Promise<void>;
}

type PendingUpdate = NonNullable<Awaited<ReturnType<typeof check>>>;

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 每日 10:00 / 16:00 静默检查更新，启动时按 6 小时间隔补检。
 *
 * 只负责「检测 + 下载安装 + 重启」的状态机，不自动安装：
 * 检测到新版本后由标题栏右上角的「更新」按钮触发下载，
 * 没有更新时标题栏不显示任何东西。
 */
export function useAutoUpdate(enabled: boolean): AutoUpdateState {
  const { message } = AntdApp.useApp();
  const [phase, setPhase] = useState<UpdatePhase>("idle");
  const [version, setVersion] = useState<string | null>(null);
  const [percent, setPercent] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const phaseRef = useRef<UpdatePhase>("idle");
  const pendingRef = useRef<PendingUpdate | null>(null);
  const busyRef = useRef(false);
  const nextTimerRef = useRef<number | null>(null);
  const nextAtRef = useRef(0);

  const setUpdatePhase = useCallback((next: UpdatePhase) => {
    phaseRef.current = next;
    setPhase(next);
  }, []);

  const checkNow = useCallback(
    async (manual = false) => {
      if (!enabled || busyRef.current) return;
      // 已经找到待安装的版本、正在下载或已下载待重启时不再检查，
      // 否则会把右上角已有的按钮状态覆盖掉。
      if (
        phaseRef.current === "available" ||
        phaseRef.current === "downloading" ||
        phaseRef.current === "ready"
      ) {
        return;
      }

      busyRef.current = true;
      setUpdatePhase("checking");
      setError(null);
      try {
        const update = await check();
        if (!update) {
          pendingRef.current = null;
          setVersion(null);
          setUpdatePhase("idle");
          if (manual) {
            message.info("当前已是最新版本");
          }
          return;
        }
        pendingRef.current = update;
        setVersion(update.version);
        setPercent(0);
        setUpdatePhase("available");
      } catch (err) {
        pendingRef.current = null;
        setVersion(null);
        setError(errorText(err));
        // 自动检查失败保持安静（避免离线时打扰），手动检查才提示。
        setUpdatePhase(manual ? "error" : "idle");
        if (manual) {
          message.error(`检查更新失败：${errorText(err)}`);
        }
      } finally {
        localStorage.setItem(LAST_CHECK_KEY, String(Date.now()));
        busyRef.current = false;
      }
    },
    [enabled, message, setUpdatePhase],
  );

  const installNow = useCallback(async () => {
    if (!enabled || busyRef.current) return;
    const update = pendingRef.current;
    if (!update) return;

    busyRef.current = true;
    setError(null);
    setPercent(0);
    setUpdatePhase("downloading");

    let downloaded = 0;
    let total: number | undefined;
    try {
      await update.downloadAndInstall(
        (event) => {
          if (event.event === "Started") {
            total = event.data.contentLength;
            downloaded = 0;
            setPercent(0);
          } else if (event.event === "Progress") {
            downloaded += event.data.chunkLength;
            if (total) {
              setPercent(
                Math.min(99, Math.floor((downloaded / total) * 100)),
              );
            }
          } else if (event.event === "Finished") {
            setPercent(100);
          }
        },
        { restartAfterInstall: false },
      );
      setUpdatePhase("ready");
      message.success(`新版本 v${update.version} 已安装，重启后生效`);
    } catch (err) {
      setError(errorText(err));
      setUpdatePhase("error");
      message.error(`更新失败：${errorText(err)}`);
    } finally {
      busyRef.current = false;
    }
  }, [enabled, message, setUpdatePhase]);

  const restart = useCallback(async () => {
    try {
      await relaunch();
    } catch (err) {
      message.error(`重启失败：${errorText(err)}`);
    }
  }, [message]);

  useEffect(() => {
    if (!enabled) return;

    const lastCheck = Number(localStorage.getItem(LAST_CHECK_KEY) ?? "0") || null;
    if (shouldCheckOnStartup(Date.now(), lastCheck)) {
      void checkNow(false);
    }

    const armNext = () => {
      if (nextTimerRef.current !== null) {
        window.clearTimeout(nextTimerRef.current);
      }
      const nextAt = nextUpdateCheck().getTime();
      nextAtRef.current = nextAt;
      nextTimerRef.current = window.setTimeout(
        () => {
          void checkNow(false).finally(armNext);
        },
        Math.max(0, nextAt - Date.now()),
      );
    };
    armNext();

    // 系统休眠/窗口恢复后，如果错过了定时点，补检一次。
    const catchUp = () => {
      if (Date.now() >= nextAtRef.current) {
        void checkNow(false).finally(armNext);
      }
    };
    window.addEventListener("focus", catchUp);
    document.addEventListener("visibilitychange", catchUp);

    return () => {
      if (nextTimerRef.current !== null) {
        window.clearTimeout(nextTimerRef.current);
        nextTimerRef.current = null;
      }
      window.removeEventListener("focus", catchUp);
      document.removeEventListener("visibilitychange", catchUp);
    };
  }, [enabled, checkNow]);

  return {
    phase,
    version,
    percent,
    error,
    checkNow,
    installNow,
    restart,
  };
}
