//! Tauri 命令：前端只能通过这里访问网络与本地配置。
//!
//! - AppKey / AppSecret 必填：必须与服务端启动时指定的一致，不再回退内置默认值；
//!   `get_config` 不返回 secret；用户点击密码框眼睛时通过 `reveal_server_secret`
//!   按 id 读取本地自定义值返回给设置页显示；
//! - 配置为多服务器 v3 树形结构：每台服务器独立的 serverUrl / deviceId / deviceName / 凭证，
//!   relay 目标通过 `parentId` 指向上一跳，请求时按链签名（见 [`crate::api`]）；
//! - 所有网络命令都作用于当前激活的 target 服务器（relay 时请求经整条链转发）；
//! - 大文件上传自动走分片 + 断点续传（见 [`crate::uploads`]）。

use serde::Serialize;
use std::time::Duration;
use tauri::{AppHandle, Emitter, State};

use crate::api::{
    Api, AuditLogPage, CopyResult, CreateResult, ExtractResult, FileContent, FileList, FileMeta,
    MoveResult, RenameResult, SavedContent, StorageRoots, TerminalResult,
};
use crate::config::{self, ConfigView, ServerProfile};
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

/// 解析当前激活服务器的完整转发链（root → target）。
fn active_chain(state: &AppState) -> Result<Vec<ServerProfile>, String> {
    let config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();
    let active = config.require_active()?;
    Ok(config
        .resolve_chain(&active.id)?
        .into_iter()
        .cloned()
        .collect::<Vec<ServerProfile>>())
}

/// 用「当前激活服务器」的转发链构造 HTTP 客户端（root → target）。
fn api_from(state: &AppState) -> Result<Api, String> {
    Ok(Api::from_chain(state.http.clone(), active_chain(state)?))
}

/// 转发链的祖先前缀（root → target 的上一跳），用于逐跳预激活（设计 4.9）。
///
/// - `[A, B, C]` → `[[A], [A, B]]`（C 是 target，不包含）；
/// - `[A, B]` → `[[A]]`；
/// - `[A]` / 空 → `[]`（直连或空配置不做 preflight）。
fn ancestor_prefixes(chain: &[ServerProfile]) -> Vec<Vec<ServerProfile>> {
    if chain.len() <= 1 {
        return Vec::new();
    }
    (1..chain.len())
        .map(|length| chain[..length].to_vec())
        .collect()
}

/// 逐跳预激活祖先服务器（设计 4.9）：
/// 按 root → target 上一跳的顺序，对每个前缀执行 `verify_connection()` + `activate()`。
///
/// 中间跳（relay 节点）自己的 `/api/relay/**` 请求会做设备绑定检查，所以必须先用
/// 它自己的 deviceId/deviceName 完成 verify → activate；否则 3 跳以上访问文件接口会收到
/// 40300。最终 target 不在这里 activate（UI 在验证连接后自己激活 target）。
///
/// 任一跳失败立即返回「转发链第 N 跳 <name> 验证/激活失败：...」，不静默继续。
async fn preflight_ancestors(http: reqwest::Client, chain: &[ServerProfile]) -> Result<(), String> {
    for (index, prefix) in ancestor_prefixes(chain).into_iter().enumerate() {
        let hop_name = prefix
            .last()
            .map(|hop| hop.name.trim().to_string())
            .unwrap_or_default();
        let label = if hop_name.is_empty() {
            format!("第 {} 跳", index + 1)
        } else {
            format!("第 {} 跳 {}", index + 1, hop_name)
        };
        let api = Api::from_chain(http.clone(), prefix);
        api.verify_connection()
            .await
            .map_err(|error| format!("转发链{label}验证失败：{error}"))?;
        api.activate()
            .await
            .map_err(|error| format!("转发链{label}激活失败：{error}"))?;
    }
    Ok(())
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

/// 在用户点击密码框眼睛时返回一台已保存服务器的自定义 AppSecret。
///
/// 这是唯一会向前端返回 secret 的命令；`get_config` 仍然只返回 `hasSecret`，
/// 不把 secret 放进常规配置视图。只允许查看自定义值，内置演示密钥不返回前端。
#[tauri::command]
pub async fn reveal_server_secret(
    state: State<'_, AppState>,
    id: Option<String>,
) -> Result<String, String> {
    let config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();
    let server = match id
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        Some(id) => config.find(id).ok_or_else(|| "服务器不存在".to_string())?,
        None => config.require_active()?,
    };
    if !server.has_custom_secret() {
        return Err("该服务器使用内置 AppSecret，不能查看".to_string());
    }
    let secret = server.effective_app_secret().trim();
    if secret.is_empty() {
        return Err("该服务器未配置 AppSecret".to_string());
    }
    Ok(secret.to_string())
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
    // 必填：服务端启动时必须显式指定 AppKey/AppSecret，客户端不再回退内置默认值。
    config::validate_required_credentials(
        id.as_deref(),
        app_key.as_deref(),
        app_secret.as_deref(),
    )?;

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

/// 删除一台服务器及其所有代理服务器（级联）：清理整棵子树的本地配置与续传状态；
/// 允许删到空配置（返回的 `activeServer` 为 null）。
#[tauri::command]
pub async fn delete_server(state: State<'_, AppState>, id: String) -> Result<ConfigView, String> {
    let mut config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();

    let deleted = config.delete_server(&id)?;
    config::persist(&state.config_path, &config)?;

    // 清理所有被删服务器（含子树）遗留的断点续传状态。
    if let Ok(mut store) = uploads::UploadStore::load(&state.upload_state_path) {
        let mut changed = false;
        for server_id in &deleted {
            changed |= store.remove_by_server_id(server_id) > 0;
        }
        if changed {
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

/// 新建 / 编辑一个「代理服务器」（relay）：保存到 `parentId` 下并落盘。
///
/// - 不传 `id`：新建（生成独立 id 与 deviceId），自动设为 active；
/// - 传 `id`：编辑，保留原 id 与 deviceId；
/// - `app_secret` 为空/`null` 表示保留已保存的 secret（新建时必填）。
#[tauri::command]
pub async fn save_relay(
    state: State<'_, AppState>,
    id: Option<String>,
    parent_id: String,
    name: String,
    server_url: String,
    device_name: String,
    app_key: String,
    app_secret: Option<String>,
) -> Result<ConfigView, String> {
    // 空白 secret 视为「保留原值」；新建时仍必须提供。
    let secret_for_validation = app_secret
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty());
    config::validate_required_credentials(
        id.as_deref(),
        Some(app_key.as_str()),
        secret_for_validation,
    )?;

    let mut config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();

    config.save_relay(
        id.as_deref(),
        &parent_id,
        &name,
        &server_url,
        &device_name,
        &app_key,
        app_secret.as_deref(),
    )?;
    config::persist(&state.config_path, &config)?;

    let view = config.view();
    *state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())? = config;
    Ok(view)
}

/// 代理服务器保存前的预览验证：按 `parentId` 解析父链 + 表单里的目标临时拼成链，
/// 先逐跳验证并激活 parent 链上的祖先（设计 4.9），再验证整条链可达且 target 的
/// key/secret 正确；**不落盘，也不激活/写入最终 target**。
///
/// - `existing_id` 为编辑时已保存的 relay id（沿用其 deviceId/secret）；
/// - 新建使用临时 deviceId / 表单 secret，不激活目标。
#[tauri::command]
pub async fn verify_relay_preview(
    state: State<'_, AppState>,
    parent_id: String,
    existing_id: Option<String>,
    name: String,
    server_url: String,
    device_name: String,
    app_key: String,
    app_secret: Option<String>,
) -> Result<serde_json::Value, String> {
    let config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();
    let parent = config
        .find(&parent_id)
        .ok_or_else(|| "上级服务器不存在".to_string())?;
    let mut chain = config
        .resolve_chain(&parent.id)?
        .into_iter()
        .cloned()
        .collect::<Vec<ServerProfile>>();

    let mut relay = if let Some(existing_id) = existing_id
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        config
            .find(existing_id)
            .cloned()
            .ok_or_else(|| "代理服务器不存在".to_string())?
    } else {
        ServerProfile::new_relay(
            &name,
            &server_url,
            &device_name,
            &app_key,
            app_secret.as_deref().unwrap_or_default(),
            &parent.id,
        )
    };
    let server_url = config::normalize_server_url(&server_url)?;
    if server_url.is_empty() {
        return Err("服务器地址不能为空".to_string());
    }
    relay.name = config::normalize_server_name(&name)?;
    relay.server_url = server_url;
    relay.device_name = config::normalize_device_name(&device_name)?;
    relay.app_key = app_key.trim().to_string();
    if let Some(secret) = app_secret
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        relay.app_secret = secret.to_string();
    }
    if relay.app_key.is_empty() {
        return Err("代理服务器 AppKey 必填".to_string());
    }
    if relay.app_secret.trim().is_empty() {
        return Err("代理服务器 AppSecret 必填".to_string());
    }
    chain.push(relay);

    // 设计 4.9：先按前缀逐跳验证并激活 parent 链上的所有祖先（不含临时 target），
    // 再验证临时 target；全程只在内存中构造链，不读取/写入已保存配置。
    preflight_ancestors(state.http.clone(), &chain).await?;
    Api::from_chain(state.http.clone(), chain)
        .verify_connection()
        .await
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
// 本机信息
// ============================================================================

/// 本机网络信息：物理地址（MAC）与默认路由网卡的局域网 IPv4。
///
/// 只用于详情页展示，不写配置、不参与签名；取不到的字段为空串。
#[tauri::command]
pub fn get_local_network_info() -> crate::net::LocalNetworkInfo {
    crate::net::local_network_info()
}

/// 平台信息：前端据此决定自绘标题栏的布局。
///
/// - macOS：标题栏左侧要给系统红绿灯留白；
/// - Windows：原生边框已去掉，标题栏自己画最小化/最大化/关闭按钮；
/// - Linux：保留系统边框，标题栏只放品牌与更新按钮。
///
/// 用编译期 target 判断，不靠 userAgent 猜（打包后的 WebView 标识各版本不一致）。
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlatformInfo {
    /// `windows` / `macos` / `linux`
    pub os: &'static str,
    /// 架构，例如 `aarch64` / `x86_64`
    pub arch: &'static str,
}

#[tauri::command]
pub fn get_platform_info() -> PlatformInfo {
    let os = if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    };
    PlatformInfo {
        os,
        arch: std::env::consts::ARCH,
    }
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
///
/// relay 目标（链长 > 1）先按前缀逐跳验证并激活所有祖先，再验证最终 target；
/// 直连（链长 ≤ 1）不触发任何 preflight，行为与旧版一致。
#[tauri::command]
pub async fn verify_connection(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    let chain = active_chain(&state)?;
    preflight_ancestors(state.http.clone(), &chain).await?;
    Api::from_chain(state.http.clone(), chain)
        .verify_connection()
        .await
}

/// 单独验证一台已保存服务器的连接（不切换 active、不激活设备）。
///
/// 用于左侧服务器列表的「当前一组服务器连通性检测」：relay 目标会沿 `parentId`
/// 解析出完整链，但只做一次 verify，不调用 preflight/activate，避免检测动作改变设备状态。
#[tauri::command]
pub async fn verify_server(state: State<'_, AppState>, id: String) -> Result<(), String> {
    let config = state
        .config
        .lock()
        .map_err(|_| "配置状态异常，请重启客户端".to_string())?
        .clone();
    let target = config
        .find(id.trim())
        .ok_or_else(|| "服务器不存在".to_string())?;
    let chain = config
        .resolve_chain(&target.id)?
        .into_iter()
        .cloned()
        .collect::<Vec<ServerProfile>>();
    // verify 走的是完整转发链；目标不通时底层 reqwest 可能长时间阻塞，
    // 这里显式限时，让左侧圆点从「检测中」及时落到灰色，而不是一直黄。
    match tokio::time::timeout(
        Duration::from_secs(8),
        Api::from_chain(state.http.clone(), chain).verify_connection(),
    )
    .await
    {
        Ok(Ok(_)) => Ok(()),
        Ok(Err(error)) => Err(error),
        Err(_) => Err("连接超时（8 秒）".to_string()),
    }
}

/// 添加 / 编辑服务器前的预览验证（契约 3.2）：直接用表单里的地址与凭据验证连接，
/// **不读取、不写入已保存配置**，所以不需要先把服务器加入列表。
///
/// - 添加页：`existing_id` 为空，使用临时 deviceId/deviceName；验证成功后再由前端调用
///   `save_server` 真正添加。`/api/auth/verify` 不校验设备绑定，不会影响之后保存的身份。
/// - 编辑页：`existing_id` 指向已保存的服务器，沿用其 deviceId/deviceName 和未修改的
///   AppSecret（`app_secret` 为 `None` 表示保持原值，`Some("")` 表示清除自定义密钥回退内置值），
///   用表单值覆盖地址 / AppKey；验证成功后才由前端保存，失败不落盘。
#[tauri::command]
pub async fn verify_connection_preview(
    state: State<'_, AppState>,
    server_url: String,
    app_key: Option<String>,
    app_secret: Option<String>,
    existing_id: Option<String>,
) -> Result<serde_json::Value, String> {
    let existing_id = existing_id
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty());
    let profile = match existing_id {
        Some(id) => {
            let config = state
                .config
                .lock()
                .map_err(|_| "配置状态异常，请重启客户端".to_string())?
                .clone();
            saved_preview_profile(
                &config,
                id,
                &server_url,
                app_key.as_deref(),
                app_secret.as_deref(),
            )?
        }
        None => preview_profile(&server_url, app_key.as_deref(), app_secret.as_deref())?,
    };
    Api::new(state.http.clone(), profile)
        .verify_connection()
        .await
}

/// 构造预览验证用的临时服务器配置（不落盘）：规范化地址、裁剪凭据。
///
/// 空 AppKey / AppSecret 保持空串，由 [`ServerProfile`] 回退到客户端内置默认值。
fn preview_profile(
    server_url: &str,
    app_key: Option<&str>,
    app_secret: Option<&str>,
) -> Result<ServerProfile, String> {
    let server_url = config::normalize_server_url(server_url)?;
    if server_url.is_empty() {
        return Err("请输入服务器地址".to_string());
    }
    let mut profile = ServerProfile::new("", &server_url, "");
    profile.app_key = app_key.unwrap_or_default().trim().to_string();
    profile.app_secret = app_secret.unwrap_or_default().trim().to_string();
    Ok(profile)
}

/// 编辑已保存服务器时的预览验证配置（不落盘）：
/// 沿用已保存的 id / deviceId / deviceName / 未修改的 secret，用表单值覆盖地址与 AppKey。
///
/// `app_secret == None` 表示保持原值；`Some("")` 表示清除自定义密钥（验证时回退内置值）。
fn saved_preview_profile(
    config: &config::AppConfig,
    existing_id: &str,
    server_url: &str,
    app_key: Option<&str>,
    app_secret: Option<&str>,
) -> Result<ServerProfile, String> {
    let mut profile = config
        .find(existing_id)
        .cloned()
        .ok_or_else(|| "服务器不存在，请刷新后重试".to_string())?;
    profile.server_url = config::normalize_server_url(server_url)?;
    if profile.server_url.is_empty() {
        return Err("请输入服务器地址".to_string());
    }
    if let Some(app_key) = app_key {
        profile.app_key = app_key.trim().to_string();
    }
    if let Some(app_secret) = app_secret {
        profile.app_secret = app_secret.trim().to_string();
    }
    Ok(profile)
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

/// 按相对路径删除服务端存储根目录中的文件/目录（FTP 式目录浏览）。
///
/// `recursive=true` 时服务端一次性递归删除非空目录；缺省/false 保持旧行为
/// （非空目录返回「目录非空，不能删除」）。
#[tauri::command]
pub async fn delete_server_file(
    state: State<'_, AppState>,
    path: String,
    root: Option<String>,
    recursive: Option<bool>,
) -> Result<(), String> {
    api_from(&state)?
        .delete_server_file(&path, root.as_deref(), recursive.unwrap_or(false))
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
    api_from(&state)?
        .copy_server_path(&path, root.as_deref())
        .await
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
        .terminal_exec(&session_id, &command, cwd.as_deref(), root.as_deref())
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

/// `POST /api/files/create`：在指定目录下新建空文件或文件夹。
///
/// `parent` 为空表示当前存储根目录；`kind` 只接受 `file` / `directory`。
#[tauri::command]
pub async fn create_server_entry(
    state: State<'_, AppState>,
    parent: String,
    name: String,
    kind: String,
    root: Option<String>,
) -> Result<CreateResult, String> {
    api_from(&state)?
        .create_server_entry(&parent, &name, &kind, root.as_deref())
        .await
}

#[cfg(test)]
mod tests {
    use super::{ancestor_prefixes, preview_profile, saved_preview_profile};
    use crate::config::ServerProfile;

    /// 构造一条 `A(direct) → B → C …` 的测试链（纯内存，不发网络请求）。
    fn chain_fixture(len: usize) -> Vec<ServerProfile> {
        assert!(len >= 1);
        let mut chain = vec![ServerProfile::new("A", "http://a", "dev")];
        for index in 1..len {
            let parent_id = chain.last().unwrap().id.clone();
            chain.push(ServerProfile::new_relay(
                &format!("hop{index}"),
                &format!("http://hop{index}"),
                "dev",
                &format!("key{index}"),
                &format!("secret{index}"),
                &parent_id,
            ));
        }
        chain
    }

    #[test]
    fn preview_profile_normalizes_url_and_trims_credentials() {
        let profile = preview_profile(
            "  http://127.0.0.1:8080/  ",
            Some(" key "),
            Some(" secret "),
        )
        .unwrap();
        assert_eq!(profile.server_url, "http://127.0.0.1:8080");
        assert_eq!(profile.app_key, "key");
        assert_eq!(profile.app_secret, "secret");
        // 预览使用临时设备身份，不影响之后 save_server 保存的服务器。
        assert!(!profile.device_id.is_empty());
    }

    #[test]
    fn preview_profile_falls_back_to_builtin_credentials() {
        let profile = preview_profile("http://127.0.0.1:8080", None, Some("   ")).unwrap();
        assert_eq!(profile.effective_app_key(), crate::builtin::app_key());
        assert_eq!(profile.effective_app_secret(), crate::builtin::app_secret());
    }

    #[test]
    fn preview_profile_rejects_empty_or_invalid_url() {
        assert!(preview_profile("   ", None, None).is_err());
        assert!(preview_profile("ftp://127.0.0.1:8080", None, None).is_err());
    }

    /// 编辑预览沿用已保存的 id / 设备身份 / secret，只覆盖表单里的地址与 AppKey。
    #[test]
    fn saved_preview_profile_keeps_identity_and_saved_secret() {
        let mut config = crate::config::default_config();
        let server = config.require_active().unwrap().clone();
        config.servers[0].app_key = "saved-key".to_string();
        config.servers[0].app_secret = "saved-secret".to_string();

        let profile = saved_preview_profile(
            &config,
            &server.id,
            "  http://127.0.0.1:9090/  ",
            Some("new-key"),
            None,
        )
        .unwrap();
        assert_eq!(profile.id, server.id);
        assert_eq!(profile.device_id, server.device_id);
        assert_eq!(profile.device_name, server.device_name);
        assert_eq!(profile.server_url, "http://127.0.0.1:9090");
        assert_eq!(profile.app_key, "new-key");
        assert_eq!(profile.app_secret, "saved-secret");
    }

    /// 清空自定义 secret（`Some("")`）时预览回退内置值，与保存的语义一致。
    #[test]
    fn saved_preview_profile_clears_secret_for_builtin_fallback() {
        let mut config = crate::config::default_config();
        let server = config.require_active().unwrap().clone();
        config.servers[0].app_secret = "saved-secret".to_string();

        let profile = saved_preview_profile(
            &config,
            &server.id,
            "http://127.0.0.1:9090",
            None,
            Some("   "),
        )
        .unwrap();
        assert_eq!(profile.app_secret, "");
        assert_eq!(profile.effective_app_secret(), crate::builtin::app_secret());
    }

    #[test]
    fn saved_preview_profile_rejects_unknown_id_or_invalid_url() {
        let config = crate::config::default_config();
        assert!(
            saved_preview_profile(&config, "missing", "http://127.0.0.1:9090", None, None).is_err()
        );
        let id = config.require_active().unwrap().id.clone();
        assert!(saved_preview_profile(&config, &id, "   ", None, None).is_err());
        assert!(saved_preview_profile(&config, &id, "ftp://127.0.0.1:8080", None, None).is_err());
    }

    /// 空链 / 单跳 direct 不触发 preflight（不多余 activate）。
    #[test]
    fn ancestor_prefixes_skip_direct_and_empty_chain() {
        assert!(ancestor_prefixes(&[]).is_empty());
        assert!(ancestor_prefixes(&chain_fixture(1)).is_empty());
    }

    /// prefix 顺序：root → target 的上一跳，按 1 步递增。
    #[test]
    fn ancestor_prefixes_are_root_to_parent_in_order() {
        for len in 2..=4 {
            let chain = chain_fixture(len);
            let prefixes = ancestor_prefixes(&chain);
            assert_eq!(
                prefixes.len(),
                len - 1,
                "链长 {len} 应有 {} 个前缀",
                len - 1
            );
            for (index, prefix) in prefixes.iter().enumerate() {
                assert_eq!(prefix.len(), index + 1, "前缀应按 1 步递增");
                assert_eq!(prefix[0].id, chain[0].id, "每个前缀都从 root 开始");
                assert_eq!(
                    prefix.last().unwrap().id,
                    chain[index].id,
                    "第 {index} 个前缀的末跳是链上第 {index} 跳"
                );
                assert_eq!(prefix.last().unwrap().kind, chain[index].kind);
            }
        }
    }

    /// 最终 target 不能被放进 preflight（否则会在 verify_connection 里被 activate）。
    #[test]
    fn ancestor_prefixes_never_include_target() {
        let chain = chain_fixture(3);
        let target = chain.last().unwrap();
        let prefixes = ancestor_prefixes(&chain);
        assert!(
            prefixes
                .iter()
                .all(|prefix| prefix.iter().all(|hop| hop.id != target.id)),
            "preflight 不能包含最终 target"
        );
        // 单跳链（只有 root）也返回空，不做多余 activate。
        assert!(ancestor_prefixes(&chain[..1]).is_empty());
    }
}
