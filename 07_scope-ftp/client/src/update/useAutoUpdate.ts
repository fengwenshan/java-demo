import { App as AntdApp } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { relaunch } from "@tauri-apps/plugin-process";
import { check } from "@tauri-apps/plugin-updater";

import { nextUpdateCheck, shouldCheckOnStartup } from "./schedule";

const LAST_CHECK_KEY = "scopeftp.update.lastCheck";
const NOTIFICATION_KEY = "scopeftp-update";

export type UpdatePhase =
  | "idle"
  | "checking"
  | "downloading"
  | "ready"
  | "error";

export interface AutoUpdateState {
  phase: UpdatePhase;
  availableVersion: string | null;
  checkNow: (manual?: boolean) => Promise<void>;
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 每日 10:00 / 16:00 检查更新，启动时按 6 小时间隔补检。
 * 发现更新后自动下载安装，完成后让用户确认是否重启。
 */
export function useAutoUpdate(enabled: boolean): AutoUpdateState {
  const { notification, modal } = AntdApp.useApp();
  const [phase, setPhase] = useState<UpdatePhase>("idle");
  const [availableVersion, setAvailableVersion] = useState<string | null>(null);
  const busyRef = useRef(false);
  const phaseRef = useRef<UpdatePhase>("idle");
  const nextTimerRef = useRef<number | null>(null);
  const nextAtRef = useRef<number>(0);

  const setUpdatePhase = useCallback((next: UpdatePhase) => {
    phaseRef.current = next;
    setPhase(next);
  }, []);

  const runCheck = useCallback(
    async (manual = false) => {
      if (!enabled || busyRef.current) return;
      busyRef.current = true;
      setUpdatePhase("checking");
      let installed = false;
      try {
        const update = await check();
        if (!update) {
          if (manual) {
            notification.info({
              message: "当前已是最新版本",
              duration: 3,
            });
          }
          return;
        }

        setAvailableVersion(update.version);
        setUpdatePhase("downloading");
        notification.info({
          key: NOTIFICATION_KEY,
          message: `发现新版本 ${update.version}`,
          description: "正在后台下载，完成后会提示重启",
          duration: 0,
        });

        let downloaded = 0;
        let total: number | undefined;
        let lastPercent = -1;
        await update.downloadAndInstall(
          (event) => {
            if (event.event === "Started") {
              total = event.data.contentLength;
              downloaded = 0;
            } else if (event.event === "Progress") {
              downloaded += event.data.chunkLength;
              const percent = total
                ? Math.min(99, Math.floor((downloaded / total) * 100))
                : null;
              if (percent !== null && percent !== lastPercent) {
                lastPercent = percent;
                notification.info({
                  key: NOTIFICATION_KEY,
                  message: `正在下载 ${update.version}`,
                  description: `${percent}%`,
                  duration: 0,
                });
              }
            } else if (event.event === "Finished") {
              installed = true;
              setUpdatePhase("ready");
              notification.success({
                key: NOTIFICATION_KEY,
                message: `新版本 ${update.version} 已安装`,
                description: "重启客户端后生效",
                duration: 0,
              });
              modal.confirm({
                title: "更新已就绪",
                content: "新版本已安装，现在重启生效吗？",
                okText: "立即重启",
                cancelText: "稍后",
                onOk: () => relaunch(),
              });
            }
          },
          { restartAfterInstall: false },
        );
      } catch (error) {
        setUpdatePhase("error");
        if (manual) {
          notification.error({
            message: "检查更新失败",
            description: errorText(error),
          });
        }
      } finally {
        localStorage.setItem(LAST_CHECK_KEY, String(Date.now()));
        busyRef.current = false;
        if (!installed) setUpdatePhase("idle");
      }
    },
    [enabled, modal, notification, setUpdatePhase],
  );

  useEffect(() => {
    if (!enabled) return;

    const lastCheck = Number(localStorage.getItem(LAST_CHECK_KEY) ?? "0") || null;
    if (shouldCheckOnStartup(Date.now(), lastCheck)) {
      void runCheck(false);
    }

    const armNext = () => {
      if (nextTimerRef.current !== null) {
        window.clearTimeout(nextTimerRef.current);
      }
      const nextAt = nextUpdateCheck().getTime();
      nextAtRef.current = nextAt;
      nextTimerRef.current = window.setTimeout(
        () => {
          void runCheck(false).finally(armNext);
        },
        Math.max(0, nextAt - Date.now()),
      );
    };
    armNext();

    // 系统休眠/窗口恢复后，如果错过了定时点，补检一次。
    const catchUp = () => {
      if (Date.now() >= nextAtRef.current) {
        void runCheck(false).finally(armNext);
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
  }, [enabled, runCheck]);

  return { phase, availableVersion, checkNow: runCheck };
}
