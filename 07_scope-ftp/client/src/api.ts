/**
 * 前端访问 Rust 侧的唯一入口：全部通过 Tauri `invoke`，不直接发 HTTP。
 * 常规配置视图不含 appSecret；仅用户点击密码框眼睛时通过 reveal 命令读取本地自定义值。
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
  LocalNetworkInfo,
  AuditLogPage,
  AuditLogQuery,
  ConfigView,
  CopyResult,
  CreateResult,
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

/**
 * 在用户点击密码框眼睛时读取一台已保存服务器的 AppSecret。
 *
 * 配置文件里是 0600 本地明文，这里只读取，不做解密；常规 `get_config`
 * 视图仍不包含 secret。
 */
export async function revealServerSecret(id?: string | null): Promise<string> {
  requireDesktop();
  return invoke<string>("reveal_server_secret", { id: id ?? null });
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

/** 删除一台服务器（含其下所有代理服务器的本地配置与续传状态）。 */
export async function deleteServer(id: string): Promise<ConfigView> {
  requireDesktop();
  return invoke<ConfigView>("delete_server", { id });
}

export interface SaveRelayArgs {
  /** 不传/null 表示新建；传 id 表示编辑。 */
  id: string | null;
  /** 上级服务器 id（代理服务器挂在它下面）。 */
  parentId: string;
  name: string;
  /** 目标中转服务器地址（父服务器可访问）。 */
  serverUrl: string;
  deviceName: string;
  /** 代理服务器自己的 AppKey（必填）。 */
  appKey: string;
  /** 代理服务器自己的 AppSecret；编辑时 null 表示保留原值，新建时必填。 */
  appSecret: string | null;
}

/**
 * 新建或编辑一个代理服务器（转发链 v3）。
 *
 * 新建不会自动切换 active；编辑保存后需要重新验证连接。
 */
export async function saveRelay(args: SaveRelayArgs): Promise<ConfigView> {
  requireDesktop();
  return invoke<ConfigView>("save_relay", {
    id: args.id,
    parentId: args.parentId,
    name: args.name,
    serverUrl: args.serverUrl,
    deviceName: args.deviceName,
    appKey: args.appKey,
    appSecret: args.appSecret,
  });
}

export interface VerifyRelayPreviewArgs {
  /** 上级服务器 id（父链必须已在本地配置中）。 */
  parentId: string;
  /** 编辑已保存的代理服务器时传其 id，用于复用原 secret。 */
  existingId: string | null;
  name: string;
  serverUrl: string;
  deviceName: string;
  appKey: string;
  appSecret: string | null;
}

/**
 * 保存代理服务器前，用「父链 + 待保存的目标」整条链做预览验证；
 * 不落盘、不切换配置，验证成功后再调用 `saveRelay`。
 */
export async function verifyRelayPreview(
  args: VerifyRelayPreviewArgs,
): Promise<VerifyResult> {
  requireDesktop();
  return invoke<VerifyResult>("verify_relay_preview", {
    parentId: args.parentId,
    existingId: args.existingId,
    name: args.name,
    serverUrl: args.serverUrl,
    deviceName: args.deviceName,
    appKey: args.appKey,
    appSecret: args.appSecret,
  });
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

/** 单独验证一台已保存服务器（不切换 active，用于左侧同组连通性检测）。 */
export async function verifyServer(id: string): Promise<void> {
  requireDesktop();
  return invoke<void>("verify_server", { id });
}

export interface VerifyConnectionPreviewArgs {
  serverUrl: string;
  /** 自定义 AppKey；空串/不传表示使用内置默认。 */
  appKey?: string;
  /**
   * 自定义 AppSecret：`null`/不传表示沿用已保存服务器的原值（编辑场景），
   * 空串表示使用内置默认（新建或清除自定义密钥）。
   */
  appSecret?: string | null;
  /** 编辑已保存服务器时传其 id：沿用原 deviceId/deviceName 与未修改的 secret。 */
  existingId?: string | null;
}

/**
 * 添加 / 编辑服务器前的预览验证（契约 3.2）：用表单里的地址与凭据直接验证，
 * 不保存、不切换配置，因此不需要先把服务器加进列表。
 * 验证成功后才由调用方调用 `saveServer` / `saveRelay` 落盘。
 */
export async function verifyConnectionPreview(
  args: VerifyConnectionPreviewArgs,
): Promise<VerifyResult> {
  requireDesktop();
  return invoke<VerifyResult>("verify_connection_preview", {
    serverUrl: args.serverUrl,
    appKey: args.appKey ?? "",
    appSecret: args.appSecret ?? null,
    existingId: args.existingId ?? null,
  });
}

/**
 * 本机网络信息：物理地址（MAC）与默认路由网卡的局域网 IPv4。
 * 只用于详情页展示，不写配置、不参与签名。
 */
export async function getLocalNetworkInfo(): Promise<LocalNetworkInfo> {
  requireDesktop();
  return invoke<LocalNetworkInfo>("get_local_network_info");
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

/**
 * 按相对路径删除服务器目录中的文件/目录（FTP 式目录浏览）。
 *
 * `recursive=true` 时服务端一次性递归删除非空目录（服务端仍在用旧接口时会
 * 返回「目录非空，不能删除」，由调用方原样展示）。
 */
export async function deleteServerFile(
  path: string,
  root?: string | null,
  recursive?: boolean,
): Promise<void> {
  requireDesktop();
  // Tauri 可选参数必须显式传值（不能 undefined），否则 Rust 侧会报 missing key。
  await invoke("delete_server_file", {
    path,
    root: root ?? null,
    recursive: recursive ?? false,
  });
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

/** 在指定目录新建空文件或文件夹（`parent` 为空表示存储根目录）。 */
export async function createServerEntry(
  parent: string,
  name: string,
  kind: "file" | "directory",
  root?: string | null,
): Promise<CreateResult> {
  requireDesktop();
  return invoke<CreateResult>("create_server_entry", {
    parent,
    name,
    kind,
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
