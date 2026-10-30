/** 与 Rust 侧（`src-tauri/src/*.rs`）及 docs/02-api-contract.md 对齐的类型定义。 */

/** 一台服务器的视图（`get_config` 返回，不含任何凭据）。 */
export interface ServerView {
  id: string;
  /** 用户自定义名称，例如「开发环境」。 */
  name: string;
  serverUrl: string;
  /** 该服务器独立的设备 ID（用于设备绑定）。 */
  deviceId: string;
  /** 该服务器独立的设备名称。 */
  deviceName: string;
  /** 自定义 AppKey；空串表示使用内置默认。 */
  appKey: string;
  /** 当前生效的 AppKey（自定义或内置）。 */
  effectiveAppKey: string;
  /** 是否配置了自定义 AppSecret（不返回 secret 值）。 */
  hasCustomSecret: boolean;
  /** 服务器地址非空。 */
  configured: boolean;
}

/** `get_config` / `save_server` 等返回的配置视图（不含 appSecret）。 */
export interface ConfigView {
  activeServerId: string;
  servers: ServerView[];
  appKey: string;
  hasSecret: boolean;
}

/** 文件元数据（契约 3.4，也用于分片上传 complete 的返回值）。 */
export interface FileMeta {
  id: string;
  name: string;
  description?: string | null;
  size: number;
  contentType?: string | null;
  sha256: string;
  createdAt: string;
  /** 文件所属存储根 id（task-16；旧服务端/上传接口可能不返回）。 */
  rootId?: string | null;
}

/** 服务端的一个存储根（task-16 多存储根目录，`list_storage_roots`）。 */
export interface StorageRoot {
  /** 固定字符串 id（如 work / media），所有文件接口的 `root` 参数取该值。 */
  id: string;
  /** 展示名称（配置省略时服务端用 id 兜底）。 */
  name: string;
  /** 服务端解析后的绝对路径，供 UI 只读展示。 */
  path: string;
}

/** `list_storage_roots` 返回的 data。 */
export interface StorageRoots {
  /** 默认根 id；所有不传 root 的请求都作用于该根。 */
  defaultRootId: string;
  roots: StorageRoot[];
}

/** 服务端存储根目录下的一个条目（契约 3.8）。 */
export interface DirEntry {
  name: string;
  /** 相对存储根目录的路径，例如 `docs/2026`。 */
  path: string;
  type: "file" | "directory";
  /** 目录为 null，文件为字节数。 */
  size: number | null;
  modifiedAt: string;
}

/** `rename_server_file` 返回的 data（task-18）。 */
export interface RenameResult {
  /** 重命名前相对存储根的路径。 */
  oldPath: string;
  /** 重命名后相对存储根的路径。 */
  newPath: string;
  /** `file` 或 `directory`。 */
  type: "file" | "directory" | string;
  /** 所属存储根 id（旧服务端可能不返回）。 */
  rootId?: string | null;
}

/** `list_server_dir` 返回的 data（契约 3.8）。 */
export interface DirList {
  /** 当前目录相对存储根目录的路径，根目录为 `""`。 */
  path: string;
  /** 父目录相对路径，根目录为 null。 */
  parent: string | null;
  /** 是否因条目过多被截断。 */
  truncated: boolean;
  entries: DirEntry[];
}

/** 递归搜索命中的条目（task-14 契约 B）。 */
export interface SearchEntry {
  name: string;
  /** 相对存储根目录的完整路径，例如 `docs/2026/报告.pdf`。 */
  path: string;
  /** 所在目录；根目录下的条目为 `""`（兼容旧服务端的 null）。 */
  parent: string | null;
  type: "file" | "directory";
  size: number | null;
  modifiedAt: string;
}

/** `search_server_files` 返回的 data（task-14 契约 B）。 */
export interface SearchResult {
  keyword: string;
  truncated: boolean;
  entries: SearchEntry[];
}

/** `read_server_file_content` 返回的 data（task-23 在线编辑）。 */
export interface FileContent {
  /** 相对存储根的路径。 */
  path: string;
  /** UTF-8 文本内容。 */
  content: string;
  /** 字节数。 */
  size: number;
  /** 服务端内容 SHA-256（保存时作为 expectedSha256）。 */
  sha256: string;
  /** 是否允许在线编辑（二进制/过大为 false 或直接报错）。 */
  editable: boolean;
}

/** `save_server_file_content` 返回的 data（task-23）。 */
export interface SavedContent {
  path: string;
  size: number;
  sha256: string;
}

/** `terminal_exec` 返回的 data（task-30 远程终端）。 */
export interface TerminalResult {
  /** 前端生成的终端会话 id。 */
  sessionId: string;
  /** 本次执行的命令。 */
  command: string;
  /** 合并后的 stdout/stderr（可能含 ANSI，客户端负责去除）。 */
  output: string;
  /** 退出码；超时为 -1。 */
  exitCode: number;
  /** 执行后的会话工作目录（绝对路径）。 */
  cwd: string;
  /** 输出是否因 max-output-bytes 被截断。 */
  truncated: boolean;
  durationMs: number;
}

/** `extract_zip_file` 返回的 data（task-23）。 */
export interface ExtractResult {
  /** 解压目标目录（相对存储根，同级同名；重名时追加 ` (1)`）。 */
  targetDir: string;
  /** 解压条目数。 */
  entries: number;
  /** 解压字节数。 */
  bytes: number;
  /** 所属存储根 id（服务端可能不返回）。 */
  rootId?: string | null;
}

/** `POST /api/files/copy` 的响应（task-40）。 */
export interface CopyResult {
  root: string;
  path: string;
  type: "file" | "directory" | string;
  name: string;
}

/** `POST /api/files/move` 的响应（task-40，可跨根）。 */
export interface MoveResult {
  root: string;
  path: string;
  type: "file" | "directory" | string;
  name: string;
}

/** `GET /api/audit/logs` 的单条操作日志（task-45）。 */
export interface AuditLogItem {
  /** ISO-8601 时间（可能带时区）。 */
  ts: string;
  requestId: string;
  appKey: string | null;
  clientName: string | null;
  deviceId: string | null;
  clientIp: string | null;
  method: string | null;
  path: string | null;
  query: string | null;
  action: string | null;
  root: string | null;
  targetRoot: string | null;
  pathParam: string | null;
  targetDir: string | null;
  sessionId: string | null;
  terminalCommand: string | null;
  body: string | null;
  contentSha256: string | null;
  requestBytes: number | null;
  responseBytes: number | null;
  durationMs: number | null;
  httpStatus: number | null;
  success: boolean;
  error: string | null;
  userAgent: string | null;
  fileName: string | null;
}

/** `GET /api/audit/logs` 的分页 data（task-45）。 */
export interface AuditLogPage {
  items: AuditLogItem[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
}

/** 客户端操作日志查询参数；可选筛选传 null 表示不筛（显式传 null 避免 missing key）。 */
export type AuditLogCategory = "operation" | "connection" | "both";

export interface AuditLogQuery {
  page?: number;
  size?: number;
  action?: string | null;
  category?: AuditLogCategory | null;
  success?: boolean | null;
  keyword?: string | null;
  from?: string | null;
  to?: string | null;
}

/** 设备绑定信息（契约 3.2）。 */
export interface DeviceBinding {
  deviceId: string;
  deviceName: string;
  boundAt: string;
}

/** 服务端限制（契约 3.2）。 */
export interface ServerLimits {
  maxFileSize: number;
}

/** `verify_connection` 返回的 data（契约 3.2）。 */
export interface VerifyResult {
  appKey: string;
  name: string;
  serverTime: number;
  deviceBindingEnabled: boolean;
  device: DeviceBinding | null;
  limits: ServerLimits;
  /**
   * 服务端生效的存储根目录（绝对路径）。
   * 旧版服务端没有该字段，按可选处理，缺失时不做任何报错。
   */
  storageDir?: string;
  /** 多存储根列表（task-16；旧服务端没有该字段）。 */
  roots?: StorageRoot[];
  /** 默认根 id（task-16；旧服务端没有该字段）。 */
  defaultRootId?: string;
}

/** `activate` 返回的 data（契约 3.3）。 */
export interface ActivateResult {
  appKey: string;
  deviceId: string;
  deviceName: string;
  boundAt: string;
  serverTime: number;
}

/** `upload-progress` 事件 payload（契约 5.3）。 */
export interface UploadProgress {
  uploadId: string;
  sent: number;
  total: number;
}

/** 上传前读取的本地文件信息（Rust `file_info` 辅助命令）。 */
export interface LocalFileInfo {
  name: string;
  size: number;
}

export type ConnectionStatus =
  | "unconfigured"
  | "checking"
  | "connected"
  | "failed";

export interface ConnectionState {
  status: ConnectionStatus;
  message?: string;
}
