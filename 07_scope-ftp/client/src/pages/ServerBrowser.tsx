import "../monaco-setup";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
} from "react";
import Editor, { type OnMount } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import {
  Alert,
  App as AntdApp,
  Breadcrumb,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Modal,
  Progress,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import type { BreadcrumbProps, InputRef } from "antd";
import type { ColumnsType } from "antd/es/table";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import {
  ArrowLeftOutlined,
  CodeOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FileOutlined,
  FileZipOutlined,
  FolderOutlined,
  FolderOpenOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  HistoryOutlined,
  InboxOutlined,
  ReloadOutlined,
  SearchOutlined,
  SettingOutlined,
  UploadOutlined,
} from "@ant-design/icons";

import {
  copyServerPath,
  deleteServerFile,
  downloadServerFile,
  errorMessage,
  extractZipFile,
  getLocalFileInfo,
  isTauri,
  listAuditLogs,
  listServerDir,
  listenUploadProgress,
  moveServerPath,
  openServerFile,
  readServerFileContent,
  pickFile,
  pickSavePath,
  renameServerFile,
  saveServerFileContent,
  uploadFile,
} from "../api";
import type {
  AuditLogCategory,
  AuditLogItem,
  DirEntry,
  DirList,
  SearchEntry,
  StorageRoot,
  UploadProgress,
} from "../types";

/** 在线编辑器状态（task-23）。 */
interface EditorState {
  entry: DirEntry;
  loading: boolean;
  error: string | null;
  content: string;
  /** 打开时的内容哈希，保存时作为 expectedSha256。 */
  sha256: string;
  size: number;
  /** 打开时的内容，用于判断未保存修改。 */
  originalContent: string;
  /** 当前 Monaco 语言（按扩展名自动识别，可手动切换）。 */
  language: string;
}
/** 「移动到…」弹窗状态（task-40）。 */
interface MoveDialogState {
  entry: DirEntry;
  sourceRootId: string | null;
  targetRootId: string | null;
  targetPath: string;
  entries: DirEntry[] | null;
  loading: boolean;
  error: string | null;
  submitting: boolean;
}

/** 日志筛选状态（task-45）。 */
interface AuditFilters {
  category: AuditLogCategory;
  action: string | null;
  from: string | null;
  to: string | null;
}

const EMPTY_AUDIT_FILTERS: AuditFilters = {
  category: "both",
  action: null,
  from: null,
  to: null,
};

/** 常用操作 action → 中文名；未知 action 原样显示。 */
const AUDIT_ACTION_LABELS: Record<string, string> = {
  "auth.verify": "验证连接",
  "auth.activate": "激活设备",
  "roots.list": "存储根列表",
  "files.list": "文件列表",
  "dir.list": "目录列表",
  search: "搜索",
  download: "下载",
  "download.byId": "按 ID 下载",
  upload: "上传",
  "upload.init": "初始化上传",
  "upload.chunk": "上传分片",
  "upload.complete": "完成上传",
  "upload.status": "上传状态",
  "upload.cancel": "取消上传",
  delete: "删除",
  rename: "重命名",
  copy: "拷贝",
  move: "移动",
  extract: "解压",
  "content.read": "读取内容",
  "content.save": "保存内容",
  "terminal.exec": "终端命令",
  "audit.list": "查看日志",
};

/** 合并日志只展示实际操作与连接事件，不混入列表/搜索等接口请求。 */
const AUDIT_OPERATION_ACTIONS = [
  "upload",
  "upload.complete",
  "download",
  "download.byId",
  "delete",
  "rename",
  "copy",
  "move",
  "extract",
  "content.read",
  "content.save",
  "terminal.exec",
] as const;

/** 连接日志：连接验证、设备激活。 */
const AUDIT_CONNECTION_ACTIONS = ["auth.verify", "auth.activate"] as const;

const toAuditOptions = (actions: readonly string[]) =>
  actions.map((value) => ({
    value,
    label: AUDIT_ACTION_LABELS[value] ?? value,
  }));

/** 按日志类型给出操作筛选项；both 时合并展示。 */
const AUDIT_ACTION_OPTIONS: Record<
  AuditLogCategory,
  { value: string; label: string }[]
> = {
  operation: toAuditOptions(AUDIT_OPERATION_ACTIONS),
  connection: toAuditOptions(AUDIT_CONNECTION_ACTIONS),
  both: toAuditOptions([...AUDIT_OPERATION_ACTIONS, ...AUDIT_CONNECTION_ACTIONS]),
};

function auditActionLabel(action: string | null | undefined): string {
  if (!action) return "未知操作";
  return AUDIT_ACTION_LABELS[action] ?? action;
}

/** 日志时间转本地 `yyyy-MM-dd HH:mm:ss`；无法解析时原样返回。 */
function formatAuditTime(ts: string | null | undefined): string {
  if (!ts) return "-";
  const date = new Date(ts);
  if (Number.isNaN(date.getTime())) return ts;
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate(),
  )} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(
    date.getSeconds(),
  )}`;
}

function auditObjectSummary(item: AuditLogItem): string {
  const fileName = item.fileName?.trim() || "";
  const pathParam = item.pathParam?.trim() || "";
  const targetDir = item.targetDir?.trim() || "";
  const targetRoot = item.targetRoot?.trim() || "";
  const command = item.terminalCommand?.trim() || "";

  if (item.action === "terminal.exec") {
    return command || "-";
  }
  if (item.action === "auth.verify") {
    return "连接验证";
  }
  if (item.action === "auth.activate") {
    return "设备激活";
  }
  if (item.action === "upload" || item.action === "upload.complete") {
    return fileName || targetDir || "上传文件";
  }
  if (item.action === "rename" || item.action === "copy") {
    if (pathParam && fileName) return `${pathParam} → ${fileName}`;
    return fileName || pathParam || "-";
  }
  if (item.action === "move") {
    const destination = `${targetRoot ? `${targetRoot}:` : ""}${targetDir ? `${targetDir}/` : ""}${fileName}`;
    if (pathParam && destination) return `${pathParam} → ${destination}`;
    return destination || pathParam || "-";
  }
  if (item.action === "extract") {
    if (pathParam && targetDir) return `${pathParam} → ${targetDir}`;
    return targetDir || pathParam || "-";
  }
  return fileName || pathParam || targetDir || item.sessionId || "-";
}

function auditDisplay(value: unknown): string {
  if (value === null || value === undefined || value === "") return "-";
  if (typeof value === "boolean") return value ? "是" : "否";
  return String(value);
}

import {
  createId,
  fileExtension,
  fileKindLabel,
  formatBytes,
  formatFinderDate,
} from "../utils";
import QuickOpen from "../components/QuickOpen";
import TerminalPanel from "../components/TerminalPanel";
import {
  EDITOR_LANGUAGES,
  detectEditorLanguage,
} from "../editorLanguages";

const { Text, Link } = Typography;

/** 右键菜单尺寸（用于视口边缘避让）。 */
const CONTEXT_MENU_WIDTH = 168;
/** 单个菜单项高度（约）。 */
const CONTEXT_MENU_ITEM_HEIGHT = 33;
/** 菜单上下 padding。 */
const CONTEXT_MENU_PADDING = 16;

/** 按条目类型计算右键菜单条目数（含拷贝/移动到…，task-40）。 */
function contextMenuItemCount(entry: DirEntry): number {
  if (entry.type !== "file") return 5;
  const isZip = fileExtension(entry.name) === "zip";
  return isZip ? 8 : 7;
}

/** 右键菜单实际高度（用于边缘避让，避免菜单溢出视口）。 */
function contextMenuHeight(entry: DirEntry): number {
  return CONTEXT_MENU_PADDING + contextMenuItemCount(entry) * CONTEXT_MENU_ITEM_HEIGHT;
}

/** Monaco 缩进宽度偏好（2/4 空格，默认 4，task-37）。 */
const EDITOR_TAB_SIZE_KEY = "secure-upload:editor-tab-size";

function loadEditorTabSize(): number {
  try {
    const value = Number(window.localStorage.getItem(EDITOR_TAB_SIZE_KEY));
    if (value === 2 || value === 4) return value;
  } catch {
    // localStorage 不可用时使用默认值。
  }
  return 4;
}

/** 校验重命名的单段名称；返回错误提示（合法时返回 null）。 */
function validateSingleName(raw: string): string | null {
  const name = raw.trim();
  if (!name) return "名称不能为空";
  if (name === "." || name === "..") return "名称不能是 . 或 ..";
  if (name.includes("/") || name.includes("\\")) return "名称不能包含 / 或 \\";
  if (/[\u0000-\u001f\u007f]/.test(name)) return "名称不能包含控制字符";
  return null;
}

export interface ServerBrowserProps {
  configured: boolean;
  serverName?: string;
  /** 当前存储根 id（task-16）；null 表示旧单根服务端的默认根。 */
  rootId: string | null;
  /** 当前存储根名称，用于面包屑首段。 */
  rootName?: string | null;
  /** 当前存储根绝对路径；用于把相对目录拼成终端 cwd（单根旧服务端可能为 null）。 */
  rootPath?: string | null;
  /** 全部存储根（task-40：移动弹窗跨根切换；旧单根服务端为空数组）。 */
  roots: StorageRoot[];
  onGoSettings: () => void;
}

/** 把存储根绝对路径与相对目录拼成绝对路径；rootPath 缺失时返回 null。 */
export function joinRootPath(
  rootPath: string | null | undefined,
  relativePath: string,
): string | null {
  if (!rootPath) {
    return null;
  }
  const base = rootPath.replace(/\/+$/, "");
  const relative = relativePath.replace(/^\/+/, "");
  return relative ? `${base}/${relative}` : base;
}

/**
 * 右侧目录浏览器：面包屑/刷新/上传 + 表格操作 + 拖拽上传 +
 * 存储根目录递归搜索（防抖 300ms、点击结果跳转所在目录并高亮）。
 */
export default function ServerBrowser({
  configured,
  serverName,
  rootId,
  rootName,
  rootPath,
  roots,
  onGoSettings,
}: ServerBrowserProps) {
  const { message, modal } = AntdApp.useApp();
  const desktop = isTauri();
  const [currentPath, setCurrentPath] = useState("");

  // 切换服务器时 App 会以 server:root 为 key 重建整个浏览器；这里检测服务器变化并提示
  // 终端会话/历史已随旧页面销毁（task-30）。
  useEffect(() => {
    const storageKey = "secure-upload:terminal-server";
    try {
      const previous = window.sessionStorage.getItem(storageKey);
      if (previous && serverName && previous !== serverName) {
        message.info("已切换服务器，终端会话与历史已重置");
      }
      if (serverName) {
        window.sessionStorage.setItem(storageKey, serverName);
      }
    } catch {
      // sessionStorage 不可用时静默忽略（不影响功能）。
    }
  }, [message, serverName]);
  const [dir, setDir] = useState<DirList | null>(null);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(
    null,
  );
  const [uploadIndex, setUploadIndex] = useState(0);
  const [uploadTotal, setUploadTotal] = useState(0);
  const [downloadingPath, setDownloadingPath] = useState<string | null>(null);
  const [deletingPath, setDeletingPath] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [highlightPath, setHighlightPath] = useState<string | null>(null);
  const [quickOpenOpen, setQuickOpenOpen] = useState(false);
  const [terminalOpen, setTerminalOpen] = useState(false);
  /** 打开终端时希望定位到的绝对 cwd（右键文件/目录、工具栏当前目录）。 */
  const [terminalInitialCwd, setTerminalInitialCwd] = useState<string | null>(
    null,
  );
  /** 每次「打开/定位终端」递增；TerminalPanel 据此新建 session 并清屏。 */
  const [terminalOpenToken, setTerminalOpenToken] = useState(0);
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
    entry: DirEntry;
  } | null>(null);
  /** Finder 式选中行（task-26）。 */
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  /** 与 selectedPath 同步的 ref：连续单击判定用（state 更新滞后会误判）。 */
  const selectedPathRef = useRef<string | null>(null);
  const selectFinderPath = useCallback((path: string | null) => {
    selectedPathRef.current = path;
    setSelectedPath(path);
  }, []);
  /** 正在用默认应用打开的文件路径（防重复双击）。 */
  const [openingPath, setOpeningPath] = useState<string | null>(null);
  /** 行内重命名（task-20）：正在编辑的行路径、输入值、提交中状态。 */
  const [renamingPath, setRenamingPath] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [renaming, setRenaming] = useState(false);
  /** 解压中的 zip 路径（菜单项 loading/禁用）。 */
  const [extractingPath, setExtractingPath] = useState<string | null>(null);
  /** 拷贝中的路径（task-40：菜单项 loading/禁用）。 */
  const [copyingPath, setCopyingPath] = useState<string | null>(null);
  /** 「移动到…」弹窗（task-40）。 */
  const [moveDialog, setMoveDialog] = useState<MoveDialogState | null>(null);
  const moveLoadSeqRef = useRef(0);
  /** 日志 Drawer（task-45）。 */
  const [auditOpen, setAuditOpen] = useState(false);
  const [auditFullscreen, setAuditFullscreen] = useState(false);
  const [auditItems, setAuditItems] = useState<AuditLogItem[]>([]);
  const [auditTotal, setAuditTotal] = useState(0);
  const [auditPage, setAuditPage] = useState(1);
  const [auditSize, setAuditSize] = useState(50);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditFilters, setAuditFilters] =
    useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  /** RangePicker 非受控；切换/重置时通过 key 重建以清空选择。 */
  const [auditRangeKey, setAuditRangeKey] = useState(0);
  const [auditDetail, setAuditDetail] = useState<AuditLogItem | null>(null);
  const auditSeqRef = useRef(0);
  /** 在线编辑器（task-23）：null 表示关闭。 */
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [editorSaving, setEditorSaving] = useState(false);
  /** Monaco 缩进宽度（2/4 空格，localStorage 持久化，task-37）。 */
  const [editorTabSize, setEditorTabSize] = useState<number>(loadEditorTabSize);
  const editorBodyRef = useRef<HTMLDivElement | null>(null);
  const editorInstanceRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  /** 保存函数的最新引用：Monaco addCommand 只在 mount 时注册一次。 */
  const editorSaveRef = useRef<() => void>(() => undefined);

  const uploadIdRef = useRef<string | null>(null);
  const uploadingRef = useRef(false);
  const loadSeqRef = useRef(0);
  const tableRef = useRef<HTMLDivElement | null>(null);
  const contextMenuRef = useRef<HTMLDivElement | null>(null);
  const renameInputRef = useRef<InputRef | null>(null);
  /** Esc 取消后紧随的 blur 不应触发保存。 */
  const renameCancelledRef = useRef(false);
  /** Finder 式「再次单击进入重命名」的延迟定时器（双击会取消）。 */
  const pendingRenameTimerRef = useRef<number | null>(null);

  const load = useCallback(
    async (path: string) => {
      if (!configured) return;
      const seq = ++loadSeqRef.current;
      setLoading(true);
      try {
        const result = await listServerDir(path.trim() || undefined, rootId);
        if (seq !== loadSeqRef.current) return;
        setDir(result);
        // 以服务端返回的 path 为准，避免本地状态与服务端规范化结果不一致。
        setCurrentPath(result.path ?? path);
      } catch (error) {
        if (seq !== loadSeqRef.current) return;
        message.error(errorMessage(error));
      } finally {
        if (seq === loadSeqRef.current) setLoading(false);
      }
    },
    [configured, message, rootId],
  );

  useEffect(() => {
    if (!configured) return;
    void load("");
  }, [configured, load]);

  // 上传进度事件（契约 5.3）：只处理本组件当前上传的 uploadId。
  useEffect(() => {
    let disposed = false;
    let unlisten: (() => void) | undefined;
    listenUploadProgress((payload) => {
      if (payload.uploadId !== uploadIdRef.current) return;
      setUploadProgress(payload);
    })
      .then((stop) => {
        if (disposed) {
          stop();
        } else {
          unlisten = stop;
        }
      })
      .catch(() => {
        // 浏览器预览 / 非桌面环境没有 Tauri 事件系统，忽略。
      });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  // 搜索跳转后：定位并高亮目标行。
  useEffect(() => {
    if (!highlightPath || loading) return;
    const timer = window.setTimeout(() => {
      const container = tableRef.current;
      if (!container) return;
      const rows = container.querySelectorAll<HTMLTableRowElement>(
        "tr[data-row-key]",
      );
      for (const row of Array.from(rows)) {
        if (row.getAttribute("data-row-key") === highlightPath) {
          row.scrollIntoView({ block: "center", behavior: "smooth" });
          break;
        }
      }
    }, 80);
    return () => window.clearTimeout(timer);
  }, [dir, highlightPath, loading]);

  const clearHighlight = useCallback(() => setHighlightPath(null), []);

  /** 串行上传多个路径到当前目录；目录/空文件会被跳过并汇总提示。 */
  const uploadPaths = useCallback(
    async (paths: string[]) => {
      if (uploadingRef.current) {
        message.warning("正在上传，请稍候");
        return;
      }
      if (!isTauri()) {
        message.warning("请使用桌面客户端运行");
        return;
      }
      const dirForUpload = currentPath.trim() || null;
      const rootLabel = rootName ? `「${rootName}」` : "根目录";
      const targetLabel = currentPath
        ? `${rootLabel}/${currentPath}`
        : rootLabel;

      const files: { path: string; name: string; size: number }[] = [];
      let skipped = 0;
      for (const path of paths) {
        try {
          const info = await getLocalFileInfo(path);
          if (info.size === 0) {
            skipped += 1;
            continue;
          }
          files.push({ path, name: info.name, size: info.size });
        } catch {
          // 目录或不可读文件：跳过
          skipped += 1;
        }
      }
      if (files.length === 0) {
        message.warning(
          skipped > 0 ? "没有可上传的文件（已跳过目录/空文件）" : "未发现可上传的文件",
        );
        return;
      }

      const succeeded: string[] = [];
      const failed: { name: string; reason: string }[] = [];
      uploadingRef.current = true;
      setUploading(true);
      setUploadTotal(files.length);
      setUploadIndex(0);
      setHighlightPath(null);
      try {
        for (let index = 0; index < files.length; index += 1) {
          const file = files[index];
          const uploadId = createId();
          uploadIdRef.current = uploadId;
          setUploadIndex(index + 1);
          // 先给出 0% 的进度，随后由 upload-progress 事件持续更新。
          setUploadProgress({ uploadId, sent: 0, total: file.size });
          try {
            await uploadFile({
              path: file.path,
              description: null,
              dir: dirForUpload,
              root: rootId,
              uploadId,
            });
            succeeded.push(file.name);
          } catch (error) {
            failed.push({ name: file.name, reason: errorMessage(error) });
          } finally {
            uploadIdRef.current = null;
          }
        }
      } finally {
        uploadingRef.current = false;
        uploadIdRef.current = null;
        setUploading(false);
        setUploadProgress(null);
        setUploadIndex(0);
        setUploadTotal(0);
      }

      if (succeeded.length > 0) {
        message.success(`已上传 ${succeeded.length} 个文件到${targetLabel}`);
        // 上传成功后刷新当前目录。
        await load(currentPath);
      }
      if (failed.length > 0) {
        const detail = failed
          .slice(0, 3)
          .map((item) => `${item.name}：${item.reason}`)
          .join("；");
        message.error(
          `${failed.length} 个文件上传失败：${detail}${failed.length > 3 ? "…" : ""}`,
        );
      }
      if (skipped > 0) {
        message.info(`已跳过 ${skipped} 个目录或空文件`);
      }
    },
    [currentPath, load, message, rootId, rootName],
  );

  const handlePickUpload = useCallback(async () => {
    if (uploadingRef.current) {
      message.warning("正在上传，请稍候");
      return;
    }
    try {
      const path = await pickFile();
      if (!path) return;
      await uploadPaths([path]);
    } catch (error) {
      message.error(errorMessage(error));
    }
  }, [message, uploadPaths]);

  const handleDrop = useCallback(
    (paths: string[]) => {
      if (!configured) {
        message.warning("请先在设置中完成服务器配置");
        return;
      }
      void uploadPaths(paths);
    },
    [configured, message, uploadPaths],
  );

  // Tauri 原生拖放（enter/over 显示遮罩，drop 上传到当前目录）。
  useEffect(() => {
    if (!desktop) return;
    let disposed = false;
    let unlisten: (() => void) | undefined;

    getCurrentWebview()
      .onDragDropEvent((event) => {
        if (disposed) return;
        const type = event.payload.type;
        if (type === "enter" || type === "over") {
          setDragActive(true);
        } else if (type === "leave") {
          setDragActive(false);
        } else if (type === "drop") {
          setDragActive(false);
          const paths = event.payload.paths ?? [];
          if (paths.length > 0) handleDrop(paths);
        }
      })
      .then((stop) => {
        if (disposed) {
          stop();
        } else {
          unlisten = stop;
        }
      })
      .catch(() => {
        // 浏览器预览 / 非桌面环境忽略，仍可用「上传到此目录」。
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [desktop, handleDrop]);

  const clearPendingRenameTimer = useCallback(() => {
    if (pendingRenameTimerRef.current !== null) {
      window.clearTimeout(pendingRenameTimerRef.current);
      pendingRenameTimerRef.current = null;
    }
  }, []);

  const enter = useCallback(
    (path: string) => {
      clearPendingRenameTimer();
      selectFinderPath(null);
      setHighlightPath(null);
      if (path === currentPath) {
        void load(path);
        return;
      }
      void load(path);
    },
    [clearPendingRenameTimer, currentPath, load],
  );

  /** 快速查找选中：目录直接进入；文件跳到 parent 目录、刷新并高亮该行。 */
  const handleQuickOpenSelect = useCallback(
    async (entry: SearchEntry) => {
      setQuickOpenOpen(false);
      if (entry.type === "directory") {
        enter(entry.path);
        return;
      }
      setHighlightPath(entry.path);
      selectFinderPath(entry.path);
      await load(entry.parent ?? "");
    },
    [enter, load],
  );

  // ⌘P / Ctrl+P 打开快速查找（本组件只在目录浏览视图渲染，设置页不会响应）。
  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if (
        (event.metaKey || event.ctrlKey) &&
        !event.altKey &&
        !event.shiftKey &&
        event.key.toLowerCase() === "p"
      ) {
        event.preventDefault();
        setQuickOpenOpen(true);
      }
    };
    window.addEventListener("keydown", handleShortcut);
    return () => window.removeEventListener("keydown", handleShortcut);
  }, []);

  const handleDownloadFile = useCallback(
    async (record: DirEntry) => {
      setContextMenu(null);
      setDownloadingPath(record.path);
      try {
        const extension = fileExtension(record.name);
        const targetPath = await pickSavePath(
          record.name,
          extension ? [extension] : undefined,
        );
        if (!targetPath) return;
        const savedPath = await downloadServerFile({
          path: record.path,
          targetPath,
          root: rootId,
        });
        message.success(`已保存到 ${savedPath}`);
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setDownloadingPath(null);
      }
    },
    [message, rootId],
  );

  /** 执行删除并从服务端刷新当前目录（文件与空目录通用）。 */
  const performDelete = useCallback(
    async (record: DirEntry) => {
      setDeletingPath(record.path);
      try {
        await deleteServerFile(record.path, rootId);
        message.success(
          record.type === "directory"
            ? `已删除目录：${record.name}`
            : `已删除：${record.name}`,
        );
        setHighlightPath(null);
        if (selectedPathRef.current === record.path) selectFinderPath(null);
        await load(currentPath);
      } catch (error) {
        // 非空目录（40000）等失败直接用服务端可展示错误提示。
        message.error(errorMessage(error));
      } finally {
        setDeletingPath(null);
      }
    },
    [currentPath, load, message, rootId],
  );

  /** 右键菜单「解压」：调用服务端解压到同级同名目录并刷新当前目录。 */
  const handleExtractZip = useCallback(
    async (record: DirEntry) => {
      if (extractingPath) return;
      setContextMenu(null);
      setExtractingPath(record.path);
      try {
        const result = await extractZipFile(record.path, rootId);
        message.success(
          `已解压到「${result.targetDir}」（${result.entries} 个条目）`,
        );
        setHighlightPath(null);
        await load(currentPath);
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setExtractingPath(null);
      }
    },
    [currentPath, extractingPath, load, message, rootId],
  );

  /** 右键「拷贝」：原地复制并刷新/选中新条目（task-40）。 */
  const copyContextEntry = useCallback(
    async (record: DirEntry) => {
      if (copyingPath) return;
      setContextMenu(null);
      setCopyingPath(record.path);
      try {
        const result = await copyServerPath(record.path, rootId);
        message.success(`已拷贝为 ${result.name}`);
        await load(currentPath);
        selectFinderPath(result.path);
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setCopyingPath(null);
      }
    },
    [copyingPath, currentPath, load, message, rootId, selectFinderPath],
  );

  /** 加载「移动到…」弹窗中的目录列表（只保留目录）。 */
  const loadMoveDialogDir = useCallback(async (dialog: MoveDialogState) => {
    const seq = ++moveLoadSeqRef.current;
    setMoveDialog((current) =>
      current ? { ...current, loading: true, error: null } : current,
    );
    try {
      const result = await listServerDir(
        dialog.targetPath || undefined,
        dialog.targetRootId,
      );
      if (seq !== moveLoadSeqRef.current) return;
      setMoveDialog((current) =>
        current
          ? {
              ...current,
              entries: result.entries.filter(
                (entry) => entry.type === "directory",
              ),
              targetPath: result.path ?? dialog.targetPath,
              loading: false,
              error: null,
            }
          : current,
      );
    } catch (error) {
      if (seq !== moveLoadSeqRef.current) return;
      message.error(errorMessage(error));
      setMoveDialog(null);
    }
  }, [message]);

  /** 右键「移动到…」：默认目标为当前浏览目录（task-40）。 */
  const openMoveDialog = useCallback(
    (record: DirEntry) => {
      setContextMenu(null);
      const dialog: MoveDialogState = {
        entry: record,
        sourceRootId: rootId,
        targetRootId: rootId,
        targetPath: currentPath,
        entries: null,
        loading: true,
        error: null,
        submitting: false,
      };
      setMoveDialog(dialog);
      void loadMoveDialogDir(dialog);
    },
    [currentPath, loadMoveDialogDir, rootId],
  );

  /** 切换目标存储根：回到该根根目录。 */
  const changeMoveRoot = useCallback(
    (nextRootId: string | null) => {
      setMoveDialog((current) => {
        if (!current) return current;
        const next: MoveDialogState = {
          ...current,
          targetRootId: nextRootId,
          targetPath: "",
          entries: null,
          loading: true,
          error: null,
        };
        void loadMoveDialogDir(next);
        return next;
      });
    },
    [loadMoveDialogDir],
  );

  /** 进入弹窗中的子目录。 */
  const enterMoveDir = useCallback(
    (path: string) => {
      setMoveDialog((current) => {
        if (!current) return current;
        const next: MoveDialogState = {
          ...current,
          targetPath: path,
          entries: null,
          loading: true,
          error: null,
        };
        void loadMoveDialogDir(next);
        return next;
      });
    },
    [loadMoveDialogDir],
  );

  /** 返回目标目录上一级（根目录时禁用）。 */
  const moveDialogUp = useCallback(() => {
    setMoveDialog((current) => {
      if (!current || !current.targetPath) return current;
      const parts = current.targetPath.split("/").filter(Boolean);
      parts.pop();
      const next: MoveDialogState = {
        ...current,
        targetPath: parts.join("/"),
        entries: null,
        loading: true,
        error: null,
      };
      void loadMoveDialogDir(next);
      return next;
    });
  }, [loadMoveDialogDir]);

  /** 确认移动：调用 move_server_path；失败保留弹窗并提示服务端错误。 */
  const confirmMove = useCallback(async () => {
    if (!moveDialog || moveDialog.submitting) return;
    const { entry, sourceRootId, targetRootId, targetPath } = moveDialog;
    setMoveDialog((current) =>
      current ? { ...current, submitting: true } : current,
    );
    try {
      const result = await moveServerPath(
        entry.path,
        sourceRootId,
        targetRootId,
        targetPath,
      );
      const targetRootName =
        roots.find((root) => root.id === result.root)?.name ??
        roots.find((root) => root.id === targetRootId)?.name ??
        rootName ??
        "当前存储根";
      message.success(`已移动到 ${targetRootName}/${result.path}`);
      setMoveDialog(null);
      setHighlightPath(null);
      await load(currentPath);
    } catch (error) {
      message.error(errorMessage(error));
      setMoveDialog((current) =>
        current ? { ...current, submitting: false } : current,
      );
    }
  }, [currentPath, load, message, moveDialog, rootName, roots]);

  /** 当前目标根显示名。 */
  const moveTargetRootName =
    roots.find((root) => root.id === moveDialog?.targetRootId)?.name ??
    rootName ??
    "当前存储根";

  /** 目录移动到自身或子目录时客户端提前禁用（以服务端 40000 为准兜底）。 */
  const moveTargetInvalid = Boolean(
    moveDialog &&
      moveDialog.entry.type === "directory" &&
      moveDialog.targetRootId === moveDialog.sourceRootId &&
      (moveDialog.targetPath === moveDialog.entry.path ||
        moveDialog.targetPath.startsWith(`${moveDialog.entry.path}/`)),
  );

  /** 拉取日志（task-45）；seq 防止旧请求覆盖新结果。 */
  const fetchAudit = useCallback(
    async (page: number, size: number, filters: AuditFilters) => {
      const seq = ++auditSeqRef.current;
      setAuditLoading(true);
      try {
        const result = await listAuditLogs({
          page,
          size,
          action: filters.action ?? null,
          category: filters.category,
          success: null,
          keyword: null,
          from: filters.from,
          to: filters.to,
        });
        if (seq !== auditSeqRef.current) return;
        setAuditItems(result.items ?? []);
        setAuditTotal(result.total ?? 0);
        setAuditPage(result.page && result.page > 0 ? result.page : page);
        setAuditSize(result.size && result.size > 0 ? result.size : size);
      } catch (error) {
        if (seq !== auditSeqRef.current) return;
        const text = errorMessage(error);
        setAuditItems([]);
        setAuditTotal(0);
        message.error(text);
      } finally {
        if (seq === auditSeqRef.current) setAuditLoading(false);
      }
    },
    [],
  );

  /** 关闭日志 Drawer 并清理结果，避免下次打开/切换服务器串数据。 */
  const closeAuditDrawer = useCallback(() => {
    auditSeqRef.current += 1;
    setAuditOpen(false);
    setAuditItems([]);
    setAuditTotal(0);
    setAuditLoading(false);
    setAuditDetail(null);
    setAuditFullscreen(false);
  }, []);

  /** 打开日志：重置筛选/分页并拉第一页。 */
  const openAuditDrawer = useCallback(() => {
    auditSeqRef.current += 1;
    setAuditOpen(true);
    setAuditFullscreen(false);
    setAuditItems([]);
    setAuditTotal(0);
    setAuditPage(1);
    setAuditSize(50);
    setAuditFilters(EMPTY_AUDIT_FILTERS);
    setAuditRangeKey((key) => key + 1);
    setAuditDetail(null);
    void fetchAudit(1, 50, EMPTY_AUDIT_FILTERS);
  }, [fetchAudit]);

  /** 更新筛选并回到第一页。 */
  const applyAuditFilters = useCallback(
    (patch: Partial<AuditFilters>) => {
      const next = { ...auditFilters, ...patch };
      setAuditFilters(next);
      setAuditPage(1);
      void fetchAudit(1, auditSize, next);
    },
    [auditFilters, auditSize, fetchAudit],
  );

  /** 重置全部筛选。 */
  const resetAuditFilters = useCallback(() => {
    setAuditFilters(EMPTY_AUDIT_FILTERS);
    setAuditRangeKey((key) => key + 1);
    setAuditPage(1);
    setAuditSize(50);
    void fetchAudit(1, 50, EMPTY_AUDIT_FILTERS);
  }, [fetchAudit]);

  const changeAuditPage = useCallback(
    (page: number) => {
      setAuditPage(page);
      void fetchAudit(page, auditSize, auditFilters);
    },
    [auditFilters, auditSize, fetchAudit],
  );

  const changeAuditSize = useCallback(
    (size: number) => {
      setAuditSize(size);
      setAuditPage(1);
      void fetchAudit(1, size, auditFilters);
    },
    [auditFilters, fetchAudit],
  );

  const copyAuditText = useCallback(
    async (text: string, label: string) => {
      try {
        await navigator.clipboard.writeText(text);
        message.success(`${label}已复制`);
      } catch {
        message.error("复制失败，请手动选择复制");
      }
    },
    [message],
  );

  // 切换服务器/存储根时关闭并清空日志，防止旧服务器的数据残留。
  useEffect(() => {
    auditSeqRef.current += 1;
    setAuditOpen(false);
    setAuditItems([]);
    setAuditTotal(0);
    setAuditLoading(false);
    setAuditDetail(null);
    setAuditFullscreen(false);
  }, [rootId, serverName]);

  // 卸载时让进行中的请求失效。
  useEffect(
    () => () => {
      auditSeqRef.current += 1;
    },
    [],
  );

  /** 工具栏终端图标：未开 → 以当前目录打开；已开 → 关闭。 */
  const toggleTerminalPanel = useCallback(() => {
    if (terminalOpen) {
      setTerminalOpen(false);
      return;
    }
    setTerminalInitialCwd(joinRootPath(rootPath, currentPath));
    setTerminalOpenToken((token) => token + 1);
    setTerminalOpen(true);
  }, [currentPath, rootPath, terminalOpen]);

  /**
   * 右键「在此打开终端」：文件定位到所在目录（当前目录），目录定位到目录本身。
   */
  const openTerminalForEntry = useCallback(
    (record: DirEntry) => {
      setContextMenu(null);
      const target =
        record.type === "directory"
          ? joinRootPath(rootPath, record.path)
          : joinRootPath(rootPath, currentPath);
      setTerminalInitialCwd(target);
      setTerminalOpenToken((token) => token + 1);
      setTerminalOpen(true);
    },
    [currentPath, rootPath],
  );

  /** 右键菜单「在线编辑」：读取文本内容并打开编辑器。 */
  const openEditor = useCallback(
    async (record: DirEntry) => {
      setContextMenu(null);
      setEditor({
        entry: record,
        loading: true,
        error: null,
        content: "",
        sha256: "",
        size: 0,
        originalContent: "",
        language: detectEditorLanguage(record.name),
      });
      try {
        const data = await readServerFileContent(record.path, rootId);
        setEditor({
          entry: record,
          loading: false,
          error: null,
          content: data.content,
          sha256: data.sha256,
          size: data.size,
          originalContent: data.content,
          language: detectEditorLanguage(record.name),
        });
      } catch (error) {
        message.error(errorMessage(error));
        setEditor({
          entry: record,
          loading: false,
          error: errorMessage(error),
          content: "",
          sha256: "",
          size: 0,
          originalContent: "",
          language: detectEditorLanguage(record.name),
        });
      }
    },
    [message, rootId],
  );

  /** 保存编辑器内容（覆盖原文件，带 expectedSha256）。失败保留内容。 */
  const saveEditor = useCallback(async () => {
    if (!editor || editor.loading || editor.error || editorSaving) return;
    setEditorSaving(true);
    try {
      // Monaco model 是内容真源：避免 onChange 的 React state 更新滞后导致保存旧内容。
      const content =
        editorInstanceRef.current?.getModel()?.getValue() ?? editor.content;
      const saved = await saveServerFileContent(
        editor.entry.path,
        content,
        editor.sha256,
        rootId,
      );
      message.success(
        saved.path ? `已保存：${saved.path.split("/").pop() || saved.path}` : "已保存",
      );
      setEditor(null);
      setHighlightPath(null);
      await load(currentPath);
    } catch (error) {
      // 冲突/失败：保留编辑器内容，方便用户复制或重新打开。
      message.error(errorMessage(error));
    } finally {
      setEditorSaving(false);
    }
  }, [currentPath, editor, editorSaving, load, message, rootId]);

  editorSaveRef.current = () => {
    void saveEditor();
  };

  // Monaco 挂载：聚焦编辑器并注册 ⌘S / Ctrl+S（窗口级监听作为兜底）。
  const handleEditorMount = useCallback<OnMount>((instance) => {
    editorInstanceRef.current = instance;
    instance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      editorSaveRef.current();
    });
    window.setTimeout(() => instance.focus(), 0);
  }, []);

  /** 关闭编辑器：有未保存修改时先确认。 */
  const requestCloseEditor = useCallback(() => {
    if (!editor || editorSaving) return;
    const currentContent =
      editorInstanceRef.current?.getModel()?.getValue() ?? editor.content;
    const dirty =
      !editor.loading &&
      !editor.error &&
      currentContent !== editor.originalContent;
    if (!dirty) {
      setEditor(null);
      return;
    }
    modal.confirm({
      title: "放弃未保存的修改？",
      content: "当前修改尚未保存，关闭后将丢失。",
      okText: "放弃修改",
      cancelText: "继续编辑",
      okButtonProps: { danger: true },
      onOk: () => setEditor(null),
    });
  }, [editor, editorSaving, modal]);

  // Cmd/Ctrl+S 保存（编辑器打开时阻止浏览器默认保存）。
  useEffect(() => {
    if (!editor) return;
    const handleSaveShortcut = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return;
      if (
        (event.metaKey || event.ctrlKey) &&
        !event.altKey &&
        event.key.toLowerCase() === "s"
      ) {
        event.preventDefault();
        void saveEditor();
      }
    };
    window.addEventListener("keydown", handleSaveShortcut);
    return () => window.removeEventListener("keydown", handleSaveShortcut);
  }, [editor, saveEditor]);

  // 缩进宽度持久化 + 对已挂载 Monaco 立即生效。
  useEffect(() => {
    try {
      window.localStorage.setItem(EDITOR_TAB_SIZE_KEY, String(editorTabSize));
    } catch {
      // localStorage 不可用时忽略。
    }
  }, [editorTabSize]);

  useEffect(() => {
    if (!editor || editor.loading || editor.error) return;
    editorInstanceRef.current?.updateOptions({
      tabSize: editorTabSize,
      insertSpaces: true,
      detectIndentation: false,
    });
  }, [editor, editorTabSize]);

  /** 删除确认：行内按钮与右键菜单共用同一套逻辑。 */
  const requestDelete = useCallback(
    (record: DirEntry) => {
      setContextMenu(null);
      const isDirectory = record.type === "directory";
      modal.confirm({
        title: `删除${isDirectory ? "目录" : "文件"}「${record.name}」？`,
        content: isDirectory
          ? "仅空目录可以删除；非空目录会被服务端拒绝。删除后不可恢复。"
          : "删除后不可恢复。",
        okText: "删除",
        cancelText: "取消",
        okButtonProps: { danger: true },
        onOk: () => performDelete(record),
      });
    },
    [modal, performDelete],
  );

  /** 打开行右键菜单：阻止浏览器默认菜单，并做视口边缘避让。 */
  const openContextMenu = useCallback(
    (event: ReactMouseEvent<HTMLElement>, record: DirEntry) => {
      event.preventDefault();
      event.stopPropagation();
      clearPendingRenameTimer();
      selectFinderPath(record.path);
      const margin = 8;
      const x = Math.max(
        margin,
        Math.min(event.clientX, window.innerWidth - CONTEXT_MENU_WIDTH - margin),
      );
      const y = Math.max(
        margin,
        Math.min(
          event.clientY,
          window.innerHeight - contextMenuHeight(record) - margin,
        ),
      );
      setContextMenu({ x, y, entry: record });
    },
    [clearPendingRenameTimer, selectFinderPath],
  );

  // 点击空白 / Esc / 滚动 / 窗口 resize 关闭右键菜单（菜单内部点击不关闭）。
  useEffect(() => {
    if (!contextMenu) return;
    const handlePointerDown = (event: MouseEvent) => {
      if (contextMenuRef.current?.contains(event.target as Node)) return;
      setContextMenu(null);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setContextMenu(null);
    };
    const handleViewportChange = () => setContextMenu(null);
    window.addEventListener("mousedown", handlePointerDown);
    window.addEventListener("keydown", handleKeyDown);
    window.addEventListener("scroll", handleViewportChange, true);
    window.addEventListener("resize", handleViewportChange);
    return () => {
      window.removeEventListener("mousedown", handlePointerDown);
      window.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("scroll", handleViewportChange, true);
      window.removeEventListener("resize", handleViewportChange);
    };
  }, [contextMenu]);

  // 切换目录后菜单不再有意义。
  useEffect(() => {
    setContextMenu(null);
  }, [currentPath]);

  /** Finder 交互/右键菜单「重命名」：让该行名称单元格进入行内编辑。 */
  const startInlineRename = useCallback(
    (record: DirEntry) => {
      clearPendingRenameTimer();
      setContextMenu(null);
      selectFinderPath(record.path);
      renameCancelledRef.current = false;
      setRenamingPath(record.path);
      setRenameValue(record.name);
    },
    [clearPendingRenameTimer],
  );

  /** Esc 取消：标记取消，避免随后触发的 blur 又保存一次。 */
  const cancelInlineRename = useCallback(() => {
    renameCancelledRef.current = true;
    setRenamingPath(null);
    setRenameValue("");
  }, []);

  /** 保存行内重命名：未改名直接退出；失败保持编辑状态便于修改。 */
  const saveInlineRename = useCallback(
    async (record: DirEntry): Promise<boolean> => {
      if (renaming) return false;
      const name = renameValue.trim();
      const invalid = validateSingleName(name);
      if (invalid) {
        message.warning(invalid);
        // 若是失焦触发校验失败，重新聚焦以便继续修改。
        window.setTimeout(() => {
          renameInputRef.current?.focus({ preventScroll: true });
        }, 0);
        return false;
      }
      if (name === record.name) {
        // 名称未变化：不发请求，直接退出编辑。
        setRenamingPath(null);
        setRenameValue("");
        return true;
      }
      setRenaming(true);
      try {
        const result = await renameServerFile(record.path, name, rootId);
        const displayName = result.newPath.split("/").pop() || name;
        message.success(`已重命名为「${displayName}」`);
        setRenamingPath(null);
        setRenameValue("");
        setHighlightPath(null);
        selectFinderPath(result.newPath);
        await load(currentPath);
        return true;
      } catch (error) {
        // 冲突/非法名等失败：保持编辑状态，错误用服务端可展示消息提示。
        message.error(errorMessage(error));
        return false;
      } finally {
        setRenaming(false);
      }
    },
    [currentPath, load, message, renameValue, renaming, rootId],
  );

  /** Enter 保存；保存中忽略重复提交。 */
  const handleInlineRenameEnter = useCallback(
    (record: DirEntry) => {
      if (renaming) return;
      void saveInlineRename(record);
    },
    [renaming, saveInlineRename],
  );

  /** 失焦保存；Esc 取消产生的 blur 直接忽略。 */
  const handleInlineRenameBlur = useCallback(
    (record: DirEntry) => {
      if (renameCancelledRef.current) {
        renameCancelledRef.current = false;
        return;
      }
      if (renaming) return;
      void saveInlineRename(record);
    },
    [renaming, saveInlineRename],
  );

  // 进入行内编辑后聚焦并全选原名（文件管理器式体验）。
  useEffect(() => {
    if (!renamingPath) return;
    const timer = window.setTimeout(() => {
      renameInputRef.current?.focus({ preventScroll: true });
      renameInputRef.current?.select();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [renamingPath]);

  // 保存失败后输入框从 disabled 恢复，重新聚焦方便继续修改。
  useEffect(() => {
    if (renaming || !renamingPath) return;
    const timer = window.setTimeout(() => {
      renameInputRef.current?.focus({ preventScroll: true });
    }, 0);
    return () => window.clearTimeout(timer);
  }, [renaming, renamingPath]);

  /** 打开文件：下载到本机临时目录并用默认应用打开（task-26）。 */
  const handleOpenFile = useCallback(
    async (record: DirEntry) => {
      if (openingPath) return;
      setOpeningPath(record.path);
      try {
        await openServerFile(record.path, rootId);
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setOpeningPath(null);
      }
    },
    [message, openingPath, rootId],
  );

  /** 单击行：只选中；已选中的行再次单击 → 延迟进入重命名（双击会取消）。 */
  const handleRowClick = useCallback(
    (record: DirEntry) => {
      if (renamingPath === record.path) return;
      const wasSelected = selectedPathRef.current === record.path;
      selectFinderPath(record.path);
      // 用户主动选择后，查找文件跳转留下的高亮不再保留。
      setHighlightPath(null);
      clearPendingRenameTimer();
      if (!wasSelected) return;
      pendingRenameTimerRef.current = window.setTimeout(() => {
        pendingRenameTimerRef.current = null;
        startInlineRename(record);
      }, 220);
    },
    [clearPendingRenameTimer, renamingPath, selectFinderPath, startInlineRename],
  );

  /** 双击：目录进入；文件用默认应用打开；同时取消「再点改名」定时器。 */
  const handleRowDoubleClick = useCallback(
    (record: DirEntry) => {
      clearPendingRenameTimer();
      if (renamingPath) return;
      if (record.type === "directory") {
        enter(record.path);
        return;
      }
      void handleOpenFile(record);
    },
    [clearPendingRenameTimer, enter, handleOpenFile, renamingPath],
  );

  // 选中行在目录刷新后不存在则清除（删除/重命名/切换目录等场景）。
  useEffect(() => {
    if (!selectedPath) return;
    const entries = dir?.entries ?? [];
    if (!entries.some((entry) => entry.path === selectedPath)) {
      selectFinderPath(null);
    }
  }, [dir, selectedPath]);

  // Finder 式键盘操作：↑/↓ 移动选中、Enter 重命名、Esc 清除选中。
  useEffect(() => {
    const isEditableTarget = (target: EventTarget | null) => {
      const element = target as HTMLElement | null;
      if (!element) return false;
      const tag = element.tagName;
      return (
        tag === "INPUT" ||
        tag === "TEXTAREA" ||
        tag === "SELECT" ||
        element.isContentEditable
      );
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (editor || quickOpenOpen || contextMenu || renamingPath) return;
      if (isEditableTarget(event.target)) return;
      // 弹窗（删除确认等）打开时不响应表格快捷键。
      if (document.querySelector(".ant-modal-wrap")) return;
      const entries = dir?.entries ?? [];
      if (entries.length === 0) return;
      const index = entries.findIndex((entry) => entry.path === selectedPath);
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        const nextIndex =
          index < 0
            ? event.key === "ArrowDown"
              ? 0
              : entries.length - 1
            : event.key === "ArrowDown"
              ? Math.min(entries.length - 1, index + 1)
              : Math.max(0, index - 1);
        const next = entries[nextIndex];
        if (!next) return;
        setHighlightPath(null);
        selectFinderPath(next.path);
        window.requestAnimationFrame(() => {
          const row = [...(tableRef.current?.querySelectorAll("tr[data-row-key]") ?? [])].find(
            (item) => item.getAttribute("data-row-key") === next.path,
          );
          row?.scrollIntoView({ block: "nearest" });
        });
      } else if (event.key === "Home" || event.key === "End") {
        event.preventDefault();
        const next =
          event.key === "Home" ? entries[0] : entries[entries.length - 1];
        selectFinderPath(next.path);
      } else if (event.key === "Enter") {
        if (!selectedPath) return;
        const entry = entries.find((item) => item.path === selectedPath);
        if (!entry) return;
        event.preventDefault();
        startInlineRename(entry);
      } else if (event.key === "Escape") {
        if (!selectedPath) return;
        event.preventDefault();
        selectFinderPath(null);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [
    contextMenu,
    dir,
    editor,
    quickOpenOpen,
    renamingPath,
    selectedPath,
    startInlineRename,
  ]);

  // 组件卸载时清掉待改名定时器。
  useEffect(() => () => clearPendingRenameTimer(), [clearPendingRenameTimer]);

  if (!configured) {
    return (
      <Card className="page-card">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <Text strong>尚未配置服务器地址</Text>
          }
        >
          <Button type="primary" icon={<SettingOutlined />} onClick={onGoSettings}>
            去设置
          </Button>
        </Empty>
      </Card>
    );
  }

  const segments = currentPath.split("/").filter(Boolean);

  const rootLabel = rootName?.trim() || "根目录";
  const breadcrumbItems: BreadcrumbProps["items"] = [
    {
      title: (
        <span data-testid="breadcrumb-root" data-root-id={rootId ?? ""}>
          {segments.length === 0 ? (
            <Text strong>{rootLabel}</Text>
          ) : (
            <Link onClick={() => enter("")}>{rootLabel}</Link>
          )}
        </span>
      ),
    },
    ...segments.map((segment, index) => {
      const target = segments.slice(0, index + 1).join("/");
      const isLast = index === segments.length - 1;
      return {
        title: isLast ? (
          <Text strong>{segment}</Text>
        ) : (
          <Link onClick={() => enter(target)}>{segment}</Link>
        ),
      };
    }),
  ];

  const columns: ColumnsType<DirEntry> = [
    {
      title: "名称",
      dataIndex: "name",
      key: "name",
      ellipsis: true,
      render: (name: string, record) => {
        if (renamingPath === record.path) {
          // 行内重命名：输入框占满名称单元格，点击/双击不冒泡到行（避免进入目录）。
          return (
            <div
              className="inline-rename-cell"
              data-testid="inline-rename-cell"
              onClick={(event) => event.stopPropagation()}
              onDoubleClick={(event) => event.stopPropagation()}
            >
              <Input
                ref={renameInputRef}
                size="small"
                value={renameValue}
                onChange={(event) => setRenameValue(event.target.value)}
                onPressEnter={() => handleInlineRenameEnter(record)}
                onBlur={() => handleInlineRenameBlur(record)}
                onKeyDown={(event) => {
                  if (event.key === "Escape") {
                    event.preventDefault();
                    event.stopPropagation();
                    cancelInlineRename();
                  }
                }}
                onClick={(event) => event.stopPropagation()}
                onDoubleClick={(event) => event.stopPropagation()}
                disabled={renaming}
                maxLength={255}
                data-testid="inline-rename-input"
                aria-label={`重命名 ${record.name}`}
              />
            </div>
          );
        }
        return (
          <Space size={6} className="finder-name">
            {record.type === "directory" ? (
              <FolderOutlined className="finder-folder-icon" />
            ) : (
              <FileOutlined className="finder-file-icon" />
            )}
            <Text strong={record.type === "directory"}>{name}</Text>
          </Space>
        );
      },
    },
    {
      title: "修改日期",
      dataIndex: "modifiedAt",
      key: "modifiedAt",
      width: 190,
      ellipsis: true,
      render: (value: string) => formatFinderDate(value),
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 110,
      align: "right",
      render: (size: number | null, record) =>
        record.type === "directory" ? "--" : formatBytes(size),
    },
    {
      title: "种类",
      key: "kind",
      width: 130,
      ellipsis: true,
      render: (_, record) => fileKindLabel(record.name, record.type),
    },
  ];

  const auditColumns: ColumnsType<AuditLogItem> = [
    {
      title: "设备 / IP",
      key: "device",
      width: 180,
      ellipsis: true,
      render: (_, record) => (
        <div className="audit-device-cell">
          <Text ellipsis>{record.clientName || record.deviceId || "-"}</Text>
          <Text type="secondary" ellipsis>
            {record.clientIp || "-"}
          </Text>
        </div>
      ),
    },
    {
      title: "操作内容",
      key: "object",
      width: 360,
      ellipsis: true,
      render: (_, record) => {
        const text = auditObjectSummary(record);
        const terminal = record.action === "terminal.exec";
        const className = [
          terminal ? "audit-object-terminal" : "",
          !record.success ? "audit-object-failed" : "",
        ]
          .filter(Boolean)
          .join(" ");
        return (
          <Tooltip title={text}>
            <span className={className || undefined}>
              {terminal ? (
                <Text code className="audit-terminal-inline">
                  {text}
                </Text>
              ) : (
                text
              )}
            </span>
          </Tooltip>
        );
      },
    },
    {
      title: "时间",
      dataIndex: "ts",
      key: "ts",
      width: 165,
      render: (value: string) => formatAuditTime(value),
    },
    {
      title: "操作",
      dataIndex: "action",
      key: "action",
      width: 120,
      render: (action: string | null) => (
        <Tag color="blue">{auditActionLabel(action)}</Tag>
      ),
    },
  ];

  const uploadPercent =
    uploadProgress && uploadProgress.total > 0
      ? Math.min(
          100,
          Math.round((uploadProgress.sent / uploadProgress.total) * 100),
        )
      : 0;
  const dragRoot = rootName ? `「${rootName}」` : "根目录";
  const dragTarget = currentPath ? `${dragRoot}/${currentPath}` : dragRoot;

  return (
    <div className="browser-shell">
      <Card className="page-card browser-card">
        <div className="browser-toolbar" data-testid="browser-toolbar">
          <div className="browser-path" data-testid="browser-path">
            <FolderOutlined className="browser-path-icon" />
            <Breadcrumb items={breadcrumbItems} className="browser-breadcrumb" />
          </div>
          <div className="browser-toolbar-spacer" />
          <div className="browser-toolbar-actions" data-testid="browser-toolbar-actions">
            <Tooltip title="查找文件（⌘P）">
              <Button
                type="text"
                className="quick-open-trigger"
                icon={<SearchOutlined />}
                aria-label="查找文件"
                onClick={() => setQuickOpenOpen(true)}
                data-testid="quick-open-button"
              />
            </Tooltip>
            <Tooltip title="上传到此目录">
              <Button
                type="text"
                icon={<UploadOutlined />}
                loading={uploading}
                aria-label="上传到此目录"
                onClick={() => void handlePickUpload()}
                data-testid="upload-button"
              />
            </Tooltip>
            <Tooltip title="刷新">
              <Button
                type="text"
                icon={<ReloadOutlined />}
                loading={loading}
                aria-label="刷新目录"
                onClick={() => {
                  clearHighlight();
                  void load(currentPath);
                }}
                data-testid="refresh-button"
              />
            </Tooltip>
            <Tooltip title="日志">
              <Button
                type="text"
                icon={<HistoryOutlined />}
                aria-label="日志"
                onClick={openAuditDrawer}
                data-testid="audit-open"
              />
            </Tooltip>
            <Tooltip title="终端">
              <Button
                type="text"
                icon={<CodeOutlined />}
                aria-label="终端"
                aria-pressed={terminalOpen}
                className={terminalOpen ? "toolbar-icon-active" : undefined}
                onClick={toggleTerminalPanel}
                data-testid="terminal-button"
              />
            </Tooltip>
          </div>
        </div>

        <Space direction="vertical" size={12} style={{ width: "100%" }}>
          {uploading && (
            <Space style={{ width: "100%" }} align="center">
              <Progress
                style={{ flex: 1, margin: 0 }}
                size="small"
                percent={uploadPercent}
                status="active"
              />
              <Text type="secondary" style={{ whiteSpace: "nowrap" }}>
                正在上传 {uploadIndex}/{uploadTotal} 个文件
              </Text>
            </Space>
          )}

          {dir?.truncated && (
            <Alert
              type="warning"
              showIcon
              message="目录条目过多，仅显示前 2000 项"
            />
          )}

          <div ref={tableRef} className="browser-table-wrap">
            <Table<DirEntry>
              className="finder-table"
              rowKey={(record) => record.path || record.name}
              size="middle"
              loading={loading}
              columns={columns}
              dataSource={dir?.entries ?? []}
              scroll={{ x: 760 }}
              pagination={false}
              rowClassName={(record) =>
                [
                  record.path === highlightPath ? "row-highlight" : "",
                  record.path === selectedPath ? "finder-row-selected" : "",
                ]
                  .filter(Boolean)
                  .join(" ")
              }
              onRow={(record) => ({
                "aria-selected": record.path === selectedPath,
                style: { cursor: "default" as const },
                // 单击只选中；再次单击同一行由 handleRowClick 延迟进入重命名。
                onClick: () => handleRowClick(record),
                onDoubleClick: () => handleRowDoubleClick(record),
                onContextMenu: (event: ReactMouseEvent<HTMLElement>) =>
                  openContextMenu(event, record),
              })}
              locale={{
                emptyText: (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="当前目录为空"
                  />
                ),
              }}
            />
          </div>
        </Space>
      </Card>

      {contextMenu && (
        <div
          ref={contextMenuRef}
          className="row-context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          data-testid="row-context-menu"
          role="menu"
          aria-label={`${contextMenu.entry.name} 操作`}
        >
          {contextMenu.entry.type === "file" && (
            <button
              type="button"
              role="menuitem"
              className="row-context-menu-item"
              data-testid="context-edit"
              onClick={() => void openEditor(contextMenu.entry)}
            >
              <EditOutlined />
              <span>在线编辑</span>
            </button>
          )}
          {contextMenu.entry.type === "file" &&
            fileExtension(contextMenu.entry.name) === "zip" && (
              <button
                type="button"
                role="menuitem"
                className="row-context-menu-item"
                data-testid="context-extract"
                disabled={extractingPath !== null}
                onClick={() => void handleExtractZip(contextMenu.entry)}
              >
                <FileZipOutlined />
                <span>{extractingPath ? "解压中…" : "解压"}</span>
              </button>
            )}
          {contextMenu.entry.type === "file" && (
            <button
              type="button"
              role="menuitem"
              className="row-context-menu-item"
              data-testid="context-open-terminal"
              onClick={() => openTerminalForEntry(contextMenu.entry)}
            >
              <CodeOutlined />
              <span>在此打开终端</span>
            </button>
          )}
          {contextMenu.entry.type === "file" && (
            <>
              <button
                type="button"
                role="menuitem"
                className="row-context-menu-item"
                data-testid="context-copy"
                disabled={copyingPath !== null}
                onClick={() => void copyContextEntry(contextMenu.entry)}
              >
                <CopyOutlined />
                <span>{copyingPath ? "拷贝中…" : "拷贝"}</span>
              </button>
              <button
                type="button"
                role="menuitem"
                className="row-context-menu-item"
                data-testid="context-move"
                onClick={() => openMoveDialog(contextMenu.entry)}
              >
                <FolderOpenOutlined />
                <span>移动到…</span>
              </button>
            </>
          )}
          {contextMenu.entry.type === "file" && (
            <button
              type="button"
              role="menuitem"
              className="row-context-menu-item"
              data-testid="context-download"
              disabled={downloadingPath !== null}
              onClick={() => void handleDownloadFile(contextMenu.entry)}
            >
              <DownloadOutlined />
              <span>{downloadingPath ? "下载中…" : "下载"}</span>
            </button>
          )}
          {contextMenu.entry.type === "directory" && (
            <button
              type="button"
              role="menuitem"
              className="row-context-menu-item"
              data-testid="context-open-terminal"
              onClick={() => openTerminalForEntry(contextMenu.entry)}
            >
              <CodeOutlined />
              <span>在此打开终端</span>
            </button>
          )}
          {contextMenu.entry.type === "directory" && (
            <>
              <button
                type="button"
                role="menuitem"
                className="row-context-menu-item"
                data-testid="context-copy"
                disabled={copyingPath !== null}
                onClick={() => void copyContextEntry(contextMenu.entry)}
              >
                <CopyOutlined />
                <span>{copyingPath ? "拷贝中…" : "拷贝"}</span>
              </button>
              <button
                type="button"
                role="menuitem"
                className="row-context-menu-item"
                data-testid="context-move"
                onClick={() => openMoveDialog(contextMenu.entry)}
              >
                <FolderOpenOutlined />
                <span>移动到…</span>
              </button>
            </>
          )}
          <button
            type="button"
            role="menuitem"
            className="row-context-menu-item"
            data-testid="context-rename"
            onClick={() => startInlineRename(contextMenu.entry)}
          >
            <EditOutlined />
            <span>重命名</span>
          </button>
          <button
            type="button"
            role="menuitem"
            className="row-context-menu-item row-context-menu-item-danger"
            data-testid="context-delete"
            disabled={deletingPath !== null}
            onClick={() => requestDelete(contextMenu.entry)}
          >
            <DeleteOutlined />
            <span>{deletingPath ? "删除中…" : "删除"}</span>
          </button>
        </div>
      )}

      <Modal
        open={moveDialog !== null}
        title={
          <Space size={8}>
            <span>移动到…</span>
            {moveDialog && (
              <Text strong ellipsis style={{ maxWidth: 360 }}>
                {moveDialog.entry.name}
              </Text>
            )}
          </Space>
        }
        width={640}
        centered
        destroyOnHidden
        maskClosable={!moveDialog?.submitting}
        keyboard={!moveDialog?.submitting}
        onCancel={() => {
          if (!moveDialog?.submitting) setMoveDialog(null);
        }}
        footer={null}
        className="move-modal"
      >
        {moveDialog && (
          <div className="move-dialog" data-testid="move-dialog">
            <div className="move-dialog-source">
              <Text type="secondary">源：</Text>
              <Text ellipsis style={{ maxWidth: 460 }}>
                {moveDialog.entry.path}
              </Text>
            </div>
            {roots.length > 1 && (
              <div className="move-dialog-root">
                <Text type="secondary">目标存储根</Text>
                <Select
                  size="small"
                  data-testid="move-root-select"
                  value={moveDialog.targetRootId ?? undefined}
                  options={roots.map((root) => ({
                    value: root.id,
                    label: root.name,
                  }))}
                  onChange={(value) => changeMoveRoot(value)}
                  disabled={moveDialog.submitting}
                  style={{ width: 180 }}
                />
              </div>
            )}
            <div className="move-dialog-location">
              <Button
                size="small"
                icon={<ArrowLeftOutlined />}
                data-testid="move-dialog-up"
                disabled={!moveDialog.targetPath || moveDialog.loading}
                onClick={moveDialogUp}
              >
                上一级
              </Button>
              <Breadcrumb
                className="move-dialog-breadcrumb"
                items={[
                  {
                    title: (
                      <Link onClick={() => changeMoveRoot(moveDialog.targetRootId)}>
                        {moveTargetRootName}
                      </Link>
                    ),
                  },
                  ...moveDialog.targetPath
                    .split("/")
                    .filter(Boolean)
                    .map((segment, index, parts) => ({
                      title: (
                        <Link
                          onClick={() =>
                            enterMoveDir(parts.slice(0, index + 1).join("/"))
                          }
                        >
                          {segment}
                        </Link>
                      ),
                    })),
                ]}
              />
            </div>
            <div className="move-dialog-list" data-testid="move-dir-list">
              {moveDialog.loading ? (
                <div className="move-dialog-status">
                  <Spin /> <Text type="secondary">加载中…</Text>
                </div>
              ) : (moveDialog.entries?.length ?? 0) === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="当前目录没有子目录"
                />
              ) : (
                moveDialog.entries?.map((entry) => (
                  <button
                    key={entry.path}
                    type="button"
                    className="move-dialog-dir"
                    data-testid="move-dir-item"
                    data-path={entry.path}
                    onClick={() => enterMoveDir(entry.path)}
                  >
                    <FolderOutlined />
                    <span>{entry.name}</span>
                  </button>
                ))
              )}
            </div>
            <div className="move-dialog-target">
              <Text type="secondary">目标：</Text>
              <Text data-testid="move-target-path">
                {moveTargetRootName}
                {moveDialog.targetPath ? `/${moveDialog.targetPath}` : "/"}
              </Text>
            </div>
            {moveTargetInvalid && (
              <Alert
                type="warning"
                showIcon
                data-testid="move-invalid-hint"
                message="不能移动到源目录自身或其子目录"
              />
            )}
            <div className="move-dialog-footer">
              <Button
                onClick={() => setMoveDialog(null)}
                disabled={moveDialog.submitting}
                data-testid="move-cancel"
              >
                取消
              </Button>
              <Button
                type="primary"
                data-testid="move-confirm"
                loading={moveDialog.submitting}
                disabled={
                  moveDialog.loading ||
                  Boolean(moveDialog.error) ||
                  moveTargetInvalid
                }
                onClick={() => void confirmMove()}
              >
                选择此文件夹
              </Button>
            </div>
          </div>
        )}
      </Modal>

      <Drawer
        open={auditOpen}
        onClose={closeAuditDrawer}
        placement="right"
        width={auditFullscreen ? "100%" : 980}
        className="audit-drawer"
        title={
          <Space size={8}>
            <HistoryOutlined />
            <span>日志</span>
            {serverName && (
              <Text type="secondary" className="audit-drawer-server">
                · {serverName}
              </Text>
            )}
          </Space>
        }
        extra={
          <Space size={2}>
            <Tooltip title="刷新日志">
              <Button
                type="text"
                icon={<ReloadOutlined />}
                loading={auditLoading}
                aria-label="刷新日志"
                onClick={() => void fetchAudit(auditPage, auditSize, auditFilters)}
                data-testid="audit-refresh"
              />
            </Tooltip>
            <Tooltip title={auditFullscreen ? "还原宽度" : "全屏"}>
              <Button
                type="text"
                icon={
                  auditFullscreen ? (
                    <FullscreenExitOutlined />
                  ) : (
                    <FullscreenOutlined />
                  )
                }
                aria-label={auditFullscreen ? "还原宽度" : "全屏"}
                onClick={() => setAuditFullscreen((value) => !value)}
                data-testid="audit-fullscreen"
              />
            </Tooltip>
          </Space>
        }
        destroyOnHidden
        styles={{
          body: { paddingTop: 12, display: "flex", flexDirection: "column" },
        }}
      >
        <div className="audit-drawer-body" data-testid="audit-drawer">
          <div className="audit-filters" data-testid="audit-filters">
            <Select
              className="audit-filter-category"
              value={auditFilters.category}
              options={[
                { value: "both", label: "全部日志" },
                { value: "operation", label: "操作日志" },
                { value: "connection", label: "连接日志" },
              ]}
              onChange={(value: AuditLogCategory) =>
                applyAuditFilters({ category: value, action: null })
              }
              data-testid="audit-category-filter"
            />
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={
                auditFilters.category === "connection"
                  ? "全部连接事件"
                  : auditFilters.category === "operation"
                    ? "全部操作"
                    : "全部操作 / 连接事件"
              }
              className="audit-filter-action"
              value={auditFilters.action ?? undefined}
              options={AUDIT_ACTION_OPTIONS[auditFilters.category]}
              onChange={(value) => applyAuditFilters({ action: value ?? null })}
              data-testid="audit-action"
            />
            <span data-testid="audit-range">
              <DatePicker.RangePicker
                key={auditRangeKey}
                showTime
                allowClear
                className="audit-filter-range"
                placeholder={["开始时间", "结束时间"]}
                onChange={(dates) => {
                  applyAuditFilters({
                    from: dates?.[0]?.toISOString() ?? null,
                    to: dates?.[1]?.toISOString() ?? null,
                  });
                }}
              />
            </span>
            <Button onClick={resetAuditFilters} data-testid="audit-reset">
              重置
            </Button>
          </div>

          <div data-testid="audit-table">
            <Table<AuditLogItem>
              className="audit-table"
              rowKey={(record) =>
                record.requestId || `${record.ts}-${record.path ?? ""}`
              }
              size="small"
              loading={auditLoading}
              columns={auditColumns}
              dataSource={auditItems}
              scroll={{ x: 825 }}
              locale={{
                emptyText: <Empty description="暂无日志" />,
              }}
              pagination={{
                current: auditPage,
                pageSize: auditSize,
                total: auditTotal,
                showSizeChanger: { className: "audit-page-size" },
                pageSizeOptions: [10, 20, 50, 100],
                showQuickJumper: true,
                showTotal: (total, range) =>
                  `第 ${range[0]}-${range[1]} 条 / 共 ${total} 条`,
                onChange: (page, size) => {
                  if (size !== auditSize) {
                    changeAuditSize(size);
                  } else {
                    changeAuditPage(page);
                  }
                },
              }}
              onRow={(record) => ({
                onClick: () => setAuditDetail(record),
                style: { cursor: "pointer" },
              })}
            />
          </div>
        </div>
      </Drawer>

      <Modal
        open={auditDetail !== null}
        title="日志详情"
        width={860}
        centered
        destroyOnHidden
        onCancel={() => setAuditDetail(null)}
        footer={
          <Button onClick={() => setAuditDetail(null)} data-testid="audit-detail-close">
            关闭
          </Button>
        }
        className="audit-detail-modal"
      >
        {auditDetail && (
          <div className="audit-detail" data-testid="audit-detail">
            <Descriptions
              bordered
              size="small"
              column={2}
              className="audit-detail-descriptions"
              items={[
                {
                  key: "ts",
                  label: "时间",
                  children: formatAuditTime(auditDetail.ts),
                },
                {
                  key: "requestId",
                  label: "requestId",
                  children: (
                    <Space size={6}>
                      <Text code>{auditDetail.requestId || "-"}</Text>
                      <Tooltip title="复制 requestId">
                        <Button
                          type="text"
                          size="small"
                          icon={<CopyOutlined />}
                          aria-label="复制 requestId"
                          onClick={() =>
                            void copyAuditText(
                              auditDetail.requestId,
                              "requestId",
                            )
                          }
                          data-testid="audit-copy-request-id"
                        />
                      </Tooltip>
                    </Space>
                  ),
                },
                {
                  key: "action",
                  label: "操作",
                  children: (
                    <Space size={6}>
                      <Tag color="blue">
                        {auditActionLabel(auditDetail.action)}
                      </Tag>
                      <Text type="secondary">
                        {auditDetail.action || "-"}
                      </Text>
                    </Space>
                  ),
                },
                {
                  key: "result",
                  label: "结果",
                  children: (
                    <Space size={6}>
                      <Tag color={auditDetail.success ? "green" : "red"}>
                        {auditDetail.success ? "成功" : "失败"}
                      </Tag>
                      <Text type="secondary">
                        HTTP {auditDisplay(auditDetail.httpStatus)}
                      </Text>
                    </Space>
                  ),
                },
                {
                  key: "method",
                  label: "方法 / 路径",
                  children: `${auditDisplay(auditDetail.method)} ${auditDisplay(
                    auditDetail.path,
                  )}`,
                },
                {
                  key: "query",
                  label: "query",
                  children: auditDisplay(auditDetail.query),
                },
                {
                  key: "root",
                  label: "存储根",
                  children: auditDisplay(auditDetail.root),
                },
                {
                  key: "targetRoot",
                  label: "目标存储根",
                  children: auditDisplay(auditDetail.targetRoot),
                },
                {
                  key: "pathParam",
                  label: "pathParam",
                  children: auditDisplay(auditDetail.pathParam),
                },
                {
                  key: "targetDir",
                  label: "targetDir",
                  children: auditDisplay(auditDetail.targetDir),
                },
                {
                  key: "fileName",
                  label: "文件名",
                  children: auditDisplay(auditDetail.fileName),
                },
                {
                  key: "sessionId",
                  label: "sessionId",
                  children: auditDisplay(auditDetail.sessionId),
                },
                {
                  key: "clientName",
                  label: "客户端",
                  children: auditDisplay(auditDetail.clientName),
                },
                {
                  key: "deviceId",
                  label: "设备 ID",
                  children: auditDisplay(auditDetail.deviceId),
                },
                {
                  key: "clientIp",
                  label: "客户端 IP",
                  children: auditDisplay(auditDetail.clientIp),
                },
                {
                  key: "appKey",
                  label: "appKey",
                  children: auditDisplay(auditDetail.appKey),
                },
                {
                  key: "durationMs",
                  label: "耗时",
                  children: `${auditDisplay(auditDetail.durationMs)} ms`,
                },
                {
                  key: "requestBytes",
                  label: "请求字节",
                  children: auditDisplay(auditDetail.requestBytes),
                },
                {
                  key: "responseBytes",
                  label: "响应字节",
                  children: auditDisplay(auditDetail.responseBytes),
                },
                {
                  key: "contentSha256",
                  label: "contentSha256",
                  children: (
                    <Text code copyable>
                      {auditDetail.contentSha256 || "-"}
                    </Text>
                  ),
                },
                {
                  key: "userAgent",
                  label: "User-Agent",
                  children: auditDisplay(auditDetail.userAgent),
                },
                {
                  key: "error",
                  label: "错误",
                  span: 2,
                  children: auditDetail.error ? (
                    <Text type="danger">{auditDetail.error}</Text>
                  ) : (
                    "-"
                  ),
                },
                {
                  key: "terminalCommand",
                  label: "终端命令",
                  span: 2,
                  children: auditDetail.terminalCommand ? (
                    <pre
                      className="audit-pre"
                      data-testid="audit-terminal-command"
                    >
                      {auditDetail.terminalCommand}
                    </pre>
                  ) : (
                    "-"
                  ),
                },
                {
                  key: "body",
                  label: "请求体",
                  span: 2,
                  children: auditDetail.body ? (
                    <pre className="audit-pre" data-testid="audit-body">
                      {auditDetail.body}
                    </pre>
                  ) : (
                    "-"
                  ),
                },
              ]}
            />
          </div>
        )}
      </Modal>

      <Modal
        open={editor !== null}
        title={
          editor ? (
            <Space size={8}>
              <span>在线编辑</span>
              <Text strong ellipsis style={{ maxWidth: 420 }}>
                {editor.entry.name}
              </Text>
            </Space>
          ) : null
        }
        width="min(80vw, 1000px)"
        centered
        destroyOnHidden
        maskClosable={!editorSaving}
        keyboard={!editorSaving}
        onCancel={requestCloseEditor}
        footer={null}
        className="editor-modal"
      >
        {editor && (
          <div className="editor-body" data-testid="editor-modal" ref={editorBodyRef}>
            <div className="editor-meta">
              <span className="editor-meta-path" title={editor.entry.path}>
                {editor.entry.path}
              </span>
              {!editor.loading && !editor.error && (
                <>
                  <span className="editor-language" data-testid="editor-language-select">
                    <Text type="secondary">语言</Text>
                    <Select
                      size="small"
                      showSearch
                      optionFilterProp="label"
                      value={editor.language}
                      options={EDITOR_LANGUAGES}
                      onChange={(value) =>
                        setEditor((current) =>
                          current ? { ...current, language: value } : current,
                        )
                      }
                      disabled={editorSaving}
                      popupMatchSelectWidth={false}
                      style={{ width: 132 }}
                    />
                  </span>
                  <span className="editor-tab-size" data-testid="editor-tab-size-select">
                    <Select
                      size="small"
                      value={editorTabSize}
                      onChange={(value) => setEditorTabSize(value)}
                      options={[
                        { value: 2, label: "Tab: 2" },
                        { value: 4, label: "Tab: 4" },
                      ]}
                      disabled={editorSaving}
                      popupMatchSelectWidth={false}
                      style={{ width: 88 }}
                    />
                  </span>
                  <Tag data-testid="editor-utf8">UTF-8</Tag>
                  <span data-testid="editor-size">{formatBytes(editor.size)}</span>
                </>
              )}
            </div>
            {editor.loading ? (
              <div className="editor-loading" data-testid="editor-loading">
                <Spin /> <Text type="secondary">加载中…</Text>
              </div>
            ) : editor.error ? (
              <>
                <div data-testid="editor-error">
                  <Alert
                    type="error"
                    showIcon
                    message="无法在线编辑"
                    description={editor.error}
                  />
                </div>
                <div className="editor-footer">
                  <span />
                  <Button
                    onClick={() => setEditor(null)}
                    data-testid="editor-close-error"
                  >
                    关闭
                  </Button>
                </div>
              </>
            ) : (
              <>
                <div
                  className="editor-monaco"
                  data-testid="editor-monaco"
                  data-language={editor.language}
                >
                  <Editor
                    height="100%"
                    theme="vs"
                    language={editor.language}
                    value={editor.content}
                    path={`secure-upload://${editor.entry.path}`}
                    onChange={(value) =>
                      setEditor((current) =>
                        current ? { ...current, content: value ?? "" } : current,
                      )
                    }
                    onMount={handleEditorMount}
                    loading={
                      <div className="editor-loading">
                        <Spin /> <Text type="secondary">加载编辑器…</Text>
                      </div>
                    }
                    options={{
                      minimap: { enabled: false },
                      wordWrap: "on",
                      automaticLayout: true,
                      fontSize: 13,
                      fontFamily:
                        'SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace',
                      lineNumbers: "on",
                      scrollBeyondLastLine: false,
                      renderWhitespace: "selection",
                      tabSize: editorTabSize,
                      insertSpaces: true,
                      detectIndentation: false,
                      readOnly: editorSaving,
                      ariaLabel: `编辑 ${editor.entry.name}`,
                      padding: { top: 8, bottom: 8 },
                    }}
                  />
                </div>
                <div className="editor-footer">
                  <Text type="secondary">
                    {editorSaving ? "保存中…" : "⌘S / Ctrl+S 保存"}
                  </Text>
                  <Space>
                    <Button
                      onClick={requestCloseEditor}
                      disabled={editorSaving}
                      data-testid="editor-cancel"
                    >
                      取消
                    </Button>
                    <Button
                      type="primary"
                      loading={editorSaving}
                      onClick={() => void saveEditor()}
                      data-testid="editor-save"
                    >
                      保存
                    </Button>
                  </Space>
                </div>
              </>
            )}
          </div>
        )}
      </Modal>

      <QuickOpen
        open={quickOpenOpen}
        rootId={rootId}
        onClose={() => setQuickOpenOpen(false)}
        onSelect={(entry) => void handleQuickOpenSelect(entry)}
      />

      <TerminalPanel
        open={terminalOpen}
        serverName={serverName}
        rootId={rootId}
        rootPath={rootPath}
        initialCwd={terminalInitialCwd}
        openToken={terminalOpenToken}
        onClose={() => setTerminalOpen(false)}
      />

      {dragActive && (
        <div className="drag-overlay" data-testid="drag-overlay">
          <InboxOutlined className="drag-overlay-icon" />
          <Text strong className="drag-overlay-title">
            松开上传到{dragTarget}
          </Text>
        </div>
      )}
    </div>
  );
}
