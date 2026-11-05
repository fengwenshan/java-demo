import type { UpdatePhase } from "./useAutoUpdate";

export interface UpdateButtonState {
  /** 没有待处理的更新时整块留空，不占位。 */
  visible: boolean;
  label: string;
  title: string;
  /** 下载/安装进行中时按钮不可点。 */
  busy: boolean;
}

export interface UpdateButtonInput {
  phase: UpdatePhase;
  /** 检测到的新版本号；null 表示没有待处理的更新。 */
  version: string | null;
  /** 下载进度 0-100。 */
  percent: number;
  /** 最近一次失败原因。 */
  error: string | null;
}

/**
 * 右上角更新按钮的显示状态（纯函数，便于单测）。
 *
 * 只有「检测到新版本 / 下载中 / 已安装待重启 / 下载失败可重试」这几种情况才有按钮；
 * 平时（idle、checking、已是最新）不显示，标题栏右侧保持留空。
 */
export function updateButtonState(input: UpdateButtonInput): UpdateButtonState {
  const { phase, version, percent, error } = input;

  switch (phase) {
    case "available":
      return {
        visible: true,
        label: "更新",
        title: version ? `有新版本 v${version}，点击更新` : "有新版本，点击更新",
        busy: false,
      };
    case "downloading":
      return {
        visible: true,
        label: `下载中 ${Math.floor(Math.max(0, Math.min(100, percent)))}%`,
        title: "正在下载更新，请稍候",
        busy: true,
      };
    case "ready":
      return {
        visible: true,
        label: "重启生效",
        title: "更新已安装，重启应用后生效",
        busy: false,
      };
    case "error":
      // 检查更新失败（还没拿到版本号）不显示按钮，避免标题栏常驻一个「重试」；
      // 下载/安装失败时保留按钮，给用户一个重试入口。
      if (!version) {
        return { visible: false, label: "", title: "", busy: false };
      }
      return {
        visible: true,
        label: "重试",
        title: error ? `更新失败：${error}` : "更新失败，点击重试",
        busy: false,
      };
    default:
      return { visible: false, label: "", title: "", busy: false };
  }
}
