import { useCallback, useEffect, useState } from "react";
import { Button, Tooltip } from "antd";
import { getCurrentWindow } from "@tauri-apps/api/window";

import appIcon from "../assets/app-icon.png";
import { isTauri } from "../api";
import { usePlatform } from "../platform";
import { updateButtonState } from "../update/button";
import type { AutoUpdateState } from "../update/useAutoUpdate";

export interface TitleBarProps {
  update: AutoUpdateState;
}

/**
 * 自绘标题栏：左侧品牌、右侧更新按钮（仅在有更新时出现）与窗口按钮。
 *
 * - macOS 走 Tauri 的 Overlay 标题栏，系统红绿灯保留，所以左侧留白；
 * - Windows 去掉了原生边框，最小化/最大化/关闭由这里的窗口按钮负责；
 * - Linux 保留系统边框，只显示品牌和更新按钮。
 */
export default function TitleBar({ update }: TitleBarProps) {
  const platform = usePlatform();
  const desktop = isTauri();
  const isMacos = desktop && platform.os === "macos";
  const isWindows = desktop && platform.os === "windows";
  const [maximized, setMaximized] = useState(false);

  const button = updateButtonState({
    phase: update.phase,
    version: update.version,
    percent: update.percent,
    error: update.error,
  });

  useEffect(() => {
    if (!isWindows) return;
    const win = getCurrentWindow();
    let disposed = false;
    let unlisten: (() => void) | undefined;

    void win
      .isMaximized()
      .then((value) => {
        if (!disposed) setMaximized(value);
      })
      .catch(() => undefined);

    void win
      .onResized(() => {
        void win
          .isMaximized()
          .then((value) => {
            if (!disposed) setMaximized(value);
          })
          .catch(() => undefined);
      })
      .then((fn) => {
        if (disposed) fn();
        else unlisten = fn;
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [isWindows]);

  const handleUpdateClick = useCallback(() => {
    if (update.phase === "available" || update.phase === "error") {
      void update.installNow();
    } else if (update.phase === "ready") {
      void update.restart();
    }
  }, [update]);

  const minimize = useCallback(() => {
    if (!isWindows) return;
    void getCurrentWindow()
      .minimize()
      .catch(() => undefined);
  }, [isWindows]);

  const toggleMaximize = useCallback(() => {
    if (!isWindows) return;
    void getCurrentWindow()
      .toggleMaximize()
      .catch(() => undefined);
  }, [isWindows]);

  const close = useCallback(() => {
    if (!isWindows) return;
    // CloseRequested 在 Rust 侧被拦截为「隐藏到托盘」，与点原生关闭按钮一致。
    void getCurrentWindow()
      .close()
      .catch(() => undefined);
  }, [isWindows]);

  return (
    <header
      className={`app-titlebar${isMacos ? " app-titlebar-macos" : ""}${
        isWindows ? " app-titlebar-windows" : ""
      }`}
      data-tauri-drag-region={isMacos ? "deep" : undefined}
      data-testid="app-titlebar"
    >
      {isWindows && <div className="titlebar-drag-layer" data-tauri-drag-region />}

      <div className="titlebar-brand">
        <img
          className="titlebar-icon"
          src={appIcon}
          alt=""
          draggable={false}
        />
        <span className="titlebar-name">ScopeFTP</span>
      </div>

      <div className="titlebar-actions">
        {button.visible && (
          <Tooltip title={button.title}>
            <Button
              type="primary"
              size="small"
              className="titlebar-update"
              autoInsertSpace={false}
              disabled={button.busy}
              onClick={handleUpdateClick}
              data-testid="update-button"
            >
              {button.label}
            </Button>
          </Tooltip>
        )}
      </div>

      {isWindows && (
        <div className="window-controls" aria-label="窗口控制">
          <button
            type="button"
            className="window-control"
            title="最小化"
            aria-label="最小化"
            data-testid="window-minimize"
            onClick={minimize}
          >
            <svg viewBox="0 0 12 12" aria-hidden="true">
              <path d="M2 6h8" />
            </svg>
          </button>
          <button
            type="button"
            className="window-control"
            title={maximized ? "还原" : "最大化"}
            aria-label={maximized ? "还原" : "最大化"}
            data-testid="window-maximize"
            onClick={toggleMaximize}
          >
            <svg viewBox="0 0 12 12" aria-hidden="true">
              {maximized ? (
                <>
                  <rect x="2.2" y="3.2" width="6" height="6" rx="0.6" />
                  <path d="M4.2 3.2V2.1h5.7v5.7H8.8" />
                </>
              ) : (
                <rect x="2" y="2" width="8" height="8" rx="0.6" />
              )}
            </svg>
          </button>
          <button
            type="button"
            className="window-control window-control-close"
            title="关闭"
            aria-label="关闭"
            data-testid="window-close"
            onClick={close}
          >
            <svg viewBox="0 0 12 12" aria-hidden="true">
              <path d="M2.5 2.5l7 7M9.5 2.5l-7 7" />
            </svg>
          </button>
        </div>
      )}
    </header>
  );
}
