//! Tauri 命令：前端只能通过这里访问网络与本地配置。
//!
//! - AppKey / AppSecret 默认内置（见 [`crate::builtin`]），每台服务器可在设置中覆盖；secret 不返回前端；
//! - 配置为多服务器 v2：每台服务器独立的 serverUrl / deviceId / deviceName / 可选凭据；
//! - 所有网络命令都作用于当前激活的服务器；
//! - 大文件上传自动走分片 + 断点续传（见 [`crate::uploads`]）。

use serde::Serialize;
use tauri::{AppHandle, Emitter, State};

use crate::api::{
    Api, AuditLogPage, CopyResult, ExtractResult, FileContent, FileList, FileMeta, MoveResult,
    RenameResult, SavedContent, StorageRoots, TerminalResult,
};
use crate::config::{self, ConfigView};
use crate::uploads;
use crate::AppState;

/// 上传进度事件 `upload-progress` 的 payload（契约 5.3）。
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UploadProgress {
    upload_id: String,
    sent: u64,
    total: u64,
}

/// 用「当前激活服务器」的地址与设备身份构造 HTTP 客户端。
fn api_from(state: &AppState) -> Result<Api, String> {
    let server = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .require_active()?
        .clone();
    Ok(Api::new(state.http.clone(), server))
}

// ============================================================================
// 配置 / 多服务器
// ============================================================================

/// 读取配置视图（多服务器列表 + 当前生效 appKey；不包含 appSecret）。
#[tauri::command]
pub async fn get_config(state: State<'_, AppState>) -> Result<ConfigView, String> {
    let config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();
    Ok(config.view())
}

/// 新建或编辑一台服务器。
///
/// - 不传 `id`：新建（生成 id 与独立 deviceId），并自动设为当前服务器；
/// - 传 `id`：编辑该服务器，保留其 id 与 deviceId。
#[tauri::command]
pub async fn save_server(
    state: State<'_, AppState>,
    id: Option<String>,
    name: String,
    server_url: String,
    device_name: String,
    app_key: Option<String>,
    app_secret: Option<String>,
) -> Result<ConfigView, String> {
    let mut config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();

    config.save_server_with_credentials(
        id.as_deref(),
        &name,
        &server_url,
        &device_name,
        app_key.as_deref(),
        app_secret.as_deref(),
    )?;

    // 先落盘，成功后再生效，避免半途失败导致内存与磁盘不一致。
    config::persist(&state.config_path, &config)?;

    let view = config.view();
    *state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())? = config;
    Ok(view)
}

/// 删除一台服务器（至少保留一台）；删除当前服务器后自动切换。
#[tauri::command]
pub async fn delete_server(
    state: State<'_, AppState>,
    id: String,
) -> Result<ConfigView, String> {
    let mut config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();

    config.delete_server(&id)?;
    config::persist(&state.config_path, &config)?;

    // 顺手清理该服务器遗留的断点续传状态。
    if let Ok(mut store) = uploads::UploadStore::load(&state.upload_state_path) {
        if store.remove_by_server_id(&id) > 0 {
            let _ = store.save();
        }
    }

    let view = config.view();
    *state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())? = config;
    Ok(view)
}

/// 切换当前服务器。
#[tauri::command]
pub async fn set_active_server(
    state: State<'_, AppState>,
    id: String,
) -> Result<ConfigView, String> {
    let mut config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();

    config.set_active(&id)?;
    config::persist(&state.config_path, &config)?;

    let view = config.view();
    *state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())? = config;
    Ok(view)
}

// ============================================================================
// 本地文件辅助
// ============================================================================

/// 读取本地文件的基础信息（文件名/大小），仅用于上传前展示，不涉及网络与密钥。
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalFileInfo {
    pub name: String,
    pub size: u64,
}

#[tauri::command]
pub fn file_info(path: String) -> Result<LocalFileInfo, String> {
    let path = std::path::PathBuf::from(&path);
    let metadata = std::fs::metadata(&path).map_err(|e| format!("无法读取文件信息：{e}"))?;
    if !metadata.is_file() {
        return Err("所选路径不是文件".to_string());
    }
    let name = path
        .file_name()
        .map(|name| name.to_string_lossy().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "file".to_string());
    Ok(LocalFileInfo {
        name,
        size: metadata.len(),
    })
}

// ============================================================================
// 网络命令（全部作用于当前激活服务器）
// ============================================================================

/// 验证连接（契约 3.2）。
#[tauri::command]
pub async fn verify_connection(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    api_from(&state)?.verify_connection().await
}

/// 设备激活/绑定（契约 3.3），使用当前服务器的 deviceId / deviceName。
#[tauri::command]
pub async fn activate(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    api_from(&state)?.activate().await
}

/// 列出服务端可用的存储根（task-16 多存储根）；旧单根服务端没有该接口时
/// 前端会自动回退为「不传 root」的默认根行为。
#[tauri::command]
pub async fn list_storage_roots(state: State<'_, AppState>) -> Result<StorageRoots, String> {
    api_from(&state)?.list_storage_roots().await
}

/// 上传文件（契约 3.5 / task-14 分片），进度通过 `upload-progress` 事件回传。
///
/// ≤ 32MB 走单次 multipart；> 32MB 走分片 + 断点续传。`dir` 为相对存储根目录的
/// 目标目录（FTP 式目录上传），`root` 为存储根 id；两者 `None` / 空都表示默认根目录。
/// 断点续传状态按 `(serverId, rootId, dir, fileName, size, mtime, sha256)` 匹配。
#[tauri::command]
pub async fn upload_file(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
    description: Option<String>,
    dir: Option<String>,
    root: Option<String>,
    upload_id: String,
) -> Result<FileMeta, String> {
    let api = api_from(&state)?;
    let emit_app = app.clone();
    let progress_upload_id = upload_id.clone();

    uploads::upload_with_resume(
        &api,
        &state.upload_state_path,
        &path,
        description.as_deref(),
        dir.as_deref(),
        root.as_deref(),
        &upload_id,
        move |sent, total| {
            let _ = emit_app.emit(
                "upload-progress",
                UploadProgress {
                    upload_id: progress_upload_id.clone(),
                    sent,
                    total,
                },
            );
        },
    )
    .await
}

/// 分页查询文件列表（契约 3.4）。
#[tauri::command]
pub async fn list_files(
    state: State<'_, AppState>,
    page: u32,
    size: u32,
    keyword: Option<String>,
    root: Option<String>,
) -> Result<FileList, String> {
    api_from(&state)?
        .list_files(page, size, keyword.as_deref(), root.as_deref())
        .await
}

/// 浏览服务端存储根目录下的真实目录（契约 3.8）；`path` 为空表示根目录。
#[tauri::command]
pub async fn list_server_dir(
    state: State<'_, AppState>,
    path: Option<String>,
    root: Option<String>,
) -> Result<serde_json::Value, String> {
    api_from(&state)?
        .list_server_dir(path.as_deref(), root.as_deref())
        .await
}

/// 在服务端存储根目录内递归搜索文件/目录（task-14 契约 B）。
#[tauri::command]
pub async fn search_server_files(
    state: State<'_, AppState>,
    keyword: String,
    limit: Option<u32>,
    root: Option<String>,
) -> Result<serde_json::Value, String> {
    api_from(&state)?
        .search_server_files(&keyword, limit.unwrap_or(100), root.as_deref())
        .await
}

/// 下载文件到用户选择的位置并校验 SHA-256（契约 3.6），返回保存后的绝对路径。
#[tauri::command]
pub async fn download_file(
    state: State<'_, AppState>,
    id: String,
    target_path: String,
) -> Result<String, String> {
    api_from(&state)?.download_file(&id, &target_path).await
}

/// 删除文件（契约 3.7，`DELETE /api/files/{id}`）。
#[tauri::command]
pub async fn delete_file(state: State<'_, AppState>, id: String) -> Result<(), String> {
    api_from(&state)?.delete_file(&id).await
}

/// 按相对路径下载服务端存储根目录中的文件（FTP 式目录浏览），返回保存后的绝对路径。
#[tauri::command]
pub async fn download_server_file(
    state: State<'_, AppState>,
    path: String,
    target_path: String,
    root: Option<String>,
) -> Result<String, String> {
    api_from(&state)?
        .download_server_file(&path, &target_path, root.as_deref())
        .await
}

/// 按相对路径删除服务端存储根目录中的文件（FTP 式目录浏览）。
#[tauri::command]
pub async fn delete_server_file(
    state: State<'_, AppState>,
    path: String,
    root: Option<String>,
) -> Result<(), String> {
    api_from(&state)?
        .delete_server_file(&path, root.as_deref())
        .await
}

/// 重命名服务端文件/目录（task-18，`POST /api/files/rename`）。
///
/// `path` 为相对当前存储根的路径，`new_name` 只能是单段名称（不含 `/`、`\\`、不为 `.`/`..`）；
/// `root` 缺省/空表示默认根。失败时返回可直接展示的中文错误（冲突/非法名 40000、不存在 40400）。
/// `POST /api/files/copy`：原地拷贝文件/目录（task-40）。
#[tauri::command]
pub async fn copy_server_path(
    state: State<'_, AppState>,
    path: String,
    root: Option<String>,
) -> Result<CopyResult, String> {
    api_from(&state)?.copy_server_path(&path, root.as_deref()).await
}

/// `POST /api/files/move`：移动文件/目录（task-40，可跨根）。
#[tauri::command]
pub async fn move_server_path(
    state: State<'_, AppState>,
    path: String,
    root: Option<String>,
    target_root: Option<String>,
    target_dir: Option<String>,
) -> Result<MoveResult, String> {
    api_from(&state)?
        .move_server_path(
            &path,
            root.as_deref(),
            target_root.as_deref(),
            target_dir.as_deref().unwrap_or_default(),
        )
        .await
}

/// `GET /api/audit/logs`：分页查询当前 appKey 的操作日志（task-45）。
#[allow(clippy::too_many_arguments)]
#[tauri::command]
pub async fn list_audit_logs(
    state: State<'_, AppState>,
    page: Option<u32>,
    size: Option<u32>,
    action: Option<String>,
    category: Option<String>,
    success: Option<bool>,
    keyword: Option<String>,
    from: Option<String>,
    to: Option<String>,
) -> Result<AuditLogPage, String> {
    api_from(&state)?
        .list_audit_logs(
            page.unwrap_or(1),
            size.unwrap_or(50),
            action.as_deref(),
            category.as_deref(),
            success,
            keyword.as_deref(),
            from.as_deref(),
            to.as_deref(),
        )
        .await
}

/// 用系统默认应用打开服务端文件（task-26）。
///
/// 先下载到 `$TMPDIR/scop-ftp-open/<安全文件名>`（同名覆盖），再调用
/// macOS `open` / Windows `start` / Linux `xdg-open`；不弹保存对话框。
#[tauri::command]
pub async fn open_server_file(
    state: State<'_, AppState>,
    path: String,
    root: Option<String>,
) -> Result<String, String> {
    api_from(&state)?
        .open_server_file(&path, root.as_deref())
        .await
}

/// `POST /api/terminal/exec`：在服务器上执行单条终端命令（task-30）。
///
/// sessionId 由前端生成，Rust 不维护状态；错误 message 原样透传给前端判断高危命令。
#[tauri::command]
pub async fn terminal_exec(
    state: State<'_, AppState>,
    session_id: String,
    command: String,
    cwd: Option<String>,
    root: Option<String>,
) -> Result<TerminalResult, String> {
    api_from(&state)?
        .terminal_exec(
            &session_id,
            &command,
            cwd.as_deref(),
            root.as_deref(),
        )
        .await
}

/// `GET /api/files/content`：读取文本文件内容用于在线编辑（task-23）。
///
/// 目录 / 二进制 / 超过 2MiB 由服务端返回 40000；不存在 40400。
#[tauri::command]
pub async fn read_server_file_content(
    state: State<'_, AppState>,
    path: String,
    root: Option<String>,
) -> Result<FileContent, String> {
    api_from(&state)?
        .read_server_file_content(&path, root.as_deref())
        .await
}

/// `PUT /api/files/content`：覆盖保存文本文件（task-23）。
///
/// `expected_sha256` 为打开编辑器时的内容哈希；不一致时服务端返回 40000 且不写入。
#[tauri::command]
pub async fn save_server_file_content(
    state: State<'_, AppState>,
    path: String,
    content: String,
    expected_sha256: Option<String>,
    root: Option<String>,
) -> Result<SavedContent, String> {
    api_from(&state)?
        .save_server_file_content(&path, &content, expected_sha256.as_deref(), root.as_deref())
        .await
}

/// `POST /api/files/extract`：解压 zip 到同级同名目录（task-23）。
#[tauri::command]
pub async fn extract_zip_file(
    state: State<'_, AppState>,
    path: String,
    root: Option<String>,
) -> Result<ExtractResult, String> {
    api_from(&state)?
        .extract_zip_file(&path, root.as_deref())
        .await
}

#[tauri::command]
pub async fn rename_server_file(
    state: State<'_, AppState>,
    path: String,
    new_name: String,
    root: Option<String>,
) -> Result<RenameResult, String> {
    api_from(&state)?
        .rename_server_file(&path, &new_name, root.as_deref())
        .await
}
