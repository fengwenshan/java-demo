//! Rust 侧 HTTP 客户端：每个请求都做 HMAC-SHA256 签名，错误统一为中文可展示字符串。
//!
//! 上传分为两条路径（见 [`crate::uploads`]）：
//! - 小文件（≤ 32MB）：一次 `multipart/form-data`；
//! - 大文件：`init → chunk → complete` 分片上传，并支持断点续传。

use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::StreamExt;
use reqwest::header::{HeaderMap, HeaderValue, CONTENT_TYPE};
use reqwest::multipart::{Form, Part};
use reqwest::{Body, Method, StatusCode};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_util::io::ReaderStream;
use uuid::Uuid;

use crate::config::ServerProfile;
use crate::signer::{self, EMPTY_SHA256};

const HEADER_APP_KEY: &str = "X-App-Key";
const HEADER_TIMESTAMP: &str = "X-Timestamp";
const HEADER_NONCE: &str = "X-Nonce";
const HEADER_SIGNATURE: &str = "X-Signature";
const HEADER_DEVICE_ID: &str = "X-Device-Id";
const HEADER_CONTENT_SHA256: &str = "X-Content-Sha256";

/// 分片上传服务端返回的默认分片大小（8MB）；以 `init` 返回值为准。
pub const DEFAULT_CHUNK_SIZE: u64 = 8 * 1024 * 1024;

/// 普通接口（JSON/DELETE）遇到限流/网络抖动时的最多尝试次数。
const MAX_API_ATTEMPTS: u32 = 3;

/// 文件元数据（契约 3.4）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileMeta {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub content_type: Option<String>,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub created_at: String,
    /// 文件所属存储根 id（task-16；旧服务端/上传响应可能为空）。
    #[serde(default)]
    pub root_id: Option<String>,
}

/// 文件分页列表（契约 3.4）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileList {
    #[serde(default)]
    pub items: Vec<FileMeta>,
    #[serde(default)]
    pub total: u64,
    #[serde(default)]
    pub page: u32,
    #[serde(default)]
    pub size: u32,
}

/// `POST /api/files/upload/init` 的响应（契约 3.11 分片上传）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChunkInit {
    #[serde(default)]
    pub upload_id: String,
    #[serde(default)]
    pub chunk_size: u64,
}

impl ChunkInit {
    /// 分片大小（服务端未返回/返回 0 时退回默认 8MB）。
    pub fn effective_chunk_size(&self) -> u64 {
        if self.chunk_size == 0 {
            DEFAULT_CHUNK_SIZE
        } else {
            self.chunk_size
        }
    }
}

/// `GET /api/files/upload/{uploadId}` 的响应（续传状态）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChunkStatus {
    #[serde(default)]
    pub upload_id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub chunk_size: u64,
    /// 服务端已收到的分片索引（升序）。
    #[serde(default)]
    pub received: Vec<u32>,
}

impl ChunkStatus {
    pub fn effective_chunk_size(&self) -> u64 {
        if self.chunk_size == 0 {
            DEFAULT_CHUNK_SIZE
        } else {
            self.chunk_size
        }
    }
}

/// 服务端的一个存储根（task-16 多存储根目录，`GET /api/storage/roots`）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageRoot {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub path: String,
}

/// `GET /api/storage/roots` 的响应（`defaultRootId` + 根列表）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageRoots {
    #[serde(default)]
    pub default_root_id: String,
    #[serde(default)]
    pub roots: Vec<StorageRoot>,
}

impl StorageRoots {
    /// 默认根的 id；服务端未返回时退回第一个根，都没有则为空。
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn effective_default_root_id(&self) -> String {
        if !self.default_root_id.trim().is_empty() {
            return self.default_root_id.trim().to_string();
        }
        self.roots
            .iter()
            .find(|root| !root.id.trim().is_empty())
            .map(|root| root.id.trim().to_string())
            .unwrap_or_default()
    }
}

/// `POST /api/files/rename` 的响应（task-18：重命名文件/目录）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RenameResult {
    #[serde(default)]
    pub old_path: String,
    #[serde(default)]
    pub new_path: String,
    /// `file` 或 `directory`（契约字段名为 `type`）。
    #[serde(default, rename = "type")]
    pub entry_type: String,
    /// 所属存储根 id（task-16；服务端可能不返回）。
    #[serde(default)]
    pub root_id: Option<String>,
}

/// `POST /api/files/copy` 的响应（task-40：原地拷贝文件/目录）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CopyResult {
    #[serde(default)]
    pub root: String,
    #[serde(default)]
    pub path: String,
    #[serde(default, rename = "type")]
    pub entry_type: String,
    #[serde(default)]
    pub name: String,
}

/// `POST /api/files/move` 的响应（task-40：移动文件/目录，可跨根）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MoveResult {
    #[serde(default)]
    pub root: String,
    #[serde(default)]
    pub path: String,
    #[serde(default, rename = "type")]
    pub entry_type: String,
    #[serde(default)]
    pub name: String,
}

/// `GET /api/audit/logs` 的单条操作日志（task-45）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuditLogItem {
    #[serde(default)]
    pub ts: String,
    #[serde(default)]
    pub request_id: String,
    #[serde(default)]
    pub app_key: Option<String>,
    #[serde(default)]
    pub client_name: Option<String>,
    #[serde(default)]
    pub device_id: Option<String>,
    #[serde(default)]
    pub client_ip: Option<String>,
    #[serde(default)]
    pub method: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub query: Option<String>,
    #[serde(default)]
    pub action: Option<String>,
    #[serde(default)]
    pub root: Option<String>,
    #[serde(default)]
    pub target_root: Option<String>,
    #[serde(default)]
    pub path_param: Option<String>,
    #[serde(default)]
    pub target_dir: Option<String>,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub terminal_command: Option<String>,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub content_sha256: Option<String>,
    #[serde(default)]
    pub request_bytes: Option<i64>,
    #[serde(default)]
    pub response_bytes: Option<i64>,
    #[serde(default)]
    pub duration_ms: Option<i64>,
    #[serde(default)]
    pub http_status: Option<i64>,
    #[serde(default)]
    pub success: bool,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default)]
    pub user_agent: Option<String>,
    #[serde(default)]
    pub file_name: Option<String>,
}

/// `GET /api/audit/logs` 的分页响应（task-45）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuditLogPage {
    #[serde(default)]
    pub items: Vec<AuditLogItem>,
    #[serde(default)]
    pub total: i64,
    #[serde(default)]
    pub page: i64,
    #[serde(default)]
    pub size: i64,
    #[serde(default)]
    pub has_more: bool,
}

/// `GET /api/files/content` 的响应（task-23：在线编辑读取文本文件）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileContent {
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub content: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub editable: bool,
}

/// `PUT /api/files/content` 的响应（task-23：覆盖保存）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedContent {
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub sha256: String,
}

/// `POST /api/files/extract` 的响应（task-23：解压 zip 到同级同名目录）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExtractResult {
    #[serde(default)]
    pub target_dir: String,
    #[serde(default)]
    pub entries: u64,
    #[serde(default)]
    pub bytes: u64,
    /// 所属存储根 id（服务端可能不返回）。
    #[serde(default)]
    pub root_id: Option<String>,
}

/// 统一响应信封。
#[derive(Debug, Deserialize)]
struct Envelope<T> {
    #[serde(default)]
    code: i64,
    #[serde(default)]
    message: String,
    data: Option<T>,
}

/// 归一化存储根参数：`None` / 空串表示「服务端默认根」（不传 `root`）。
fn normalize_root(root: Option<&str>) -> Option<String> {
    root.map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|value| value.to_string())
}

/// 追加一个查询参数（值会编码，随后 `root` 等参数一起参与 canonicalQuery 签名）。
fn push_query(query: &mut String, key: &str, value: &str) {
    if !query.is_empty() {
        query.push('&');
    }
    query.push_str(key);
    query.push('=');
    query.push_str(&signer::encode_query_component(value));
}

fn with_query(target: &str, query: &str) -> String {
    if query.is_empty() {
        target.to_string()
    } else {
        format!("{target}?{query}")
    }
}

/// 构造 `GET /api/audit/logs` 的查询串（task-45）。
///
/// `page`/`size` 总是携带；可选筛选参数为空时省略，`success` 序列化为 `true`/`false`。
fn audit_logs_target(
    page: u32,
    size: u32,
    action: Option<&str>,
    category: Option<&str>,
    success: Option<bool>,
    keyword: Option<&str>,
    from: Option<&str>,
    to: Option<&str>,
) -> Result<String, String> {
    let page = page.max(1);
    let size = size.clamp(1, 200);
    let mut query = String::new();
    push_query(&mut query, "page", &page.to_string());
    push_query(&mut query, "size", &size.to_string());
    if let Some(action) = action.map(str::trim).filter(|value| !value.is_empty()) {
        push_query(&mut query, "action", action);
    }
    if let Some(category) = category.map(str::trim).filter(|value| !value.is_empty()) {
        push_query(&mut query, "category", category);
    }
    if let Some(success) = success {
        push_query(&mut query, "success", if success { "true" } else { "false" });
    }
    if let Some(keyword) = keyword.map(str::trim).filter(|value| !value.is_empty()) {
        push_query(&mut query, "keyword", keyword);
    }
    if let Some(from) = from.map(str::trim).filter(|value| !value.is_empty()) {
        push_query(&mut query, "from", from);
    }
    if let Some(to) = to.map(str::trim).filter(|value| !value.is_empty()) {
        push_query(&mut query, "to", to);
    }
    Ok(with_query("/api/audit/logs", &query))
}

/// 校验并归一化单段条目名（重命名用）：
/// 非空、不能是 `.`/`..`、不能包含路径分隔符 `/` `\`、不能包含控制字符。
fn validate_entry_name(raw: &str) -> Result<String, String> {
    let name = raw.trim();
    if name.is_empty() {
        return Err("名称不能为空".to_string());
    }
    if name == "." || name == ".." {
        return Err("名称不能是 . 或 ..".to_string());
    }
    if name.contains('/') || name.contains('\\') {
        return Err("名称不能包含路径分隔符 / 或 \\".to_string());
    }
    if name.chars().any(char::is_control) {
        return Err("名称不能包含控制字符".to_string());
    }
    Ok(name.to_string())
}

/// 构造 `POST /api/files/rename` 的目标地址与 JSON 请求体（task-18）。
///
/// `root` 放在 JSON 体里（契约 `{root?, path, newName}`），因此参与 payloadHash
/// 签名而不是 canonicalQuery；`None`/空表示默认根，此时请求体不带 root（旧服务端兼容）。
fn rename_request(
    path: &str,
    new_name: &str,
    root: Option<&str>,
) -> Result<(&'static str, Vec<u8>), String> {
    let path = path.trim();
    if path.is_empty() {
        return Err("路径不能为空".to_string());
    }
    let new_name = validate_entry_name(new_name)?;
    let mut payload = json!({
        "path": path,
        "newName": new_name,
    });
    if let Some(root) = normalize_root(root) {
        payload["root"] = Value::String(root);
    }
    let body = serde_json::to_vec(&payload).map_err(|e| format!("请求体序列化失败：{e}"))?;
    Ok(("/api/files/rename", body))
}

/// 构造 `POST /api/files/copy` 的 JSON 请求体（task-40）。
fn copy_request(path: &str, root: Option<&str>) -> Result<(&'static str, Vec<u8>), String> {
    let path = path.trim();
    if path.is_empty() {
        return Err("路径不能为空".to_string());
    }
    let mut payload = json!({ "path": path });
    if let Some(root) = normalize_root(root) {
        payload["root"] = Value::String(root);
    }
    let body = serde_json::to_vec(&payload).map_err(|e| format!("请求体序列化失败：{e}"))?;
    Ok(("/api/files/copy", body))
}

/// 规范化目标目录：去掉首尾空白与斜杠；`""` 表示目标根根目录（task-40）。
fn normalize_target_dir(raw: &str) -> String {
    raw.trim().trim_matches('/').to_string()
}

/// 构造 `POST /api/files/move` 的 JSON 请求体（task-40）。
///
/// `targetRoot` 省略表示同根；`targetDir=""` 表示目标根根目录。
fn move_request(
    path: &str,
    root: Option<&str>,
    target_root: Option<&str>,
    target_dir: &str,
) -> Result<(&'static str, Vec<u8>), String> {
    let path = path.trim();
    if path.is_empty() {
        return Err("路径不能为空".to_string());
    }
    let target_dir = normalize_target_dir(target_dir);
    let mut payload = json!({
        "path": path,
        "targetDir": target_dir,
    });
    if let Some(root) = normalize_root(root) {
        payload["root"] = Value::String(root);
    }
    if let Some(target_root) = normalize_root(target_root) {
        payload["targetRoot"] = Value::String(target_root);
    }
    let body = serde_json::to_vec(&payload).map_err(|e| format!("请求体序列化失败：{e}"))?;
    Ok(("/api/files/move", body))
}

/// 构造 `GET /api/files/content` 的查询串（path + 可选 root，均参与 canonicalQuery 签名）。
fn content_target(path: &str, root: Option<&str>) -> Result<String, String> {
    let path = path.trim();
    if path.is_empty() {
        return Err("文件路径不能为空".to_string());
    }
    let mut query = String::new();
    push_query(&mut query, "path", path);
    if let Some(root) = normalize_root(root) {
        push_query(&mut query, "root", &root);
    }
    Ok(with_query("/api/files/content", &query))
}

/// 构造 `PUT /api/files/content` 的目标地址与 JSON 请求体（task-23）。
///
/// `root` / `expectedSha256` 放在 JSON 体里参与 payloadHash；默认根不携带 root
/// （旧单根服务端兼容）。`expectedSha256` 为空表示不做并发校验。
fn save_content_request(
    path: &str,
    content: &str,
    expected_sha256: Option<&str>,
    root: Option<&str>,
) -> Result<(&'static str, Vec<u8>), String> {
    let path = path.trim();
    if path.is_empty() {
        return Err("文件路径不能为空".to_string());
    }
    let mut payload = json!({
        "path": path,
        "content": content,
    });
    if let Some(expected) = expected_sha256.map(str::trim).filter(|value| !value.is_empty()) {
        payload["expectedSha256"] = Value::String(expected.to_string());
    }
    if let Some(root) = normalize_root(root) {
        payload["root"] = Value::String(root);
    }
    let body = serde_json::to_vec(&payload).map_err(|e| format!("请求体序列化失败：{e}"))?;
    Ok(("/api/files/content", body))
}

/// 构造 `POST /api/files/extract` 的目标地址与 JSON 请求体（task-23）。
fn extract_zip_request(path: &str, root: Option<&str>) -> Result<(&'static str, Vec<u8>), String> {
    let path = path.trim();
    if path.is_empty() {
        return Err("压缩包路径不能为空".to_string());
    }
    if !path.to_ascii_lowercase().ends_with(".zip") {
        return Err("仅支持解压 .zip 文件".to_string());
    }
    let mut payload = json!({ "path": path });
    if let Some(root) = normalize_root(root) {
        payload["root"] = Value::String(root.to_string());
    }
    let body = serde_json::to_vec(&payload).map_err(|e| format!("请求体序列化失败：{e}"))?;
    Ok(("/api/files/extract", body))
}

/// 终端执行返回（task-30，契约 `POST /api/terminal/exec`）。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TerminalResult {
    #[serde(rename = "sessionId")]
    pub session_id: String,
    pub command: String,
    pub output: String,
    #[serde(rename = "exitCode")]
    pub exit_code: i32,
    pub cwd: String,
    pub truncated: bool,
    #[serde(rename = "durationMs")]
    pub duration_ms: u64,
}

/// 校验前端生成的终端 sessionId（8-64 位 `[A-Za-z0-9_-]`）。
fn validate_terminal_session_id(session_id: &str) -> Result<&str, String> {
    let session_id = session_id.trim();
    if !(8..=64).contains(&session_id.chars().count()) {
        return Err("终端会话 ID 长度必须为 8-64 位".to_string());
    }
    if !session_id
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '_' || ch == '-')
    {
        return Err("终端会话 ID 只能包含字母、数字、下划线和连字符".to_string());
    }
    Ok(session_id)
}

/// 构造 `POST /api/terminal/exec` 的 JSON 请求体（task-30）。
fn terminal_exec_request(
    session_id: &str,
    command: &str,
    cwd: Option<&str>,
    root: Option<&str>,
) -> Result<(&'static str, Vec<u8>), String> {
    let session_id = validate_terminal_session_id(session_id)?;
    let command = command.trim();
    if command.is_empty() {
        return Err("命令不能为空".to_string());
    }
    if command.chars().count() > 4096 {
        return Err("命令过长（最多 4096 字符）".to_string());
    }
    if command.contains('\n') || command.contains('\r') || command.contains('\0') {
        return Err("终端只支持单行命令（不允许换行或 NUL）".to_string());
    }
    let mut payload = json!({
        "sessionId": session_id,
        "command": command,
    });
    if let Some(cwd) = cwd.map(str::trim).filter(|value| !value.is_empty()) {
        payload["cwd"] = Value::String(cwd.to_string());
    }
    if let Some(root) = normalize_root(root) {
        payload["root"] = Value::String(root);
    }
    let body = serde_json::to_vec(&payload).map_err(|e| format!("请求体序列化失败：{e}"))?;
    Ok(("/api/terminal/exec", body))
}

/// 「用默认应用打开」临时文件名最大字符数（避免路径过长）。
const OPEN_TEMP_FILE_MAX_CHARS: usize = 120;

/// 把服务端相对路径安全化成临时目录中的文件名（task-26）。
///
/// 只取最后一段，替换路径分隔符/控制字符/Windows 保留字符，去掉首尾的 `.` 与空格，
/// 空名回退 `download`，并截断到 [`OPEN_TEMP_FILE_MAX_CHARS`] 个字符。
pub fn open_temp_file_name(path: &str) -> String {
    let raw = path
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or_default()
        .trim();
    let mut safe = String::with_capacity(raw.len());
    for ch in raw.chars() {
        if ch.is_control() || matches!(ch, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') {
            safe.push('_');
        } else {
            safe.push(ch);
        }
    }
    let safe: String = safe
        .trim_matches(|ch| ch == '.' || ch == ' ')
        .chars()
        .take(OPEN_TEMP_FILE_MAX_CHARS)
        .collect();
    if safe.is_empty() {
        "download".to_string()
    } else {
        safe
    }
}

/// 用默认应用打开时的临时目录：`$TMPDIR/scop-ftp-open`（同名文件覆盖写）。
pub fn open_temp_dir() -> PathBuf {
    std::env::temp_dir().join("scop-ftp-open")
}

/// 计算待打开文件的临时路径；目录不存在时创建。
pub fn open_temp_path(path: &str) -> Result<PathBuf, String> {
    if path.trim().is_empty() {
        return Err("文件路径不能为空".to_string());
    }
    let dir = open_temp_dir();
    std::fs::create_dir_all(&dir).map_err(|error| format!("创建临时目录失败：{error}"))?;
    Ok(dir.join(open_temp_file_name(path)))
}

/// 「打开方式」目标应用（task-27）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpenApplication {
    Default,
    VsCode,
    TextEdit,
    Notepad,
    Choose,
}

impl OpenApplication {
    /// 解析前端传入的 application 字符串；未知值返回中文错误。
    pub fn parse(value: &str) -> Result<Self, String> {
        match value.trim() {
            "default" => Ok(Self::Default),
            "vscode" => Ok(Self::VsCode),
            "textedit" => Ok(Self::TextEdit),
            "notepad" => Ok(Self::Notepad),
            "choose" => Ok(Self::Choose),
            other => Err(format!("不支持的打开方式：{other}")),
        }
    }
}

/// macOS `choose application` 脚本：弹系统应用选择器，再用所选应用打开文件。
#[cfg(target_os = "macos")]
const MAC_CHOOSE_SCRIPT: &str = r#"on run argv
  set appPath to POSIX path of (choose application with prompt "选择打开方式")
  do shell script "open -a " & quoted form of appPath & " " & quoted form of (item 1 of argv)
end run"#;

/// 返回各平台「用指定应用打开文件」的命令（便于单测，不实际启动应用）。
fn open_command_parts(
    path: &Path,
    application: OpenApplication,
) -> Result<(String, Vec<String>), String> {
    let target = path.to_string_lossy().into_owned();
    #[cfg(target_os = "macos")]
    {
        let (program, args): (&str, Vec<String>) = match application {
            OpenApplication::Default => ("open", vec![target]),
            OpenApplication::VsCode => (
                "open",
                vec!["-a".to_string(), "Visual Studio Code".to_string(), target],
            ),
            // macOS 没有「记事本」，文本类应用统一用 TextEdit。
            OpenApplication::TextEdit | OpenApplication::Notepad => (
                "open",
                vec!["-a".to_string(), "TextEdit".to_string(), target],
            ),
            OpenApplication::Choose => {
                ("osascript", vec!["-e".to_string(), MAC_CHOOSE_SCRIPT.to_string(), target])
            }
        };
        Ok((program.to_string(), args))
    }
    #[cfg(target_os = "windows")]
    {
        let (program, args): (&str, Vec<String>) = match application {
            OpenApplication::Default => (
                "cmd",
                vec!["/c".to_string(), "start".to_string(), "".to_string(), target],
            ),
            OpenApplication::VsCode => ("code", vec![target]),
            OpenApplication::TextEdit | OpenApplication::Notepad => {
                ("notepad", vec![target])
            }
            OpenApplication::Choose => (
                "rundll32",
                vec!["shell32.dll,OpenAs_RunDLL".to_string(), target],
            ),
        };
        Ok((program.to_string(), args))
    }
    #[cfg(all(not(target_os = "macos"), not(target_os = "windows")))]
    {
        let (program, args): (&str, Vec<String>) = match application {
            OpenApplication::Default => ("xdg-open", vec![target]),
            OpenApplication::VsCode => ("code", vec![target]),
            OpenApplication::TextEdit | OpenApplication::Notepad => {
                ("gedit", vec![target])
            }
            OpenApplication::Choose => {
                return Err("当前系统不支持选择打开方式".to_string());
            }
        };
        Ok((program.to_string(), args))
    }
}

/// 用指定应用打开本地文件；只负责拉起进程，不等待应用退出。
pub fn open_with_application(path: &Path, application: OpenApplication) -> Result<(), String> {
    let (program, args) = open_command_parts(path, application)?;
    Command::new(&program)
        .args(&args)
        .spawn()
        .map(|_| ())
        .map_err(|error| match application {
            OpenApplication::Default => format!("无法用默认应用打开文件：{error}"),
            OpenApplication::VsCode => {
                format!("未找到 VS Code（请确认已安装并加入 PATH）：{error}")
            }
            OpenApplication::TextEdit | OpenApplication::Notepad => {
                format!("无法用文本应用打开文件：{error}")
            }
            OpenApplication::Choose => format!("无法打开应用选择器：{error}"),
        })
}

/// 用系统默认应用打开本地文件。
pub fn open_with_default_app(path: &Path) -> Result<(), String> {
    open_with_application(path, OpenApplication::Default)
}

/// 一次请求所需的配置快照（当前激活服务器的地址与设备身份）。/// 一次请求所需的配置快照（当前激活服务器的地址与设备身份）。
#[derive(Debug, Clone)]
pub struct Api {
    http: reqwest::Client,
    server: ServerProfile,
}

impl Api {
    pub fn new(http: reqwest::Client, server: ServerProfile) -> Self {
        Self { http, server }
    }

    /// 当前服务器配置（只读）。
    pub fn server(&self) -> &ServerProfile {
        &self.server
    }

    /// 服务端根地址（去掉末尾 `/`）。
    fn base_url(&self) -> Result<String, String> {
        let url = self.server.server_url.trim().trim_end_matches('/');
        if url.is_empty() {
            return Err("请先在「设置」中配置服务器地址".to_string());
        }
        Ok(url.to_string())
    }

    fn require_configured(&self) -> Result<(), String> {
        if !self.server.is_configured() {
            return Err("请先在「设置」中配置服务器地址".to_string());
        }
        Ok(())
    }

    /// 设备 UUID（每台服务器独立）。
    fn device_id(&self) -> &str {
        self.server.device_id.trim()
    }

    /// 构造签名请求头。`target` 中的 query 会被规范化后参与签名，并原样用于实际请求。
    ///
    /// AppKey / AppSecret 默认使用客户端内置值；每台服务器可在设置中覆盖（见 [`ServerProfile`]）。
    fn auth_headers(
        &self,
        method: &str,
        path: &str,
        canonical_query: &str,
        payload_hash: &str,
        extra: &[(&'static str, String)],
    ) -> Result<HeaderMap, String> {
        let timestamp = now_millis();
        let nonce = Uuid::new_v4().to_string();
        let signature = signer::sign_request(
            self.server.effective_app_secret(),
            self.server.effective_app_key(),
            timestamp,
            &nonce,
            method,
            path,
            canonical_query,
            payload_hash,
        );

        let mut headers = HeaderMap::new();
        let mut insert = |name: &'static str, value: &str| -> Result<(), String> {
            let value =
                HeaderValue::from_str(value).map_err(|e| format!("构造请求头 {name} 失败：{e}"))?;
            headers.insert(name, value);
            Ok(())
        };
        insert(HEADER_APP_KEY, self.server.effective_app_key())?;
        insert(HEADER_TIMESTAMP, &timestamp.to_string())?;
        insert(HEADER_NONCE, &nonce)?;
        insert(HEADER_SIGNATURE, &signature)?;
        if !self.device_id().is_empty() {
            insert(HEADER_DEVICE_ID, self.device_id())?;
        }
        for (name, value) in extra {
            insert(name, value)?;
        }
        Ok(headers)
    }

    /// 拼接实际请求 URL（查询串使用签名时的规范化结果）。
    fn build_url(&self, path: &str, canonical_query: &str) -> Result<String, String> {
        let mut url = format!("{}{}", self.base_url()?, path);
        if !canonical_query.is_empty() {
            url.push('?');
            url.push_str(canonical_query);
        }
        Ok(url)
    }

    /// 发送带签名的 JSON 请求（GET/POST/DELETE），解析统一信封。
    ///
    /// 网络抖动 / 超时 / 限流 / 5xx 会自动重试（最多 [`MAX_API_ATTEMPTS`] 次）。
    async fn send_json<T: DeserializeOwned>(
        &self,
        method: Method,
        target: &str,
        body: Option<Vec<u8>>,
    ) -> Result<T, String> {
        let mut attempt = 1;
        loop {
            match self
                .send_json_once::<T>(method.clone(), target, body.as_deref())
                .await
            {
                Ok(value) => return Ok(value),
                Err(err) => {
                    if attempt >= MAX_API_ATTEMPTS || !is_retryable_error(&err) {
                        return Err(err);
                    }
                    tokio::time::sleep(Duration::from_millis(400 * attempt as u64)).await;
                    attempt += 1;
                }
            }
        }
    }

    async fn send_json_once<T: DeserializeOwned>(
        &self,
        method: Method,
        target: &str,
        body: Option<&[u8]>,
    ) -> Result<T, String> {
        let (path, raw_query) = signer::split_path_and_query(target);
        let canonical_query = signer::canonical_query(raw_query);
        let payload_hash = body
            .map(signer::sha256_hex)
            .unwrap_or_else(|| EMPTY_SHA256.to_string());

        let headers =
            self.auth_headers(method.as_str(), path, &canonical_query, &payload_hash, &[])?;
        let url = self.build_url(path, &canonical_query)?;

        let mut request = self.http.request(method, url).headers(headers);
        if let Some(bytes) = body {
            request = request
                .header(CONTENT_TYPE, "application/json; charset=utf-8")
                .body(bytes.to_vec());
        }

        let response = request.send().await.map_err(network_error)?;
        let status = response.status();
        let bytes = response.bytes().await.map_err(network_error)?;
        parse_envelope::<T>(&bytes, status)
    }

    /// 发送无返回数据的有签名请求（如 DELETE）。
    async fn send_void(&self, method: Method, target: &str) -> Result<(), String> {
        let mut attempt = 1;
        loop {
            match self.send_void_once(method.clone(), target).await {
                Ok(()) => return Ok(()),
                Err(err) => {
                    if attempt >= MAX_API_ATTEMPTS || !is_retryable_error(&err) {
                        return Err(err);
                    }
                    tokio::time::sleep(Duration::from_millis(400 * attempt as u64)).await;
                    attempt += 1;
                }
            }
        }
    }

    async fn send_void_once(&self, method: Method, target: &str) -> Result<(), String> {
        let (path, raw_query) = signer::split_path_and_query(target);
        let canonical_query = signer::canonical_query(raw_query);
        let headers =
            self.auth_headers(method.as_str(), path, &canonical_query, EMPTY_SHA256, &[])?;
        let url = self.build_url(path, &canonical_query)?;

        let response = self
            .http
            .request(method, url)
            .headers(headers)
            .send()
            .await
            .map_err(network_error)?;
        let status = response.status();
        let bytes = response.bytes().await.map_err(network_error)?;

        // DELETE 的响应 data 可能为 null，这里只关心业务是否成功。
        if let Ok(envelope) = serde_json::from_slice::<Envelope<Value>>(&bytes) {
            if envelope.code == 0 {
                return Ok(());
            }
            return Err(api_error(envelope.code, &envelope.message));
        }
        if status.is_success() {
            Ok(())
        } else {
            Err(status_error(status))
        }
    }

    /// 健康检查（`GET /api/health`，免签名），用于区分「地址不通」与「签名/授权错误」。
    pub async fn health(&self) -> Result<Value, String> {
        let url = format!("{}/api/health", self.base_url()?);
        let response = self
            .http
            .get(url)
            .timeout(Duration::from_secs(8))
            .send()
            .await
            .map_err(network_error)?;
        let status = response.status();
        let bytes = response.bytes().await.map_err(network_error)?;
        let data: Value = parse_envelope::<Value>(&bytes, status)?;
        let server_status = data
            .get("status")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if server_status.eq_ignore_ascii_case("UP") {
            Ok(data)
        } else {
            Err("服务器健康检查未通过，请确认服务端已启动".to_string())
        }
    }

    /// `GET /api/storage/roots`：列出服务端可用的存储根（task-16 多存储根）。
    pub async fn list_storage_roots(&self) -> Result<StorageRoots, String> {
        self.require_configured()?;
        self.send_json::<StorageRoots>(Method::GET, "/api/storage/roots", None)
            .await
    }

    /// `GET /api/auth/verify`：验证连接（契约 3.2）。
    pub async fn verify_connection(&self) -> Result<Value, String> {
        self.require_configured()?;
        self.health().await?;
        self.send_json::<Value>(Method::GET, "/api/auth/verify", None)
            .await
    }

    /// `POST /api/auth/activate`：设备激活/绑定（契约 3.3），使用本服务器的 deviceId / deviceName。
    pub async fn activate(&self) -> Result<Value, String> {
        self.require_configured()?;
        let device_id = self.device_id();
        let device_name = self.server.device_name.trim();
        if device_id.is_empty() {
            return Err("缺少设备 ID，请重启客户端后重试".to_string());
        }
        if device_name.is_empty() {
            return Err("请先在「设置」中填写设备名称".to_string());
        }
        let body = serde_json::to_vec(&json!({
            "deviceId": device_id,
            "deviceName": device_name,
        }))
        .map_err(|e| format!("请求体序列化失败：{e}"))?;
        self.send_json::<Value>(Method::POST, "/api/auth/activate", Some(body))
            .await
    }

    /// `GET /api/files`：分页查询文件列表（契约 3.4）。
    pub async fn list_files(
        &self,
        page: u32,
        size: u32,
        keyword: Option<&str>,
        root: Option<&str>,
    ) -> Result<FileList, String> {
        self.require_configured()?;
        let page = page.max(1);
        let size = size.clamp(1, 100);
        let mut query = format!("page={page}&size={size}");
        if let Some(keyword) = keyword.map(str::trim).filter(|value| !value.is_empty()) {
            push_query(&mut query, "keyword", keyword);
        }
        if let Some(root) = normalize_root(root) {
            push_query(&mut query, "root", &root);
        }
        let target = format!("/api/files?{query}");
        self.send_json::<FileList>(Method::GET, &target, None).await
    }

    /// `GET /api/files/dir`：浏览服务端存储根目录下的真实目录（契约 3.8）。
    ///
    /// `path` 为相对存储根目录的路径，`None` / 空串表示根目录。
    pub async fn list_server_dir(
        &self,
        path: Option<&str>,
        root: Option<&str>,
    ) -> Result<Value, String> {
        self.require_configured()?;
        let path = path.map(str::trim).filter(|value| !value.is_empty());
        let mut query = String::new();
        if let Some(path) = path {
            push_query(&mut query, "path", path);
        }
        if let Some(root) = normalize_root(root) {
            push_query(&mut query, "root", &root);
        }
        let target = with_query("/api/files/dir", &query);
        self.send_json::<Value>(Method::GET, &target, None).await
    }

    /// `GET /api/files/search?keyword=&limit=`：在存储根目录内递归搜索（task-14 契约 B）。
    ///
    /// `keyword` 参与 canonicalQuery 签名。
    pub async fn search_server_files(
        &self,
        keyword: &str,
        limit: u32,
        root: Option<&str>,
    ) -> Result<Value, String> {
        self.require_configured()?;
        let keyword = keyword.trim();
        if keyword.is_empty() {
            return Err("请输入搜索关键字".to_string());
        }
        if keyword.chars().count() > 100 {
            return Err("搜索关键字不能超过 100 个字符".to_string());
        }
        let limit = limit.clamp(1, 500);
        let mut query = String::new();
        push_query(&mut query, "keyword", keyword);
        push_query(&mut query, "limit", &limit.to_string());
        if let Some(root) = normalize_root(root) {
            push_query(&mut query, "root", &root);
        }
        let target = with_query("/api/files/search", &query);
        self.send_json::<Value>(Method::GET, &target, None).await
    }

    /// `GET /api/audit/logs`：分页查询当前 appKey 的操作日志（task-45）。
    ///
    /// 服务端强制只返回当前签名 appKey 的记录；未启用/DB 不可用等错误 message 原样透传。
    #[allow(clippy::too_many_arguments)]
    pub async fn list_audit_logs(
        &self,
        page: u32,
        size: u32,
        action: Option<&str>,
        category: Option<&str>,
        success: Option<bool>,
        keyword: Option<&str>,
        from: Option<&str>,
        to: Option<&str>,
    ) -> Result<AuditLogPage, String> {
        self.require_configured()?;
        let target = audit_logs_target(page, size, action, category, success, keyword, from, to)?;
        self.send_json::<AuditLogPage>(Method::GET, &target, None)
            .await
    }

    // ------------------------------------------------------------------
    // 分片上传（契约 task-14 A）
    // ------------------------------------------------------------------

    /// `POST /api/files/upload/init`：创建分片上传会话。
    pub async fn init_chunked_upload(
        &self,
        name: &str,
        dir: Option<&str>,
        size: u64,
        sha256: &str,
        description: Option<&str>,
        root: Option<&str>,
    ) -> Result<ChunkInit, String> {
        self.require_configured()?;
        if name.trim().is_empty() {
            return Err("文件名不能为空".to_string());
        }
        if size == 0 {
            return Err("不能上传空文件".to_string());
        }
        let mut payload = json!({
            "name": name,
            "size": size,
            "sha256": sha256,
        });
        if let Some(dir) = dir.map(str::trim).filter(|value| !value.is_empty()) {
            payload["dir"] = Value::String(dir.to_string());
        }
        if let Some(root) = normalize_root(root) {
            payload["root"] = Value::String(root);
        }
        if let Some(description) = description.map(str::trim).filter(|value| !value.is_empty()) {
            payload["description"] = Value::String(description.to_string());
        }
        let body = serde_json::to_vec(&payload).map_err(|e| format!("请求体序列化失败：{e}"))?;
        let init: ChunkInit = self
            .send_json::<ChunkInit>(Method::POST, "/api/files/upload/init", Some(body))
            .await?;
        if init.upload_id.trim().is_empty() {
            return Err("服务器未返回 uploadId，无法继续分片上传".to_string());
        }
        Ok(init)
    }

    /// `PUT /api/files/upload/{uploadId}/chunk?index=N`：上传单个分片（裸字节）。
    ///
    /// 按服务端约定：`X-Content-Sha256` = 该分片 SHA-256，签名 payloadHash 也用它。
    pub async fn put_chunk(&self, upload_id: &str, index: u32, bytes: &[u8]) -> Result<(), String> {
        let upload_id = upload_id.trim();
        if upload_id.is_empty() {
            return Err("缺少上传会话 id".to_string());
        }
        let chunk_sha256 = signer::sha256_hex(bytes);
        let path = format!("/api/files/upload/{upload_id}/chunk");
        let raw_query = format!("index={index}");
        let canonical_query = signer::canonical_query(&raw_query);
        let headers = self.auth_headers(
            "PUT",
            &path,
            &canonical_query,
            &chunk_sha256,
            &[(HEADER_CONTENT_SHA256, chunk_sha256.clone())],
        )?;
        let url = self.build_url(&path, &canonical_query)?;

        let response = self
            .http
            .put(url)
            .headers(headers)
            .header(CONTENT_TYPE, "application/octet-stream")
            .body(bytes.to_vec())
            .send()
            .await
            .map_err(network_error)?;
        let status = response.status();
        let body = response.bytes().await.map_err(network_error)?;
        parse_envelope::<Value>(&body, status).map(|_| ())
    }

    /// `GET /api/files/upload/{uploadId}`：查询已接收分片（续传用）。
    pub async fn chunk_status(&self, upload_id: &str) -> Result<ChunkStatus, String> {
        let upload_id = upload_id.trim();
        if upload_id.is_empty() {
            return Err("缺少上传会话 id".to_string());
        }
        self.send_json::<ChunkStatus>(
            Method::GET,
            &format!("/api/files/upload/{upload_id}"),
            None,
        )
        .await
    }

    /// `POST /api/files/upload/{uploadId}/complete`：合并分片并生成文件元数据。
    pub async fn complete_chunked_upload(&self, upload_id: &str) -> Result<FileMeta, String> {
        let upload_id = upload_id.trim();
        if upload_id.is_empty() {
            return Err("缺少上传会话 id".to_string());
        }
        self.send_json::<FileMeta>(
            Method::POST,
            &format!("/api/files/upload/{upload_id}/complete"),
            None,
        )
        .await
    }

    /// `DELETE /api/files/upload/{uploadId}`：取消分片上传会话。
    pub async fn cancel_chunked_upload(&self, upload_id: &str) -> Result<(), String> {
        let upload_id = upload_id.trim();
        if upload_id.is_empty() {
            return Ok(());
        }
        self.send_void(
            Method::DELETE,
            &format!("/api/files/upload/{upload_id}"),
        )
        .await
    }

    // ------------------------------------------------------------------
    // 单次 multipart 上传（小文件）
    // ------------------------------------------------------------------

    /// `POST /api/files/upload`：两遍读取文件（先算 SHA-256，再流式上传），
    /// 上传过程通过 `on_progress(sent, total)` 回报进度。
    ///
    /// `dir` 为相对存储根目录的目标目录（FTP 式目录上传），`None` / 空串表示根目录。
    pub async fn upload_file_multipart<F>(
        &self,
        path: &str,
        description: Option<&str>,
        dir: Option<&str>,
        root: Option<&str>,
        on_progress: F,
    ) -> Result<FileMeta, String>
    where
        F: Fn(u64, u64) + Send + Sync + 'static,
    {
        self.require_configured()?;

        let source: PathBuf = PathBuf::from(path);
        let metadata = tokio::fs::metadata(&source)
            .await
            .map_err(|e| format!("无法读取所选文件：{e}"))?;
        if !metadata.is_file() {
            return Err("所选路径不是文件".to_string());
        }
        let total = metadata.len();
        if total == 0 {
            return Err("不能上传空文件".to_string());
        }

        let file_name = source
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .filter(|name| !name.is_empty())
            .unwrap_or_else(|| "file".to_string());

        let description = description.map(str::trim).filter(|value| !value.is_empty());
        if let Some(description) = description {
            if description.chars().count() > 200 {
                return Err("说明不能超过 200 个字符".to_string());
            }
        }

        // 第一遍：流式计算文件 SHA-256（不读入内存）。
        let content_sha256 = sha256_file(&source).await?;

        // 第二遍：流式上传，边读边发进度事件。
        let file = tokio::fs::File::open(&source)
            .await
            .map_err(|e| format!("无法打开所选文件：{e}"))?;
        let sent = Arc::new(AtomicU64::new(0));
        let progress = Arc::new(on_progress);
        let progress_for_stream = Arc::clone(&progress);
        let stream = ReaderStream::new(file).map(move |chunk| match chunk {
            Ok(chunk) => {
                let sent_now =
                    sent.fetch_add(chunk.len() as u64, Ordering::Relaxed) + chunk.len() as u64;
                progress_for_stream(sent_now, total);
                Ok(chunk)
            }
            Err(err) => Err(err),
        });

        let file_part =
            Part::stream_with_length(Body::wrap_stream(stream), total).file_name(file_name.clone());
        let mut form = Form::new()
            .text("name", file_name.clone())
            .part("file", file_part);
        if let Some(description) = description {
            form = form.text("description", description.to_string());
        }
        // FTP 式目录上传：dir 为相对存储根目录的路径，空/缺省表示根目录。
        if let Some(dir) = dir.map(str::trim).filter(|value| !value.is_empty()) {
            form = form.text("dir", dir.to_string());
        }
        // 多存储根：root 为存储根 id，空/缺省表示服务端默认根（兼容单根旧服务端）。
        if let Some(root) = normalize_root(root) {
            form = form.text("root", root);
        }

        let headers = self.auth_headers(
            "POST",
            "/api/files/upload",
            "",
            &content_sha256,
            &[(HEADER_CONTENT_SHA256, content_sha256.clone())],
        )?;
        let url = format!("{}/api/files/upload", self.base_url()?);

        let response = self
            .http
            .post(url)
            .headers(headers)
            .multipart(form)
            .send()
            .await
            .map_err(network_error)?;
        let status = response.status();
        let bytes = response.bytes().await.map_err(network_error)?;

        // 保证前端进度条最终到 100%。
        progress(total, total);
        parse_envelope::<FileMeta>(&bytes, status)
    }

    // ------------------------------------------------------------------
    // 下载 / 删除
    // ------------------------------------------------------------------

    /// `GET /api/files/{id}/download`：流式下载并校验 SHA-256（契约 3.6 / 5.4）。
    pub async fn download_file(&self, id: &str, target_path: &str) -> Result<String, String> {
        let id = id.trim();
        if id.is_empty() {
            return Err("文件 ID 不能为空".to_string());
        }
        let target = format!("/api/files/{id}/download");
        self.download_target(&target, target_path).await
    }

    /// `GET /api/files/download?path=`：按相对路径下载（FTP 式目录浏览），
    /// 复用与按 id 下载相同的流式写入 + `X-Content-Sha256` 校验逻辑。
    pub async fn download_server_file(
        &self,
        path: &str,
        target_path: &str,
        root: Option<&str>,
    ) -> Result<String, String> {
        let path = path.trim();
        if path.is_empty() {
            return Err("文件路径不能为空".to_string());
        }
        let mut query = String::new();
        push_query(&mut query, "path", path);
        if let Some(root) = normalize_root(root) {
            push_query(&mut query, "root", &root);
        }
        let target = with_query("/api/files/download", &query);
        self.download_target(&target, target_path).await
    }

    /// 用系统默认应用打开刚下载到临时目录的文件（task-26）。
    ///
    /// 下载复用 `download_server_file`，不弹保存对话框；打开失败返回中文错误。
    pub async fn open_server_file(
        &self,
        path: &str,
        root: Option<&str>,
    ) -> Result<String, String> {
        let path = path.trim();
        if path.is_empty() {
            return Err("文件路径不能为空".to_string());
        }
        let temp_path = open_temp_path(path)?;
        let saved = self
            .download_server_file(path, temp_path.to_string_lossy().as_ref(), root)
            .await?;
        open_with_default_app(Path::new(&saved))?;
        Ok(saved)
    }

    /// 带签名的流式下载公共实现：`target` 可包含查询串（会参与 canonicalQuery 签名）。
    async fn download_target(&self, target: &str, target_path: &str) -> Result<String, String> {
        self.require_configured()?;
        let target_file = PathBuf::from(target_path);
        if target_file.as_os_str().is_empty() {
            return Err("请选择保存位置".to_string());
        }

        let (path, raw_query) = signer::split_path_and_query(target);
        let canonical_query = signer::canonical_query(raw_query);
        let headers = self.auth_headers("GET", path, &canonical_query, EMPTY_SHA256, &[])?;
        let url = self.build_url(path, &canonical_query)?;

        let response = self
            .http
            .get(url)
            .headers(headers)
            .send()
            .await
            .map_err(network_error)?;
        let status = response.status();

        if !status.is_success() {
            let bytes = response.bytes().await.map_err(network_error)?;
            return Err(parse_envelope::<Value>(&bytes, status)
                .err()
                .unwrap_or_else(|| status_error(status)));
        }

        let expected = response
            .headers()
            .get(HEADER_CONTENT_SHA256)
            .and_then(|value| value.to_str().ok())
            .map(|value| value.trim().to_ascii_lowercase())
            .filter(|value| !value.is_empty());

        match save_stream_to_file(response, &target_file, expected.as_deref()).await {
            Ok(saved) => Ok(saved),
            Err(err) => {
                let _ = tokio::fs::remove_file(&target_file).await;
                Err(err)
            }
        }
    }

    /// `DELETE /api/files/{id}`：删除文件（契约 3.7）。
    pub async fn delete_file(&self, id: &str) -> Result<(), String> {
        self.require_configured()?;
        let path = format!("/api/files/{}", id.trim());
        self.send_void(Method::DELETE, &path).await
    }

    /// `DELETE /api/files?path=`：按相对路径删除文件（FTP 式目录浏览），
    /// 服务端保证路径限制在存储根目录内。
    pub async fn delete_server_file(&self, path: &str, root: Option<&str>) -> Result<(), String> {
        self.require_configured()?;
        let path = path.trim();
        if path.is_empty() {
            return Err("文件路径不能为空".to_string());
        }
        let mut query = String::new();
        push_query(&mut query, "path", path);
        if let Some(root) = normalize_root(root) {
            push_query(&mut query, "root", &root);
        }
        let target = with_query("/api/files", &query);
        self.send_void(Method::DELETE, &target).await
    }

    /// `POST /api/files/rename`：重命名文件/目录（task-18）。
    ///
    /// `root` 缺省/空表示默认根；成功后返回 `{oldPath, newPath, type}`。
    /// 该操作不做自动重试（重试可能把「已成功但响应丢失」误报成旧路径不存在）。
    pub async fn rename_server_file(
        &self,
        path: &str,
        new_name: &str,
        root: Option<&str>,
    ) -> Result<RenameResult, String> {
        self.require_configured()?;
        let (target, body) = rename_request(path, new_name, root)?;
        self.send_json_once::<RenameResult>(Method::POST, target, Some(&body))
            .await
    }

    /// `POST /api/files/copy`：原地拷贝文件/目录（task-40）。
    ///
    /// 服务端负责生成 `xxx_副本` / `xxx_副本 (1)` 等新名称；错误 message 原样透传。
    pub async fn copy_server_path(
        &self,
        path: &str,
        root: Option<&str>,
    ) -> Result<CopyResult, String> {
        self.require_configured()?;
        let (target, body) = copy_request(path, root)?;
        self.send_json_once::<CopyResult>(Method::POST, target, Some(&body))
            .await
    }

    /// `POST /api/files/move`：移动文件/目录到目标根/目录（task-40）。
    ///
    /// `target_root=None` 表示同根；`target_dir=""` 表示目标根根目录。
    /// 不做自动重试（重试可能在响应丢失后重复移动/覆盖）。
    pub async fn move_server_path(
        &self,
        path: &str,
        root: Option<&str>,
        target_root: Option<&str>,
        target_dir: &str,
    ) -> Result<MoveResult, String> {
        self.require_configured()?;
        let (target, body) = move_request(path, root, target_root, target_dir)?;
        self.send_json_once::<MoveResult>(Method::POST, target, Some(&body))
            .await
    }

    /// `GET /api/files/content`：读取文本文件内容（task-23 在线编辑）。
    ///
    /// 目录 / 二进制 / 超过 2MiB 由服务端返回 40000；不存在 40400。
    pub async fn read_server_file_content(
        &self,
        path: &str,
        root: Option<&str>,
    ) -> Result<FileContent, String> {
        self.require_configured()?;
        let target = content_target(path, root)?;
        self.send_json::<FileContent>(Method::GET, &target, None).await
    }

    /// `PUT /api/files/content`：覆盖保存文本文件（task-23）。
    ///
    /// 不做自动重试：网络超时后重试可能因为 expectedSha256 已变化而被误报冲突。
    pub async fn save_server_file_content(
        &self,
        path: &str,
        content: &str,
        expected_sha256: Option<&str>,
        root: Option<&str>,
    ) -> Result<SavedContent, String> {
        self.require_configured()?;
        let (target, body) = save_content_request(path, content, expected_sha256, root)?;
        self.send_json_once::<SavedContent>(Method::PUT, target, Some(&body))
            .await
    }

    /// `POST /api/files/extract`：解压 zip 到同级同名目录（task-23）。
    ///
    /// 不做自动重试：重试可能在响应丢失后生成 ` (1)` 目录重复解压。
    pub async fn extract_zip_file(
        &self,
        path: &str,
        root: Option<&str>,
    ) -> Result<ExtractResult, String> {
        self.require_configured()?;
        let (target, body) = extract_zip_request(path, root)?;
        self.send_json_once::<ExtractResult>(Method::POST, target, Some(&body))
            .await
    }

    /// `POST /api/terminal/exec`：在服务器上执行单条命令（task-30）。
    ///
    /// 不做自动重试：终端命令可能产生副作用；网络失败交给用户手动重试。
    pub async fn terminal_exec(
        &self,
        session_id: &str,
        command: &str,
        cwd: Option<&str>,
        root: Option<&str>,
    ) -> Result<TerminalResult, String> {
        self.require_configured()?;
        let (target, body) = terminal_exec_request(session_id, command, cwd, root)?;
        self.send_json_once::<TerminalResult>(Method::POST, target, Some(&body))
            .await
    }
}

/// 流式读取文件并计算 SHA-256（小写 hex）。
pub async fn sha256_file(path: &Path) -> Result<String, String> {
    let mut file = tokio::fs::File::open(path)
        .await
        .map_err(|e| format!("无法打开所选文件：{e}"))?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .await
            .map_err(|e| format!("读取文件失败：{e}"))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// 把下载响应流式写入目标文件，并校验响应头里的 SHA-256。
async fn save_stream_to_file(
    response: reqwest::Response,
    target: &Path,
    expected_sha256: Option<&str>,
) -> Result<String, String> {
    if let Some(parent) = target.parent() {
        if !parent.as_os_str().is_empty() {
            tokio::fs::create_dir_all(parent)
                .await
                .map_err(|e| format!("无法创建保存目录：{e}"))?;
        }
    }

    let mut file = tokio::fs::File::create(target)
        .await
        .map_err(|e| format!("无法创建目标文件：{e}"))?;
    let mut hasher = Sha256::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(network_error)?;
        hasher.update(&chunk);
        file.write_all(&chunk)
            .await
            .map_err(|e| format!("写入文件失败：{e}"))?;
    }
    file.flush()
        .await
        .map_err(|e| format!("写入文件失败：{e}"))?;
    drop(file);

    let actual = hex::encode(hasher.finalize());
    if let Some(expected) = expected_sha256 {
        if actual != expected {
            return Err("文件内容校验失败：SHA-256 与响应头不一致".to_string());
        }
    }

    Ok(std::fs::canonicalize(target)
        .unwrap_or_else(|_| target.to_path_buf())
        .to_string_lossy()
        .to_string())
}

pub(crate) fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or_default()
}

fn parse_envelope<T: DeserializeOwned>(bytes: &[u8], status: StatusCode) -> Result<T, String> {
    if let Ok(envelope) = serde_json::from_slice::<Envelope<T>>(bytes) {
        if envelope.code == 0 {
            return envelope
                .data
                .ok_or_else(|| "服务器返回数据为空".to_string());
        }
        return Err(api_error(envelope.code, &envelope.message));
    }

    if status.is_success() {
        Err("服务器返回了无法解析的响应".to_string())
    } else {
        Err(status_error(status))
    }
}

/// 把契约第 4 节的错误码映射成前端可展示的中文提示（带上服务端 message 便于排查）。
fn api_error(code: i64, message: &str) -> String {
    let text = match code {
        40000 => "请求参数错误",
        40001 => "文件内容校验失败（SHA-256 不一致）",
        40003 => "文件超过大小限制",
        40100 => "签名无效，请检查 AppSecret",
        40101 => "请求已过期，请检查本机时间",
        40102 => "请求重复，请重试",
        40103 => "客户端未授权，请检查 AppKey",
        40300 => "设备未授权，请重新激活",
        40400 => "资源不存在",
        42900 => "请求过于频繁，请稍后重试",
        50000 => "服务器内部错误",
        _ => {
            if message.trim().is_empty() {
                return format!("请求失败（code={code}）");
            }
            return format!("请求失败：{}（code={code}）", message.trim());
        }
    };
    let detail = message.trim();
    if detail.is_empty() || detail == "ok" || detail == text {
        text.to_string()
    } else {
        format!("{text}：{detail}")
    }
}

/// HTTP 状态兜底（响应不是 JSON 信封时）。
fn status_error(status: StatusCode) -> String {
    let text = match status.as_u16() {
        400 => "请求参数错误",
        401 => "认证失败，请检查 AppKey 与 AppSecret",
        403 => "设备未授权，请重新激活",
        404 => "资源不存在",
        413 => "文件超过大小限制",
        415 => "不支持的文件类型",
        429 => "请求过于频繁，请稍后重试",
        500..=599 => "服务器内部错误",
        _ => return format!("请求失败（HTTP {}）", status.as_u16()),
    };
    text.to_string()
}

/// 网络层错误 → 中文提示。
///
/// `reqwest` 的原始错误（如 `error sending request for url ...`）对用户没有可操作性；
/// 这里保留目标地址，并针对本地地址提示最常见的「端口被其他程序占用」。
fn network_error(error: reqwest::Error) -> String {
    let target = error
        .url()
        .map(|url| format!("（{url}）"))
        .unwrap_or_default();
    let is_local = error
        .url()
        .and_then(|url| url.host_str())
        .map(|host| matches!(host, "127.0.0.1" | "localhost" | "::1"))
        .unwrap_or(false);
    if error.is_timeout() {
        format!("网络错误：请求超时{target}，请检查服务器地址与网络")
    } else if error.is_connect() {
        format!("网络错误：无法连接到服务器{target}，请确认服务端已启动且地址/端口正确")
    } else if error.is_body() || error.is_decode() {
        format!("网络错误：响应数据读取失败{target}（{error}）")
    } else if is_local {
        format!(
            "网络错误：本地服务连接失败{target}，请确认服务端已启动，并检查端口是否被其他程序占用（lsof -nP -iTCP:端口 -sTCP:LISTEN）"
        )
    } else {
        format!("网络错误：请求失败{target}（{error}）")
    }
}

/// 错误是否值得重试（网络抖动 / 超时 / 限流 / 5xx）。
pub fn is_retryable_error(message: &str) -> bool {
    message.contains("网络错误")
        || message.contains("请求超时")
        || message.contains("请求过于频繁")
        || message.contains("42900")
        || message.contains("服务器内部错误")
        || message.contains("50000")
        || message.contains("HTTP 5")
}

/// 上传会话不存在（续传时重新 init）。
pub fn is_session_not_found_error(message: &str) -> bool {
    message.contains("40400") || message.contains("资源不存在")
}

/// 文件内容 SHA-256 校验失败（服务端已清理会话）。
pub fn is_content_mismatch_error(message: &str) -> bool {
    message.contains("40001") || message.contains("内容校验失败")
}

/// 文件超过服务端大小限制。
pub fn is_too_large_error(message: &str) -> bool {
    message.contains("40003") || message.contains("超过大小限制")
}

// ============================================================================
// 真实服务端联调测试（默认跳过，设置 LIVE_E2E_BASE_URL 等环境变量后执行）：
//   LIVE_E2E_BASE_URL=http://127.0.0.1:8080 \
//   LIVE_E2E_DIR=/path/to/.cache/live-e2e \
//   cargo test live_client_server_roundtrip -- --nocapture
// 目的：验证 Rust 侧签名（使用客户端内置 AppKey/AppSecret）、multipart 上传、
//       分片上传（含断点续传）、搜索、分页列表、目录浏览、流式下载校验、删除与
//       真实 Spring Boot 服务端完全兼容（不是 mock）。
// ============================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn root_query_helpers_normalize_and_encode() {
        assert_eq!(normalize_root(None), None);
        assert_eq!(normalize_root(Some("   ")), None);
        assert_eq!(normalize_root(Some(" work ")), Some("work".to_string()));

        let mut query = String::new();
        push_query(&mut query, "path", "a b/c");
        push_query(&mut query, "root", "work");
        assert_eq!(
            query,
            format!(
                "path={}&root=work",
                signer::encode_query_component("a b/c")
            )
        );
        assert_eq!(with_query("/api/files/dir", ""), "/api/files/dir");
        assert_eq!(
            with_query("/api/files/dir", &query),
            format!("/api/files/dir?{query}")
        );
    }

    #[test]
    fn storage_roots_effective_default() {
        let roots = StorageRoots {
            default_root_id: " work ".to_string(),
            roots: vec![StorageRoot {
                id: "media".to_string(),
                name: "媒体库".to_string(),
                path: "/tmp/media".to_string(),
            }],
        };
        assert_eq!(roots.effective_default_root_id(), "work");

        let fallback = StorageRoots {
            default_root_id: String::new(),
            roots: vec![
                StorageRoot {
                    id: "work".to_string(),
                    name: "工作资料".to_string(),
                    path: "/tmp/work".to_string(),
                },
                StorageRoot {
                    id: "media".to_string(),
                    name: "媒体库".to_string(),
                    path: "/tmp/media".to_string(),
                },
            ],
        };
        assert_eq!(fallback.effective_default_root_id(), "work");

        assert_eq!(
            StorageRoots {
                default_root_id: String::new(),
                roots: Vec::new(),
            }
            .effective_default_root_id(),
            ""
        );
    }

    #[test]
    fn rename_request_body_carries_path_new_name_and_root() {
        let (target, body) = rename_request("docs/a.txt", "  b.txt  ", Some("media"))
            .expect("构造 rename 请求");
        assert_eq!(target, "/api/files/rename");
        let json: Value = serde_json::from_slice(&body).expect("rename 请求体是 JSON");
        assert_eq!(json["path"], "docs/a.txt");
        assert_eq!(json["newName"], "b.txt", "名称应 trim 后作为单段名提交");
        assert_eq!(json["root"], "media", "root 放在 JSON 体里");
        // root 参与 payloadHash 签名（body hash 随 root 变化），而不是 canonicalQuery。
        let with_root_hash = signer::sha256_hex(&body);
        assert_ne!(with_root_hash, signer::EMPTY_SHA256);
        let (_, body_without_root) = rename_request("docs/a.txt", "b.txt", None).unwrap();
        assert_ne!(
            with_root_hash,
            signer::sha256_hex(&body_without_root),
            "带 root 与不带 root 的请求体 hash 应不同（签名覆盖 root）"
        );
        let (path, raw_query) = signer::split_path_and_query(target);
        assert_eq!(path, "/api/files/rename");
        assert_eq!(signer::canonical_query(raw_query), "");
    }

    #[test]
    fn rename_request_omits_root_for_default_and_validates_name() {
        let (target, body) = rename_request("a.txt", "b.txt", None).expect("默认根 rename");
        assert_eq!(target, "/api/files/rename");
        let json: Value = serde_json::from_slice(&body).unwrap();
        assert!(json.get("root").is_none(), "默认根不应携带 root（旧服务端兼容）");
        // 空 root 与 None 等价
        let (_, empty_root_body) = rename_request("a.txt", "b.txt", Some("  ")).unwrap();
        let empty_json: Value = serde_json::from_slice(&empty_root_body).unwrap();
        assert!(empty_json.get("root").is_none());

        for bad in ["", "   ", ".", "..", "a/b", "a\\b"] {
            assert!(
                rename_request("a.txt", bad, None).is_err(),
                "非法名应被拒绝: {bad:?}"
            );
        }
        assert!(rename_request("", "b.txt", None).is_err(), "空路径应被拒绝");
    }

    #[test]
    fn copy_request_builds_signed_json_body() {
        let (target, body) = copy_request("docs/说明.txt", Some("work")).expect("拷贝请求");
        assert_eq!(target, "/api/files/copy");
        let json: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(json["path"], "docs/说明.txt");
        assert_eq!(json["root"], "work");

        let (_, default_body) = copy_request("docs/说明.txt", None).unwrap();
        let default_json: Value = serde_json::from_slice(&default_body).unwrap();
        assert!(default_json.get("root").is_none(), "默认根不应携带 root");
        assert!(copy_request("", Some("work")).is_err(), "空路径应被拒绝");
        assert!(copy_request("   ", None).is_err(), "空白路径应被拒绝");
    }

    #[test]
    fn move_request_carries_target_root_and_normalizes_dir() {
        let (target, body) =
            move_request("docs/说明.txt", Some("work"), Some("media"), "/照片/").expect("移动请求");
        assert_eq!(target, "/api/files/move");
        let json: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(json["path"], "docs/说明.txt");
        assert_eq!(json["root"], "work");
        assert_eq!(json["targetRoot"], "media");
        assert_eq!(json["targetDir"], "照片", "targetDir 应去掉首尾斜杠");

        let (_, same_root_body) = move_request("a.txt", Some("work"), None, "").unwrap();
        let same_root_json: Value = serde_json::from_slice(&same_root_body).unwrap();
        assert!(same_root_json.get("targetRoot").is_none(), "同根应省略 targetRoot");
        assert_eq!(same_root_json["targetDir"], "", "根目录目标为空串");
        assert_eq!(same_root_json["root"], "work");
        assert!(move_request("", None, None, "").is_err(), "空路径应被拒绝");
    }

    #[test]
    fn copy_and_move_result_parse_contract_shape() {
        let copied: CopyResult = serde_json::from_str(
            r#"{"root":"work","path":"docs/说明_副本.txt","type":"file","name":"说明_副本.txt"}"#,
        )
        .expect("解析 copy 响应");
        assert_eq!(copied.root, "work");
        assert_eq!(copied.path, "docs/说明_副本.txt");
        assert_eq!(copied.entry_type, "file");
        assert_eq!(copied.name, "说明_副本.txt");

        let moved: MoveResult = serde_json::from_str(
            r#"{"root":"media","path":"照片/说明.txt","type":"file","name":"说明.txt"}"#,
        )
        .expect("解析 move 响应");
        assert_eq!(moved.root, "media");
        assert_eq!(moved.path, "照片/说明.txt");
        assert_eq!(moved.entry_type, "file");
        assert_eq!(moved.name, "说明.txt");
    }

    #[test]
    fn rename_result_parses_contract_shape() {
        let result: RenameResult = serde_json::from_str(
            r#"{"oldPath":"docs/a.txt","newPath":"docs/b.txt","type":"file"}"#,
        )
        .expect("解析 rename 响应");
        assert_eq!(result.old_path, "docs/a.txt");
        assert_eq!(result.new_path, "docs/b.txt");
        assert_eq!(result.entry_type, "file");
        assert!(result.root_id.is_none());

        let dir_result: RenameResult = serde_json::from_str(
            r#"{"oldPath":"old","newPath":"new","type":"directory","rootId":"media"}"#,
        )
        .unwrap();
        assert_eq!(dir_result.entry_type, "directory");
        assert_eq!(dir_result.root_id.as_deref(), Some("media"));
    }

    #[test]
    fn content_query_and_save_request_carry_root_and_expected_sha() {
        let target = content_target("docs/笔记.txt", Some("media")).expect("读取请求");
        assert!(target.starts_with("/api/files/content?"));
        assert!(target.contains("path=docs%2F"));

        let (save_target, body) =
            save_content_request("docs/笔记.txt", "新内容\n", Some("abc123"), Some("media"))
                .expect("保存请求");
        assert_eq!(save_target, "/api/files/content");
        let json: Value = serde_json::from_slice(&body).expect("保存请求体是 JSON");
        assert_eq!(json["path"], "docs/笔记.txt");
        assert_eq!(json["content"], "新内容\n");
        assert_eq!(json["expectedSha256"], "abc123");
        assert_eq!(json["root"], "media");
        let (_, without_root) =
            save_content_request("docs/笔记.txt", "新内容\n", Some("abc123"), None).unwrap();
        assert_ne!(
            signer::sha256_hex(&body),
            signer::sha256_hex(&without_root),
            "root 进入 body 后应改变 payloadHash"
        );
        let (_, no_expected) =
            save_content_request("a.txt", "", None, None).expect("允许空内容与空 expected");
        let no_expected_json: Value = serde_json::from_slice(&no_expected).unwrap();
        assert!(no_expected_json.get("expectedSha256").is_none());
        assert!(no_expected_json.get("root").is_none());
        assert!(save_content_request("", "x", None, None).is_err(), "空路径应被拒绝");
    }

    #[test]
    fn extract_request_validates_zip_and_carries_root() {
        let (target, body) = extract_zip_request("docs/包.ZIP", Some("work")).expect("解压请求");
        assert_eq!(target, "/api/files/extract");
        let json: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(json["path"], "docs/包.ZIP");
        assert_eq!(json["root"], "work");
        let (_, default_body) = extract_zip_request("docs/包.zip", None).unwrap();
        let default_json: Value = serde_json::from_slice(&default_body).unwrap();
        assert!(default_json.get("root").is_none());
        for bad in ["", "   ", "docs/包.rar", "docs/包.tar.gz"] {
            assert!(extract_zip_request(bad, None).is_err(), "非 zip 应被拒绝: {bad:?}");
        }
    }

    #[test]
    fn terminal_exec_request_validates_and_builds_json() {
        let (target, body) =
            terminal_exec_request("session-12345678", "ls -la", Some("/tmp"), Some(" work "))
                .expect("合法参数应构造请求");
        assert_eq!(target, "/api/terminal/exec");
        let payload: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(payload["sessionId"], "session-12345678");
        assert_eq!(payload["command"], "ls -la");
        assert_eq!(payload["cwd"], "/tmp");
        assert_eq!(payload["root"], "work");

        let (_target, body) =
            terminal_exec_request("session-12345678", "pwd", None, None).unwrap();
        let payload: Value = serde_json::from_slice(&body).unwrap();
        assert!(payload.get("cwd").is_none());
        assert!(payload.get("root").is_none());

        assert!(terminal_exec_request("short", "ls", None, None)
            .unwrap_err()
            .contains("8-64"));
        assert!(terminal_exec_request("bad session id", "ls", None, None)
            .unwrap_err()
            .contains("只能包含"));
        assert!(terminal_exec_request("session-12345678", "  ", None, None)
            .unwrap_err()
            .contains("不能为空"));
        assert!(
            terminal_exec_request("session-12345678", "ls\nrm -rf /", None, None)
                .unwrap_err()
                .contains("单行")
        );
        assert!(terminal_exec_request(
            "session-12345678",
            &"x".repeat(4097),
            None,
            None
        )
        .unwrap_err()
        .contains("过长"));
    }

    #[test]
    fn terminal_result_parses_camel_case_contract() {
        let raw = json!({
            "sessionId": "session-12345678",
            "command": "pwd",
            "output": "/tmp\n",
            "exitCode": 0,
            "cwd": "/tmp",
            "truncated": false,
            "durationMs": 12,
        });
        let result: TerminalResult = serde_json::from_value(raw).expect("契约字段应可解析");
        assert_eq!(result.session_id, "session-12345678");
        assert_eq!(result.exit_code, 0);
        assert_eq!(result.cwd, "/tmp");
        assert!(!result.truncated);
        assert_eq!(result.duration_ms, 12);
    }

    #[test]
    fn open_temp_file_name_sanitizes_unsafe_paths() {
        assert_eq!(open_temp_file_name("docs/报告.pdf"), "报告.pdf");
        assert_eq!(open_temp_file_name("a/b\\c.txt"), "c.txt");
        assert_eq!(open_temp_file_name("../evil name.txt"), "evil name.txt");
        assert_eq!(open_temp_file_name("docs/.."), "download");
        assert_eq!(open_temp_file_name(""), "download");
        assert_eq!(open_temp_file_name("   "), "download");
        let sanitized = open_temp_file_name("bad:name*?.bin");
        assert!(!sanitized.contains(':') && !sanitized.contains('*') && !sanitized.contains('?'));
        let long = format!("{}.txt", "长".repeat(200));
        assert!(open_temp_file_name(&long).chars().count() <= OPEN_TEMP_FILE_MAX_CHARS);
        assert!(open_temp_dir().ends_with("scop-ftp-open"));
        assert!(open_temp_path("   ").is_err());
    }

    #[test]
    fn open_temp_path_creates_sanitized_file() {
        let path = open_temp_path("docs/报告.pdf").expect("临时路径");
        assert_eq!(path.file_name().unwrap(), "报告.pdf");
        assert_eq!(path.parent().unwrap(), open_temp_dir());
        assert!(path.parent().unwrap().is_dir());
        assert!(open_temp_path("   ").is_err());
    }

    #[test]
    fn open_application_parses_known_values() {
        for value in ["default", "vscode", "textedit", "notepad", "choose"] {
            assert!(OpenApplication::parse(value).is_ok(), "{value} 应解析成功");
        }
        assert_eq!(OpenApplication::parse("default").unwrap(), OpenApplication::Default);
        assert_eq!(OpenApplication::parse("vscode").unwrap(), OpenApplication::VsCode);
        let error = OpenApplication::parse("photoshop").unwrap_err();
        assert!(error.contains("不支持的打开方式"), "{error}");
    }

    #[test]
    fn open_command_parts_targets_downloaded_file() {
        let path = PathBuf::from("/tmp/scop-ftp-open/报告.pdf");
        let (program, args) = open_command_parts(&path, OpenApplication::Default).unwrap();
        assert!(!program.is_empty(), "打开命令不能为空");
        assert!(
            args.iter().any(|arg| arg.contains("报告.pdf")),
            "打开命令参数应包含目标文件: {args:?}"
        );
        let (vscode_program, vscode_args) =
            open_command_parts(&path, OpenApplication::VsCode).unwrap();
        assert!(!vscode_program.is_empty());
        assert!(vscode_args.iter().any(|arg| arg.contains("报告.pdf")));
        // 选择其他应用由系统选择器负责，也必须带上目标文件。
        #[cfg(not(target_os = "linux"))]
        {
            let (choose_program, choose_args) =
                open_command_parts(&path, OpenApplication::Choose).unwrap();
            assert!(!choose_program.is_empty());
            assert!(choose_args.iter().any(|arg| arg.contains("报告.pdf")));
        }
    }

    #[test]
    fn retryable_errors_are_classified() {
        for message in [
            "网络错误：请求超时，请检查服务器地址与网络",
            "网络错误：无法连接到服务器，请检查地址与端口",
            "请求过于频繁，请稍后重试",
        ] {
            assert!(is_retryable_error(message), "{message} 应可重试");
        }
        assert!(is_retryable_error("请求失败：服务器内部错误（code=50000）"));
        assert!(!is_retryable_error("资源不存在"));
        assert!(!is_retryable_error("签名无效，请检查 AppSecret"));
    }

    #[test]
    fn session_content_and_size_errors_are_classified() {
        assert!(is_session_not_found_error("资源不存在：上传会话不存在"));
        assert!(is_session_not_found_error("code=40400"));
        assert!(!is_session_not_found_error("网络错误"));
        assert!(is_content_mismatch_error("文件内容校验失败（SHA-256 不一致）"));
        assert!(is_content_mismatch_error("40001"));
        assert!(!is_content_mismatch_error("文件超过大小限制"));
        assert!(is_too_large_error("文件超过大小限制"));
        assert!(is_too_large_error("40003"));
        assert!(!is_too_large_error("40001"));
    }

    #[test]
    fn error_envelope_maps_known_codes() {
        let ok: Value = parse_envelope(
            r#"{"code":0,"message":"ok","data":{"name":"a.txt"}}"#.as_bytes(),
            StatusCode::OK,
        )
        .expect("成功信封");
        assert_eq!(ok["name"], "a.txt");

        let error = parse_envelope::<Value>(
            r#"{"code":40001,"message":"SHA-256 不一致","data":null}"#.as_bytes(),
            StatusCode::OK,
        )
        .unwrap_err();
        assert!(error.contains("文件内容校验失败"), "{error}");
        assert!(error.contains("SHA-256 不一致"), "{error}");

        let unknown = parse_envelope::<Value>(
            r#"{"code":41234,"message":"自定义错误","data":null}"#.as_bytes(),
            StatusCode::OK,
        )
        .unwrap_err();
        assert!(unknown.contains("自定义错误") && unknown.contains("41234"));
    }

    #[test]
    fn error_envelope_falls_back_to_http_status() {
        assert_eq!(
            parse_envelope::<Value>(b"not-json", StatusCode::OK).unwrap_err(),
            "服务器返回了无法解析的响应"
        );
        assert_eq!(
            parse_envelope::<Value>(b"bad", StatusCode::UNAUTHORIZED).unwrap_err(),
            "认证失败，请检查 AppKey 与 AppSecret"
        );
        assert_eq!(
            parse_envelope::<Value>(b"bad", StatusCode::BAD_REQUEST).unwrap_err(),
            "请求参数错误"
        );
        assert_eq!(
            parse_envelope::<Value>(r#"{"code":0,"message":"ok","data":null}"#.as_bytes(), StatusCode::OK)
                .unwrap_err(),
            "服务器返回数据为空"
        );
    }

    /// 测试辅助：构造指向 live 服务端的 ServerProfile。
    fn make_server(base_url: &str) -> ServerProfile {
        let mut server = ServerProfile::new("live", base_url, "live-test-device");
        server.server_url = base_url.to_string();
        server
    }

    #[test]
    fn request_url_keeps_reverse_proxy_prefix() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let api = Api::new(
            reqwest::Client::new(),
            make_server("https://example.com/scopeftp/"),
        );
        assert_eq!(
            api.build_url("/api/health", "").unwrap(),
            "https://example.com/scopeftp/api/health"
        );
        assert_eq!(
            api.build_url("/api/files", "page=1&size=50").unwrap(),
            "https://example.com/scopeftp/api/files?page=1&size=50"
        );
    }

    #[test]
    fn audit_logs_target_encodes_query_and_omits_empty_options() {
        let base = audit_logs_target(1, 50, None, None, None, None, None, None).unwrap();
        assert_eq!(base, "/api/audit/logs?page=1&size=50");

        let full = audit_logs_target(
            2,
            100,
            Some("content.save"),
            Some("operation"),
            Some(false),
            Some("docs/说明 文件"),
            Some("2026-10-30T00:00:00+08:00"),
            Some("2026-10-31T00:00:00Z"),
        )
        .unwrap();
        assert_eq!(
            full,
            concat!(
                "/api/audit/logs?page=2&size=100&action=content.save&category=operation&success=false",
                "&keyword=docs%2F%E8%AF%B4%E6%98%8E%20%E6%96%87%E4%BB%B6",
                "&from=2026-10-30T00%3A00%3A00%2B08%3A00&to=2026-10-31T00%3A00%3A00Z"
            )
        );

        // 页码至少 1、size 限制 1..200；空白可选参数应省略。
        let clamped = audit_logs_target(0, 500, Some("  "), None, None, Some("  "), Some(""), None)
            .unwrap();
        assert_eq!(clamped, "/api/audit/logs?page=1&size=200");
        let success_true = audit_logs_target(1, 20, None, None, Some(true), None, None, None).unwrap();
        assert!(success_true.ends_with("&success=true"));
    }

    #[test]
    fn audit_log_page_parses_contract_shape() {
        let page: AuditLogPage = serde_json::from_str(
            r#"{"items":[
                {"ts":"2026-10-30T09:30:00+08:00","requestId":"req-1","appKey":"demo-app",
                 "clientName":"我的设备","deviceId":"dev-1","clientIp":"127.0.0.1",
                 "method":"POST","path":"/api/files","query":"root=work","action":"upload",
                 "root":"work","targetRoot":null,"pathParam":null,"targetDir":null,
                 "sessionId":null,"terminalCommand":null,"body":"{\"path\":\"a.txt\"}",
                 "contentSha256":"abc","requestBytes":12,"responseBytes":34,
                 "durationMs":5,"httpStatus":200,"success":true,"error":null,
                 "userAgent":"ScopeFTP-client/0.1.0","fileName":"a.txt"}
            ],"total":1,"page":2,"size":50,"hasMore":true}"#,
        )
        .expect("解析 audit 响应");
        assert_eq!(page.total, 1);
        assert_eq!(page.page, 2);
        assert_eq!(page.size, 50);
        assert!(page.has_more);
        let item = &page.items[0];
        assert_eq!(item.ts, "2026-10-30T09:30:00+08:00");
        assert_eq!(item.request_id, "req-1");
        assert_eq!(item.action.as_deref(), Some("upload"));
        assert_eq!(item.body.as_deref(), Some(r#"{"path":"a.txt"}"#));
        assert_eq!(item.request_bytes, Some(12));
        assert!(item.success);
        assert!(item.target_root.is_none());
    }

    /// 真机操作日志联调（task-45）：设置 `LIVE_AUDIT_BASE_URL`（如 http://127.0.0.1:18115）。
    ///
    /// 先制造上传成功、删除成功、删除失败（404）和终端命令 4 条操作，再验证分页、
    /// action/success/keyword/from-to 筛选、requestId 检索与详情字段。
    #[test]
    #[ignore = "需要真实服务端：设置 LIVE_AUDIT_BASE_URL"]
    fn live_audit_logs_roundtrip() {
        let base_url = std::env::var("LIVE_AUDIT_BASE_URL").unwrap_or_default();
        if base_url.trim().is_empty() {
            eprintln!("跳过操作日志 live 联调：未设置 LIVE_AUDIT_BASE_URL");
            return;
        }
        let _ = rustls::crypto::ring::default_provider().install_default();
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .build()
            .expect("创建 HTTP 客户端");
        let api = Api::new(http, make_server(&base_url));
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("创建 tokio runtime");

        runtime.block_on(async {
            let unique = format!(
                "audit-live-{}-{}.txt",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_millis()
            );
            let local_path = std::env::temp_dir().join(&unique);
            std::fs::write(&local_path, b"audit live payload\n").expect("写本地测试文件");
            let uploaded = api
                .upload_file_multipart(
                    local_path.to_str().unwrap(),
                    Some("操作日志联调"),
                    None,
                    None,
                    |_, _| {},
                )
                .await
                .expect("上传测试文件");
            println!("[live audit upload] {uploaded:?}");
            assert_eq!(uploaded.name, unique);

            let command = format!("echo {unique}");
            let terminal = api
                .terminal_exec("audit-live-session", &command, None, None)
                .await
                .expect("执行终端命令");
            println!("[live audit terminal] {terminal:?}");

            api.delete_server_file(&unique, None)
                .await
                .expect("删除测试文件");
            let delete_error = api
                .delete_server_file(&unique, None)
                .await
                .expect_err("再次删除应 404");
            println!("[live audit delete error] {delete_error}");
            assert!(
                delete_error.contains("资源不存在") || delete_error.contains("404"),
                "删除失败应返回不存在类错误: {delete_error}"
            );

            // 审计写入为异步队列，轮询等待本轮的 4 条记录落库。
            let mut page = api
                .list_audit_logs(1, 50, None, None, None, None, None, None)
                .await
                .expect("查询操作日志");
            for _ in 0..30 {
                let has_upload = page
                    .items
                    .iter()
                    .any(|item| item.action.as_deref() == Some("upload") && item.file_name.as_deref() == Some(unique.as_str()));
                let has_terminal = page.items.iter().any(|item| {
                    item.action.as_deref() == Some("terminal.exec")
                        && item.terminal_command.as_deref() == Some(command.as_str())
                });
                let has_failure = page
                    .items
                    .iter()
                    .any(|item| !item.success && item.error.as_deref().is_some());
                if has_upload && has_terminal && has_failure {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(150)).await;
                page = api
                    .list_audit_logs(1, 50, None, None, None, None, None, None)
                    .await
                    .expect("轮询操作日志");
            }
            println!("[live audit page] total={} items={}", page.total, page.items.len());
            assert!(page.total >= 4, "应至少有上传/删除/失败/终端 4 条日志");
            // multipart 上传不在 body 中，审计只记录 action/大小，fileName 可能为空；
            // 删除请求的 pathParam 才是文件名。
            assert!(page
                .items
                .iter()
                .any(|item| item.action.as_deref() == Some("upload") && item.success));
            assert!(page.items.iter().any(|item| {
                item.action.as_deref() == Some("terminal.exec")
                    && item.terminal_command.as_deref() == Some(command.as_str())
            }));
            let request_id = page
                .items
                .iter()
                .find(|item| item.action.as_deref() == Some("upload"))
                .map(|item| item.request_id.clone())
                .expect("上传日志 requestId");
            assert!(!request_id.trim().is_empty());

            if let Ok(sample_path) = std::env::var("LIVE_AUDIT_SAMPLE_PATH") {
                if !sample_path.trim().is_empty() {
                    let json = serde_json::to_string_pretty(&page).expect("序列化日志样例");
                    std::fs::write(&sample_path, json).expect("写日志样例");
                }
            }

            // action 精确筛选
            let uploads = api
                .list_audit_logs(1, 10, Some("upload"), None, Some(true), None, None, None)
                .await
                .expect("筛选 upload");
            assert!(uploads.total >= 1);
            assert!(uploads
                .items
                .iter()
                .all(|item| item.action.as_deref() == Some("upload") && item.success));

            // terminal.exec + 详情字段
            let terminals = api
                .list_audit_logs(1, 10, Some("terminal.exec"), None, None, None, None, None)
                .await
                .expect("筛选 terminal.exec");
            let terminal_item = terminals
                .items
                .iter()
                .find(|item| item.terminal_command.as_deref() == Some(command.as_str()))
                .expect("终端命令日志");
            assert!(terminal_item.session_id.as_deref() == Some("audit-live-session"));
            assert!(terminal_item.client_ip.is_some());
            assert!(terminal_item.duration_ms.unwrap_or(-1) >= 0);

            // success=false 失败请求
            let failures = api
                .list_audit_logs(1, 10, None, None, Some(false), None, None, None)
                .await
                .expect("筛选失败");
            assert!(failures.total >= 1);
            assert!(failures.items.iter().all(|item| !item.success));
            assert!(failures
                .items
                .iter()
                .any(|item| item.error.is_some()));

            // keyword 命中文件名（上传+删除）
            let keywords = api
                .list_audit_logs(1, 20, None, None, None, Some(&unique), None, None)
                .await
                .expect("按文件名筛选");
            println!("[live audit keyword] total={}", keywords.total);
            assert!(keywords.total >= 2, "文件名应命中成功/失败的删除日志");
            let matched_deletes = keywords
                .items
                .iter()
                .filter(|item| {
                    item.action.as_deref() == Some("delete")
                        && item
                            .path_param
                            .as_deref()
                            .map(|path| path.contains(&unique))
                            .unwrap_or(false)
                })
                .count();
            assert!(
                matched_deletes >= 2,
                "keyword 应至少命中成功/失败两条 delete 日志，实际 {matched_deletes}"
            );

            // requestId 检索
            let by_request = api
                .list_audit_logs(1, 10, None, None, None, Some(&request_id), None, None)
                .await
                .expect("按 requestId 筛选");
            assert!(by_request
                .items
                .iter()
                .any(|item| item.request_id == request_id));

            // 时间范围 + 分页
            let ranged = api
                .list_audit_logs(
                    1,
                    2,
                    None,
                    None,
                    None,
                    None,
                    Some("2026-01-01T00:00:00Z"),
                    Some("2027-01-01T00:00:00Z"),
                )
                .await
                .expect("按时间范围筛选");
            assert!(ranged.total >= 4);
            assert!(ranged.items.len() <= 2);
            let page2 = api
                .list_audit_logs(2, 2, None, None, None, None, None, None)
                .await
                .expect("第二页");
            assert_eq!(page2.page, 2);
            assert_eq!(page2.size, 2);
            assert!(!page2.items.is_empty());

            // 客户端传入越界 size 时在 query 构造阶段夹到 200，避免服务端 40000。
            let clamped = audit_logs_target(1, 500, None, None, None, None, None, None).unwrap();
            assert!(clamped.contains("size=200"), "{clamped}");

            let _ = std::fs::remove_file(&local_path);
        });
    }

    /// 真机拷贝/移动联调（task-40）：设置 `LIVE_COPY_MOVE_BASE_URL`，并让服务端使用
    /// `LIVE_COPY_MOVE_WORK_ROOT` / `LIVE_COPY_MOVE_MEDIA_ROOT` 两个存储根。
    ///
    /// 覆盖：拷贝文件/目录、再次拷贝编号、同根移动、跨根移动、同名冲突、移动到自身子目录。
    #[test]
    #[ignore = "需要真实服务端：设置 LIVE_COPY_MOVE_BASE_URL"]
    fn live_copy_move_roundtrip() {
        let base_url = std::env::var("LIVE_COPY_MOVE_BASE_URL").unwrap_or_default();
        if base_url.trim().is_empty() {
            eprintln!("跳过拷贝/移动 live 联调：未设置 LIVE_COPY_MOVE_BASE_URL");
            return;
        }
        let work_root = std::env::var("LIVE_COPY_MOVE_WORK_ROOT")
            .unwrap_or_else(|_| "/tmp/scop-ftp-client/work".to_string());
        let media_root = std::env::var("LIVE_COPY_MOVE_MEDIA_ROOT")
            .unwrap_or_else(|_| "/tmp/scop-ftp-client/media".to_string());
        let work = std::path::Path::new(&work_root);
        let media = std::path::Path::new(&media_root);
        std::fs::create_dir_all(work.join("target-dir")).expect("创建 work/target-dir");
        std::fs::create_dir_all(media.join("import")).expect("创建 media/import");
        std::fs::write(work.join("live-copy.txt"), "copy me\n").expect("写测试文件");
        std::fs::create_dir_all(work.join("live-dir/sub")).expect("创建测试目录");
        std::fs::write(work.join("live-dir/sub/a.txt"), "inside\n").expect("写目录文件");
        std::fs::write(work.join("conflict.txt"), "conflict\n").expect("写冲突文件");

        let _ = rustls::crypto::ring::default_provider().install_default();
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .build()
            .expect("创建 HTTP 客户端");
        let api = Api::new(http, make_server(&base_url));
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("创建 tokio runtime");

        runtime.block_on(async {
            // 1) 拷贝文件 + 再次拷贝生成编号
            let copied = api
                .copy_server_path("live-copy.txt", Some("work"))
                .await
                .expect("拷贝文件");
            println!("[live copy] {copied:?}");
            assert_eq!(copied.name, "live-copy_副本.txt");
            assert_eq!(copied.root, "work");
            assert_eq!(
                std::fs::read_to_string(work.join(&copied.path)).unwrap(),
                "copy me\n"
            );
            let copied_again = api
                .copy_server_path("live-copy.txt", Some("work"))
                .await
                .expect("再次拷贝文件");
            println!("[live copy again] {copied_again:?}");
            assert!(
                copied_again.name.starts_with("live-copy_副本 (1)"),
                "重名应追加 _副本 (1): {}",
                copied_again.name
            );

            // 2) 拷贝目录（递归）
            let copied_dir = api
                .copy_server_path("live-dir", Some("work"))
                .await
                .expect("拷贝目录");
            println!("[live copy dir] {copied_dir:?}");
            assert_eq!(copied_dir.name, "live-dir_副本");
            assert_eq!(
                std::fs::read_to_string(work.join(&copied_dir.path).join("sub/a.txt")).unwrap(),
                "inside\n"
            );

            // 3) 同根移动
            let moved = api
                .move_server_path("live-copy.txt", Some("work"), None, "target-dir")
                .await
                .expect("同根移动");
            println!("[live move] {moved:?}");
            assert_eq!(moved.root, "work");
            assert_eq!(moved.path, "target-dir/live-copy.txt");
            assert!(!work.join("live-copy.txt").exists(), "源文件应已移动");
            assert!(work.join("target-dir/live-copy.txt").exists());

            // 4) 跨根移动
            let cross = api
                .move_server_path(
                    "target-dir/live-copy.txt",
                    Some("work"),
                    Some("media"),
                    "import",
                )
                .await
                .expect("跨根移动");
            println!("[live move cross] {cross:?}");
            assert_eq!(cross.root, "media");
            assert_eq!(cross.path, "import/live-copy.txt");
            assert!(media.join("import/live-copy.txt").exists());
            assert!(!work.join("target-dir/live-copy.txt").exists());

            // 5) 同名冲突
            std::fs::write(work.join("target-dir/conflict.txt"), "occupied\n").unwrap();
            let conflict = api
                .move_server_path("conflict.txt", Some("work"), None, "target-dir")
                .await
                .expect_err("同名冲突应失败");
            println!("[live move conflict] {conflict}");
            assert!(conflict.contains("已存在"), "冲突提示应中文透传: {conflict}");

            // 6) 移动到自身子目录
            let self_error = api
                .move_server_path("live-dir", Some("work"), None, "live-dir/sub")
                .await
                .expect_err("移动到自身子目录应失败");
            println!("[live move self] {self_error}");
            assert!(
                self_error.contains("自身") || self_error.contains("子目录"),
                "自身子目录错误应透传: {self_error}"
            );

            // 清理（尽力而为）
            for path in ["live-dir", "live-dir_副本", "target-dir", "conflict.txt"] {
                let _ = std::fs::remove_dir_all(work.join(path));
                let _ = std::fs::remove_file(work.join(path));
            }
            let _ = std::fs::remove_file(media.join("import/live-copy.txt"));
        });
    }

    /// 真机终端联调（task-30/task-33）：设置 `LIVE_TERMINAL_BASE_URL`（如 http://127.0.0.1:18080）
    /// 后执行 `cargo test live_terminal_roundtrip -- --ignored --nocapture`。
    ///
    /// 可选环境变量：
    /// - `LIVE_TERMINAL_WORK_ROOT` / `LIVE_TERMINAL_MEDIA_ROOT`：服务端配置的两个存储根绝对路径，
    ///   用于断言 cwd 限制在根内、跨根 cd 成功；
    /// - `LIVE_TERMINAL_STRICT_BASE_URL`：timeout=1s / max-output-bytes=64 的实例。
    #[test]
    #[ignore = "需要真实服务端：设置 LIVE_TERMINAL_BASE_URL"]
    fn live_terminal_roundtrip() {
        let base_url = std::env::var("LIVE_TERMINAL_BASE_URL").unwrap_or_default();
        if base_url.trim().is_empty() {
            eprintln!("跳过终端 live 联调：未设置 LIVE_TERMINAL_BASE_URL");
            return;
        }
        let strict_url = std::env::var("LIVE_TERMINAL_STRICT_BASE_URL").unwrap_or_default();
        let work_root = std::env::var("LIVE_TERMINAL_WORK_ROOT").unwrap_or_default();
        let media_root = std::env::var("LIVE_TERMINAL_MEDIA_ROOT").unwrap_or_default();
        let canonical = |path: &str| {
            std::fs::canonicalize(path).unwrap_or_else(|_| std::path::PathBuf::from(path))
        };
        if !work_root.trim().is_empty() {
            std::fs::create_dir_all(std::path::Path::new(&work_root).join("docs"))
                .expect("创建 work/docs");
        }

        let _ = rustls::crypto::ring::default_provider().install_default();
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .build()
            .expect("创建 HTTP 客户端");
        let api = Api::new(http.clone(), make_server(&base_url));
        let strict_api = if strict_url.trim().is_empty() {
            None
        } else {
            Some(Api::new(http, make_server(&strict_url)))
        };
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("创建 tokio runtime");
        let session_id = Uuid::new_v4().to_string();

        runtime.block_on(async move {
            // 默认 root=work：首条命令的 cwd 必须位于该存储根内（task-32）。
            let first = api
                .terminal_exec(&session_id, "echo terminal-ok", None, Some("work"))
                .await
                .expect("echo 执行");
            println!("[live] echo: {first:?}");
            assert_eq!(first.exit_code, 0);
            assert!(first.output.contains("terminal-ok"));
            assert!(!first.truncated);
            if !work_root.trim().is_empty() {
                assert_eq!(
                    canonical(&first.cwd),
                    canonical(&work_root),
                    "默认 cwd 应在 work 存储根内"
                );
            }

            let ls = api
                .terminal_exec(&session_id, "ls", None, None)
                .await
                .expect("ls 执行");
            println!("[live] ls exit={} output={:?}", ls.exit_code, ls.output);
            assert_eq!(ls.exit_code, 0);

            let cd = api
                .terminal_exec(&session_id, "cd docs", None, None)
                .await
                .expect("cd docs 执行");
            println!("[live] cd docs -> {}", cd.cwd);
            assert_eq!(cd.exit_code, 0);
            if !work_root.trim().is_empty() {
                let docs_path = format!("{}/docs", work_root.trim_end_matches('/'));
                assert_eq!(canonical(&cd.cwd), canonical(&docs_path));
            }

            let pwd = api
                .terminal_exec(&session_id, "pwd", None, None)
                .await
                .expect("pwd 执行");
            println!("[live] pwd -> {:?}", pwd.output);
            assert_eq!(pwd.cwd, cd.cwd, "pwd 应使用同一 session 的 cwd");
            assert!(pwd.output.trim().ends_with("docs"));

            let unsafe_cd = api
                .terminal_exec(&session_id, "cd /tmp", None, None)
                .await
                .expect_err("cd /tmp 应被非安全目录护栏拦截");
            println!("[live] cd /tmp blocked: {unsafe_cd}");
            assert!(unsafe_cd.contains("目录不在安全范围"), "{unsafe_cd}");

            let unsafe_cat = api
                .terminal_exec(&session_id, "cat /etc/hosts", None, None)
                .await
                .expect_err("cat /etc/hosts 应被非安全目录护栏拦截");
            println!("[live] cat /etc/hosts blocked: {unsafe_cat}");
            assert!(unsafe_cat.contains("命令涉及非安全目录"), "{unsafe_cat}");

            // 根内重定向写文件成功，且文件真实落在 work/docs。
            let write = api
                .terminal_exec(&session_id, "echo live-file > inside.txt", None, None)
                .await
                .expect("根内写文件应成功");
            assert_eq!(write.exit_code, 0);
            assert!(write.cwd.ends_with("docs"));
            if !work_root.trim().is_empty() {
                let written = std::path::Path::new(&work_root)
                    .join("docs")
                    .join("inside.txt");
                assert!(written.exists(), "根内文件应已创建: {}", written.display());
                let _ = std::fs::remove_file(&written);
            }

            // 跨根 cd 允许（各根都属于 app.storage.roots）。
            if !media_root.trim().is_empty() {
                let cross = api
                    .terminal_exec(&session_id, &format!("cd {media_root}"), None, None)
                    .await
                    .expect("cd 到媒体根应成功");
                println!("[live] cd media -> {}", cross.cwd);
                assert_eq!(canonical(&cross.cwd), canonical(&media_root));

                let media_session = Uuid::new_v4().to_string();
                let media_pwd = api
                    .terminal_exec(&media_session, "pwd", None, Some("media"))
                    .await
                    .expect("root=media 新会话 pwd");
                println!("[live] root=media cwd -> {}", media_pwd.cwd);
                assert_eq!(canonical(&media_pwd.cwd), canonical(&media_root));
            }

            let nonzero = api
                .terminal_exec(&session_id, "false", None, None)
                .await
                .expect("false 应返回 exitCode 而非错误");
            println!("[live] false exit={}", nonzero.exit_code);
            assert_ne!(nonzero.exit_code, 0);

            let blocked = api
                .terminal_exec(&session_id, "rm -rf /", None, None)
                .await
                .expect_err("高危命令必须被拦截");
            println!("[live] blocked: {blocked}");
            assert!(blocked.contains("高危命令已禁止执行"));
            assert!(blocked.contains("原因"));

            if let Some(strict_api) = strict_api {
                let strict_session = Uuid::new_v4().to_string();
                let timeout = strict_api
                    .terminal_exec(&strict_session, "sleep 5", None, None)
                    .await
                    .expect("超时仍应返回 200 + exitCode");
                println!("[live] timeout: exit={} output={:?}", timeout.exit_code, timeout.output);
                assert_eq!(timeout.exit_code, -1);
                assert!(timeout.output.contains("超时"));

                let truncated = strict_api
                    .terminal_exec(&strict_session, "printf '%0500d' 0", None, None)
                    .await
                    .expect("截断命令执行");
                println!(
                    "[live] truncated={} bytes={}",
                    truncated.truncated,
                    truncated.output.len()
                );
                assert!(truncated.truncated, "输出应被截断");
            }
        });
    }

    #[test]
    fn auth_headers_use_custom_server_credentials() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let mut server = make_server("http://127.0.0.1:8080");
        server.app_key = "custom-app".to_string();
        server.app_secret = "custom-secret-0123456789".to_string();
        let api = Api::new(reqwest::Client::new(), server);

        let headers = api
            .auth_headers("GET", "/api/health", "", EMPTY_SHA256, &[])
            .expect("构造签名头");
        let header = |name: &str| headers.get(name).unwrap().to_str().unwrap().to_string();
        assert_eq!(header(HEADER_APP_KEY), "custom-app");
        assert_eq!(header(HEADER_DEVICE_ID), api.device_id());

        let timestamp: i64 = header(HEADER_TIMESTAMP).parse().expect("时间戳");
        let expected = signer::sign_request(
            "custom-secret-0123456789",
            "custom-app",
            timestamp,
            &header(HEADER_NONCE),
            "GET",
            "/api/health",
            "",
            EMPTY_SHA256,
        );
        assert_eq!(header(HEADER_SIGNATURE), expected);

        // 未自定义时回退到编译期内置凭据。
        let fallback = Api::new(reqwest::Client::new(), make_server("http://127.0.0.1:8080"));
        let headers = fallback
            .auth_headers("GET", "/api/health", "", EMPTY_SHA256, &[])
            .expect("构造内置签名头");
        assert_eq!(
            headers.get(HEADER_APP_KEY).unwrap().to_str().unwrap(),
            crate::builtin::app_key()
        );
    }
}
