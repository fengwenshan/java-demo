/**
 * 前端访问 Rust 侧的唯一入口：全部通过 Tauri `invoke`，不直接发 HTTP，
 * 也拿不到 appSecret。
 *
 * 注意：Tauri 命令的可选参数（Rust 侧 `Option<T>`）**必须显式传 `null`**，
 * 不能传 `undefined`（`JSON.stringify` 会把 undefined 字段丢掉，Tauri 反序列化
 * 会报 missing required key）。这是之前上传失败的原因之一。
 */
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { open, save } from "@tauri-apps/plugin-dialog";

import type {
  ActivateResult,
  AuditLogPage,
  AuditLogQuery,
  ConfigView,
  CopyResult,
  DirList,
  ExtractResult,
  FileContent,
  FileMeta,
  LocalFileInfo,
  MoveResult,
  RenameResult,
  SavedContent,
  TerminalResult,
  SearchResult,
  StorageRoots,
  UploadProgress,
  VerifyResult,
} from "./types";

/** 是否运行在 Tauri 桌面客户端内。 */
export function isTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

/** 浏览器预览环境直接抛出可展示的错误。 */
export function requireDesktop(): void {
  if (!isTauri()) {
    throw new Error("请使用桌面客户端运行（浏览器预览环境无法访问本地接口）");
  }
}

/** 把 Rust / 未知异常统一成中文字符串。 */
export function errorMessage(error: unknown): string {
  const raw =
    typeof error === "string" && error.trim()
      ? error
      : error instanceof Error && error.message
        ? error.message
        : "";
  if (!raw) return "操作失败，请稍后重试";
  // Rust 侧命令签名与前端不匹配（改了 Rust 命令但客户端进程没重启）时给出可执行的提示。
  if (/invalid args|missing required key|command .* not found/i.test(raw)) {
    return "客户端还是旧版本：请完全退出客户端（Cmd+Q）后重新启动";
  }
  return raw;
}

/** 配置视图（多服务器，不含 appSecret）。 */
export async function getConfig(): Promise<ConfigView> {
  requireDesktop();
  return invoke<ConfigView>("get_config");
}

export interface SaveServerArgs {
  /** 不传/null 表示新建；传 id 表示编辑。 */
  id?: string | null;
  name: string;
  serverUrl: string;
  deviceName: string;
  /** 自定义 AppKey；空串表示使用内置默认。 */
  appKey?: string;
  /**
   * 自定义 AppSecret：
   * - `null` / 不传：编辑时保留原值（新建时使用内置默认）；
   * - 空串：清除自定义值，恢复内置默认；
   * - 非空：保存新的自定义值。
   */
  appSecret?: string | null;
}

/** 新建或编辑一台服务器（新建后自动切换为当前服务器）。 */
export async function saveServer(args: SaveServerArgs): Promise<ConfigView> {
  requireDesktop();
  return invoke<ConfigView>("save_server", {
    id: args.id ?? null,
    name: args.name,
    serverUrl: args.serverUrl,
    deviceName: args.deviceName,
    appKey: args.appKey ?? "",
    appSecret: args.appSecret ?? null,
  });
}

/** 删除一台服务器（至少保留一台）；删除当前服务器后自动切换。 */
export async function deleteServer(id: string): Promise<ConfigView> {
  requireDesktop();
  return invoke<ConfigView>("delete_server", { id });
}

/** 切换当前服务器。 */
export async function setActiveServer(id: string): Promise<ConfigView> {
  requireDesktop();
  return invoke<ConfigView>("set_active_server", { id });
}

/**
 * 列出服务端可用的存储根（task-16）。旧单根服务端没有 `/api/storage/roots`，
 * 调用会失败；调用方应静默回退为「不传 root」的默认根行为。
 */
export async function listStorageRoots(): Promise<StorageRoots> {
  requireDesktop();
  return invoke<StorageRoots>("list_storage_roots");
}

/** 验证连接（契约 3.2；针对当前激活服务器）。 */
export async function verifyConnection(): Promise<VerifyResult> {
  requireDesktop();
  return invoke<VerifyResult>("verify_connection");
}

/** 激活设备（契约 3.3；针对当前激活服务器的 deviceId/deviceName）。 */
export async function activateDevice(): Promise<ActivateResult> {
  requireDesktop();
  return invoke<ActivateResult>("activate");
}

/**
 * 上传文件（契约 3.5 / task-14 分片），Rust 内部按大小自动选择单次或分片。
 * `dir` 为相对存储根目录的目标目录，null 表示根目录。
 */
export async function uploadFile(args: {
  path: string;
  description?: string | null;
  dir?: string | null;
  /** 存储根 id；null = 服务端默认根（旧单根服务端保持兼容）。 */
  root?: string | null;
  uploadId: string;
}): Promise<FileMeta> {
  requireDesktop();
  return invoke<FileMeta>("upload_file", {
    path: args.path,
    description: args.description ?? null,
    dir: args.dir ?? null,
    root: args.root ?? null,
    uploadId: args.uploadId,
  });
}

/**
 * 浏览服务端存储根目录（契约 3.8）；path 为 null/空表示根目录，
 * root 为存储根 id（null = 服务端默认根）。
 */
export async function listServerDir(
  path?: string | null,
  root?: string | null,
): Promise<DirList> {
  requireDesktop();
  return invoke<DirList>("list_server_dir", { path: path ?? null, root: root ?? null });
}

/** 在存储根目录内递归搜索文件/目录（task-14 契约 B）。 */
export async function searchServerFiles(
  keyword: string,
  limit = 100,
  root?: string | null,
): Promise<SearchResult> {
  requireDesktop();
  return invoke<SearchResult>("search_server_files", {
    keyword,
    limit,
    root: root ?? null,
  });
}

/** 按相对路径下载服务器目录中的文件（FTP 式目录浏览），返回保存后的绝对路径。 */
export async function downloadServerFile(args: {
  path: string;
  targetPath: string;
  root?: string | null;
}): Promise<string> {
  requireDesktop();
  return invoke<string>("download_server_file", {
    path: args.path,
    targetPath: args.targetPath,
    root: args.root ?? null,
  });
}

/** 按相对路径删除服务器目录中的文件（FTP 式目录浏览）。 */
export async function deleteServerFile(
  path: string,
  root?: string | null,
): Promise<void> {
  requireDesktop();
  await invoke("delete_server_file", { path, root: root ?? null });
}

/**
 * 重命名服务端文件/目录（task-18，`POST /api/files/rename`）。
 *
 * `path` 为相对当前存储根的路径，`newName` 只能是单段名称；`root` 为存储根 id
 * （null = 服务端默认根，兼容旧单根服务端）。
 */
export async function renameServerFile(
  path: string,
  newName: string,
  root?: string | null,
): Promise<RenameResult> {
  requireDesktop();
  return invoke<RenameResult>("rename_server_file", {
    path,
    newName,
    root: root ?? null,
  });
}

/** 原地拷贝服务端文件/目录（task-40，服务端生成 `_副本` 名称）。 */
export async function copyServerPath(
  path: string,
  root?: string | null,
): Promise<CopyResult> {
  requireDesktop();
  return invoke<CopyResult>("copy_server_path", {
    path,
    root: root ?? null,
  });
}

/** 移动服务端文件/目录（task-40，可跨根；targetRoot=null 同根）。 */
export async function moveServerPath(
  path: string,
  root: string | null | undefined,
  targetRoot: string | null | undefined,
  targetDir: string,
): Promise<MoveResult> {
  requireDesktop();
  return invoke<MoveResult>("move_server_path", {
    path,
    root: root ?? null,
    targetRoot: targetRoot ?? null,
    targetDir,
  });
}

/** 分页查询当前 appKey 的操作日志（task-45，签名 GET）。 */
export async function listAuditLogs(
  query: AuditLogQuery = {},
): Promise<AuditLogPage> {
  requireDesktop();
  return invoke<AuditLogPage>("list_audit_logs", {
    page: query.page ?? 1,
    size: query.size ?? 50,
    action: query.action ?? null,
    category: query.category ?? null,
    success: query.success ?? null,
    keyword: query.keyword ?? null,
    from: query.from ?? null,
    to: query.to ?? null,
  });
}

/** 读取文本文件内容用于在线编辑（task-23）。 */
export async function readServerFileContent(
  path: string,
  root?: string | null,
): Promise<FileContent> {
  requireDesktop();
  return invoke<FileContent>("read_server_file_content", {
    path,
    root: root ?? null,
  });
}

/** 覆盖保存文本文件（task-23）；expectedSha256 用于并发冲突检测。 */
export async function saveServerFileContent(
  path: string,
  content: string,
  expectedSha256?: string | null,
  root?: string | null,
): Promise<SavedContent> {
  requireDesktop();
  return invoke<SavedContent>("save_server_file_content", {
    path,
    content,
    expectedSha256: expectedSha256 ?? null,
    root: root ?? null,
  });
}

/** 用系统默认应用打开服务端文件（task-26，先下载到本机临时目录）。 */
export async function openServerFile(
  path: string,
  root?: string | null,
): Promise<string> {
  requireDesktop();
  return invoke<string>("open_server_file", {
    path,
    root: root ?? null,
  });
}

/** 解压 zip 到同级同名目录（task-23）。 */
export async function extractZipFile(
  path: string,
  root?: string | null,
): Promise<ExtractResult> {
  requireDesktop();
  return invoke<ExtractResult>("extract_zip_file", {
    path,
    root: root ?? null,
  });
}

/**
 * 在服务器上执行单条终端命令（task-30）。
 *
 * sessionId 由前端生成（crypto.randomUUID），服务端按 session 维护 cwd；
 * 高危命令会返回以「高危命令已禁止执行」开头的错误 message。
 */
export async function terminalExec(
  sessionId: string,
  command: string,
  cwd?: string | null,
  root?: string | null,
): Promise<TerminalResult> {
  requireDesktop();
  return invoke<TerminalResult>("terminal_exec", {
    sessionId,
    command,
    cwd: cwd ?? null,
    root: root ?? null,
  });
}

/** 读取本地文件信息（仅文件名/大小，上传前展示用）。 */
export async function getLocalFileInfo(path: string): Promise<LocalFileInfo> {
  requireDesktop();
  return invoke<LocalFileInfo>("file_info", { path });
}

/** 订阅上传进度事件（契约 5.3）。 */
export async function listenUploadProgress(
  handler: (progress: UploadProgress) => void,
): Promise<UnlistenFn> {
  requireDesktop();
  return listen<UploadProgress>("upload-progress", (event) => handler(event.payload));
}

/** 选择单个文件，返回绝对路径；取消时返回 null。 */
export async function pickFile(): Promise<string | null> {
  requireDesktop();
  const selected = await open({
    title: "选择要上传的文件",
    multiple: false,
    directory: false,
  });
  if (Array.isArray(selected)) return selected[0] ?? null;
  return selected;
}

/** 选择下载保存路径；取消时返回 null。 */
export async function pickSavePath(
  defaultName: string,
  extensions?: string[],
): Promise<string | null> {
  requireDesktop();
  const filters =
    extensions && extensions.length > 0
      ? [{ name: "文件", extensions }]
      : undefined;
  return save({
    title: "选择保存位置",
    defaultPath: defaultName,
    filters,
  });
}
