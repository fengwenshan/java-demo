//! 本地配置读写：`app_config_dir()/config.json`，权限 0600。
//!
//! 配置为 v2 多服务器格式：
//!
//! ```json
//! {
//!   "activeServerId": "uuid",
//!   "servers": [
//!     {
//!       "id": "uuid",
//!       "name": "开发环境",
//!       "serverUrl": "http://127.0.0.1:8080",
//!       "deviceId": "uuid",
//!       "deviceName": "我的 MacBook"
//!     }
//!   ]
//! }
//! ```
//!
//! - 每台服务器有独立的 `id` / `deviceId` / `deviceName`；
//! - AppKey / AppSecret 默认内置（见 [`crate::builtin`]）；每台服务器可选自定义，secret 落盘 0600、不返回前端；
//! - 旧版单配置 `{serverUrl, deviceId, deviceName}` 在读取时自动迁移为 v2。

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager};
use url::Url;
use uuid::Uuid;

use crate::builtin;

pub const CONFIG_FILE_NAME: &str = "config.json";

/// 设备名称默认值（首次启动 / 旧配置缺少 deviceName 时使用）。
pub const DEFAULT_DEVICE_NAME: &str = "我的设备";

/// 迁移旧配置 / 首次启动时生成的服务器名称。
pub const DEFAULT_SERVER_NAME: &str = "默认服务器";

/// 一台服务器的配置：每台服务器拥有独立的设备身份。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerProfile {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub server_url: String,
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
    /// 自定义 AppKey；空串表示使用内置默认值。属于私有配置，不返回给前端展示值以外的内容。
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub app_key: String,
    /// 自定义 AppSecret；空串表示使用内置默认值。落盘权限 0600，绝不返回前端。
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub app_secret: String,
}

impl ServerProfile {
    /// 新建一台服务器（生成 id 与 deviceId）。
    pub fn new(name: &str, server_url: &str, device_name: &str) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            name: name.to_string(),
            server_url: server_url.to_string(),
            device_id: Uuid::new_v4().to_string(),
            device_name: device_name.to_string(),
            app_key: String::new(),
            app_secret: String::new(),
        }
    }

    /// 生效的 AppKey：自定义值优先，否则使用内置默认值。
    pub fn effective_app_key(&self) -> &str {
        let custom = self.app_key.trim();
        if custom.is_empty() {
            builtin::app_key()
        } else {
            custom
        }
    }

    /// 生效的 AppSecret：自定义值优先，否则使用内置默认值。
    pub fn effective_app_secret(&self) -> &str {
        let custom = self.app_secret.trim();
        if custom.is_empty() {
            builtin::app_secret()
        } else {
            custom
        }
    }

    /// 是否配置了自定义 AppSecret（用于 UI 提示，不暴露值）。
    pub fn has_custom_secret(&self) -> bool {
        !self.app_secret.trim().is_empty()
    }

    /// 是否已配置：服务器地址非空（内置凭据始终满足）。
    pub fn is_configured(&self) -> bool {
        !self.server_url.trim().is_empty()
    }

    fn ensure_ids(&mut self) -> bool {
        let mut changed = false;
        if self.id.trim().is_empty() {
            self.id = Uuid::new_v4().to_string();
            changed = true;
        }
        if self.device_id.trim().is_empty() {
            self.device_id = Uuid::new_v4().to_string();
            changed = true;
        }
        changed
    }

    /// 返回给前端的视图（不含任何凭据）。
    pub fn view(&self) -> ServerView {
        ServerView {
            id: self.id.clone(),
            name: self.name.clone(),
            server_url: self.server_url.clone(),
            device_id: self.device_id.clone(),
            device_name: self.device_name.clone(),
            app_key: self.app_key.clone(),
            effective_app_key: self.effective_app_key().to_string(),
            has_custom_secret: self.has_custom_secret(),
            configured: self.is_configured(),
        }
    }
}

/// 持久化配置 v2。
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    #[serde(default)]
    pub active_server_id: String,
    #[serde(default)]
    pub servers: Vec<ServerProfile>,
}

/// 返回给前端的一台服务器视图。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerView {
    pub id: String,
    pub name: String,
    pub server_url: String,
    pub device_id: String,
    pub device_name: String,
    /// 自定义 AppKey（空串表示使用内置默认值）。
    pub app_key: String,
    /// 当前生效的 AppKey（自定义或内置）。
    pub effective_app_key: String,
    /// 是否配置了自定义 AppSecret（不返回 secret 值）。
    pub has_custom_secret: bool,
    pub configured: bool,
}

/// 返回给前端的配置视图：没有 `appSecret`，`appKey` 为当前服务器生效值，`hasSecret` 恒为 true。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigView {
    pub active_server_id: String,
    pub servers: Vec<ServerView>,
    pub app_key: String,
    pub has_secret: bool,
}

impl AppConfig {
    /// 当前激活的服务器（加载/规范化后保证存在）。
    pub fn active(&self) -> Option<&ServerProfile> {
        self.servers
            .iter()
            .find(|server| server.id == self.active_server_id)
            .or_else(|| self.servers.first())
    }

    /// 当前激活的服务器；不存在时返回可展示的中文错误。
    pub fn require_active(&self) -> Result<&ServerProfile, String> {
        self.active()
            .ok_or_else(|| "尚未配置服务器，请先在「设置」中添加服务器".to_string())
    }

    /// 按 id 查找服务器。
    pub fn find(&self, id: &str) -> Option<&ServerProfile> {
        self.servers.iter().find(|server| server.id == id.trim())
    }

    /// 前端视图：appKey 为当前生效值，不包含 appSecret。
    pub fn view(&self) -> ConfigView {
        ConfigView {
            active_server_id: self
                .active()
                .map(|server| server.id.clone())
                .unwrap_or_default(),
            servers: self.servers.iter().map(ServerProfile::view).collect(),
            app_key: self
                .active()
                .map(|server| server.effective_app_key().to_string())
                .unwrap_or_else(|| builtin::app_key().to_string()),
            has_secret: true,
        }
    }

    /// 新建 / 编辑一台服务器：
    /// - `id == None`：新建（生成 id 与 deviceId），并自动设为 active；
    /// - `id == Some`：编辑，保留原 id 与 deviceId；
    /// - 本方法不修改凭据；需要设置/清除自定义密钥时用 `save_server_with_credentials`。
    pub fn save_server(
        &mut self,
        id: Option<&str>,
        name: &str,
        server_url: &str,
        device_name: &str,
    ) -> Result<ServerProfile, String> {
        self.save_server_with_credentials(id, name, server_url, device_name, None, None)
    }

    /// 带可选凭据的保存：供 Tauri 命令使用。
    ///
    /// - `app_key == None`：编辑时保留原自定义 AppKey；新建时使用内置值；
    /// - `app_key == Some("")`：清除自定义 AppKey，回退内置值；
    /// - `app_secret == None`：编辑时保留原自定义 secret；新建时使用内置值；
    /// - `app_secret == Some("")`：清除自定义 secret，回退内置值。
    pub fn save_server_with_credentials(
        &mut self,
        id: Option<&str>,
        name: &str,
        server_url: &str,
        device_name: &str,
        app_key: Option<&str>,
        app_secret: Option<&str>,
    ) -> Result<ServerProfile, String> {
        let name = normalize_server_name(name)?;
        let server_url = normalize_server_url(server_url)?;
        if server_url.is_empty() {
            return Err("服务器地址不能为空".to_string());
        }
        let device_name = normalize_device_name(device_name)?;
        let app_key = match app_key {
            Some(value) => Some(normalize_credential(value, "AppKey", 128)?),
            None => None,
        };
        let app_secret = match app_secret {
            Some(value) => Some(normalize_credential(value, "AppSecret", 256)?),
            None => None,
        };

        if let Some(id) = id.map(str::trim).filter(|value| !value.is_empty()) {
            let server = self
                .servers
                .iter_mut()
                .find(|server| server.id == id)
                .ok_or_else(|| "服务器不存在，可能已被删除".to_string())?;
            server.name = name;
            server.server_url = server_url;
            server.device_name = device_name;
            if let Some(value) = &app_key {
                server.app_key = value.clone();
            }
            if let Some(value) = &app_secret {
                server.app_secret = value.clone();
            }
            server.ensure_ids();
            return Ok(server.clone());
        }

        let mut server = ServerProfile::new(&name, &server_url, &device_name);
        server.ensure_ids();
        if let Some(value) = app_key {
            server.app_key = value;
        }
        if let Some(value) = app_secret {
            server.app_secret = value;
        }
        self.servers.push(server.clone());
        // 新建的服务器直接切换为当前使用，用户添加后即可浏览/上传。
        self.active_server_id = server.id.clone();
        Ok(server)
    }

    /// 删除一台服务器：至少保留一台；删除 active 后自动切到其它服务器。
    pub fn delete_server(&mut self, id: &str) -> Result<(), String> {
        let id = id.trim();
        if id.is_empty() {
            return Err("缺少要删除的服务器 id".to_string());
        }
        if self.servers.len() <= 1 {
            return Err("至少需要保留一台服务器".to_string());
        }
        let position = self
            .servers
            .iter()
            .position(|server| server.id == id)
            .ok_or_else(|| "服务器不存在，可能已被删除".to_string())?;
        self.servers.remove(position);
        if self.active_server_id == id {
            self.active_server_id = self.servers[0].id.clone();
        }
        Ok(())
    }

    /// 切换当前服务器。
    pub fn set_active(&mut self, id: &str) -> Result<(), String> {
        let id = id.trim();
        if !self.servers.iter().any(|server| server.id == id) {
            return Err("服务器不存在，可能已被删除".to_string());
        }
        self.active_server_id = id.to_string();
        Ok(())
    }

    /// 补齐/修复配置：默认服务器、缺失的 id/deviceId、空地址、active 指向。
    /// 返回是否发生了修改（需要落盘）。
    fn normalize(&mut self) -> bool {
        let mut changed = false;

        if self.servers.is_empty() {
            self.servers.push(ServerProfile::new(
                DEFAULT_SERVER_NAME,
                builtin::default_server_url(),
                DEFAULT_DEVICE_NAME,
            ));
            changed = true;
        }

        for (index, server) in self.servers.iter_mut().enumerate() {
            if server.ensure_ids() {
                changed = true;
            }
            let trimmed_url = server.server_url.trim().trim_end_matches('/').to_string();
            if trimmed_url != server.server_url {
                server.server_url = trimmed_url;
                changed = true;
            }
            if server.server_url.is_empty() {
                server.server_url = builtin::default_server_url().to_string();
                changed = true;
            }
            let trimmed_name = server.name.trim().to_string();
            if trimmed_name.is_empty() {
                server.name = if index == 0 {
                    DEFAULT_SERVER_NAME.to_string()
                } else {
                    format!("服务器 {}", index + 1)
                };
                changed = true;
            } else if trimmed_name != server.name {
                server.name = trimmed_name;
                changed = true;
            }
            let trimmed_device = server.device_name.trim().to_string();
            if trimmed_device.is_empty() {
                server.device_name = DEFAULT_DEVICE_NAME.to_string();
                changed = true;
            } else if trimmed_device != server.device_name {
                server.device_name = trimmed_device;
                changed = true;
            }
            let trimmed_key = server.app_key.trim().to_string();
            if trimmed_key != server.app_key {
                server.app_key = trimmed_key;
                changed = true;
            }
            let trimmed_secret = server.app_secret.trim().to_string();
            if trimmed_secret != server.app_secret {
                server.app_secret = trimmed_secret;
                changed = true;
            }
        }

        let active_valid = self
            .servers
            .iter()
            .any(|server| server.id == self.active_server_id);
        if !active_valid {
            self.active_server_id = self.servers[0].id.clone();
            changed = true;
        }

        changed
    }
}

/// `app_config_dir()/config.json`。
pub fn config_path(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|dir| dir.join(CONFIG_FILE_NAME))
        .map_err(|e| format!("无法定位应用配置目录：{e}"))
}

/// 默认配置（内存态）：一台内置默认地址的服务器 + 新 deviceId。
///
/// 配置目录不可写等情况下作为内存兜底，保证界面仍可用（保存时会给出明确错误）。
pub fn default_config() -> AppConfig {
    let mut config = AppConfig::default();
    config.normalize();
    config
}

/// 从旧版单服务器配置（v1）迁移。
///
/// 旧格式：`{serverUrl, deviceId, deviceName, [appKey], [appSecret]}`。
fn migrate_v1(raw: &Value) -> AppConfig {
    let text = |key: &str| {
        raw.get(key)
            .and_then(Value::as_str)
            .unwrap_or_default()
            .trim()
            .to_string()
    };

    let mut profile = ServerProfile {
        id: Uuid::new_v4().to_string(),
        name: DEFAULT_SERVER_NAME.to_string(),
        server_url: text("serverUrl").trim_end_matches('/').to_string(),
        device_id: text("deviceId"),
        device_name: text("deviceName"),
        app_key: String::new(),
        app_secret: String::new(),
    };
    profile.ensure_ids();
    AppConfig {
        active_server_id: profile.id.clone(),
        servers: vec![profile],
    }
}

/// 读取配置；必要时补齐并落盘（0600）：
/// - 文件不存在 / 内容为空：创建默认服务器（内置默认地址 + 新 deviceId）
/// - 旧版单服务器格式：迁移为 v2 并持久化（同时清洗旧的 appKey / appSecret 字段）
/// - v2：补齐缺失的 id/deviceId、空 serverUrl、无效 activeServerId
pub fn load_or_init(path: &Path) -> Result<AppConfig, String> {
    let existed = path.exists();
    let mut needs_persist = !existed;
    let mut config = AppConfig::default();

    if existed {
        let content = std::fs::read_to_string(path).map_err(|e| format!("读取配置失败：{e}"))?;
        if content.trim().is_empty() {
            needs_persist = true;
        } else {
            let raw: Value =
                serde_json::from_str(&content).map_err(|e| format!("配置文件格式错误：{e}"))?;
            // 旧版本会在文件里写 appKey / appSecret：读取时忽略，并在本次落盘时自然移除。
            if raw.get("appKey").is_some() || raw.get("appSecret").is_some() {
                needs_persist = true;
            }
            if raw.get("servers").map(Value::is_array).unwrap_or(false) {
                config = serde_json::from_value(raw)
                    .map_err(|e| format!("配置文件格式错误：{e}"))?;
            } else {
                // v1 单服务器配置 → v2。
                config = migrate_v1(&raw);
                needs_persist = true;
            }
        }
    }

    if config.normalize() {
        needs_persist = true;
    }

    if needs_persist {
        persist(path, &config)?;
    }
    Ok(config)
}

/// 写入配置文件，Unix 下权限固定 0600（目录 0700）。
pub fn persist(path: &Path, config: &AppConfig) -> Result<(), String> {
    let json = serde_json::to_string_pretty(config).map_err(|e| format!("配置序列化失败：{e}"))?;

    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建配置目录失败：{e}"))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(parent, std::fs::Permissions::from_mode(0o700));
        }
    }

    #[cfg(unix)]
    {
        use std::io::Write;
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};

        let mut options = std::fs::OpenOptions::new();
        options.write(true).create(true).truncate(true).mode(0o600);
        let mut file = options
            .open(path)
            .map_err(|e| format!("写入配置失败：{e}"))?;
        file.write_all(json.as_bytes())
            .map_err(|e| format!("写入配置失败：{e}"))?;
        let _ = file.sync_all();
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
            .map_err(|e| format!("设置配置文件权限失败：{e}"))?;
    }

    #[cfg(not(unix))]
    {
        std::fs::write(path, json).map_err(|e| format!("写入配置失败：{e}"))?;
    }

    Ok(())
}

/// 校验可选凭据：允许空串（回退内置值），否则去首尾空白并限制长度。
fn normalize_credential(input: &str, label: &str, max_chars: usize) -> Result<String, String> {
    let value = input.trim();
    if value.chars().count() > max_chars {
        return Err(format!("{label}不能超过 {max_chars} 个字符"));
    }
    if value.contains('\n') || value.contains('\r') {
        return Err(format!("{label}不能包含换行"));
    }
    Ok(value.to_string())
}

/// 规范化并校验服务器地址：
/// - 保存时去掉末尾 `/`
/// - 必须是 `http(s)://host[:port][/反向代理子路径]`，不允许 query/fragment/用户信息
/// - 允许为空（保存接口会拒绝空值；加载时用内置默认地址填充）
pub fn normalize_server_url(input: &str) -> Result<String, String> {
    let trimmed = input.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Ok(String::new());
    }

    let parsed = Url::parse(trimmed)
        .map_err(|_| "服务器地址格式不正确，示例：http://127.0.0.1:8080".to_string())?;

    if !matches!(parsed.scheme(), "http" | "https") {
        return Err("服务器地址必须以 http:// 或 https:// 开头".to_string());
    }
    if parsed.host_str().is_none() {
        return Err("服务器地址缺少主机名".to_string());
    }
    if parsed.query().is_some() || parsed.fragment().is_some() {
        return Err("服务器地址不能包含查询参数或片段".to_string());
    }
    if !parsed.username().is_empty() || parsed.password().is_some() {
        return Err("服务器地址不能包含用户名或密码".to_string());
    }

    Ok(trimmed.to_string())
}

/// 校验设备名称：1~64 字符。
pub fn normalize_device_name(input: &str) -> Result<String, String> {
    let name = input.trim();
    if name.is_empty() {
        return Err("设备名称不能为空".to_string());
    }
    if name.chars().count() > 64 {
        return Err("设备名称不能超过 64 个字符".to_string());
    }
    Ok(name.to_string())
}

/// 校验服务器名称：1~64 字符。
pub fn normalize_server_name(input: &str) -> Result<String, String> {
    let name = input.trim();
    if name.is_empty() {
        return Err("服务器名称不能为空".to_string());
    }
    if name.chars().count() > 64 {
        return Err("服务器名称不能超过 64 个字符".to_string());
    }
    Ok(name.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "secure-upload-config-test-{}",
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_file(path: &Path, content: &str) {
        std::fs::write(path, content).unwrap();
    }

    #[test]
    fn normalizes_trailing_slash() {
        assert_eq!(
            normalize_server_url(" http://127.0.0.1:8080/ ").unwrap(),
            "http://127.0.0.1:8080"
        );
        assert_eq!(
            normalize_server_url("https://upload.example.com/").unwrap(),
            "https://upload.example.com"
        );
    }

    #[test]
    fn rejects_bad_server_url() {
        assert!(normalize_server_url("127.0.0.1:8080").is_err());
        assert!(normalize_server_url("ftp://127.0.0.1").is_err());
        assert!(normalize_server_url("http://127.0.0.1:8080?x=1").is_err());
        assert!(normalize_server_url("http://127.0.0.1:8080#frag").is_err());
        assert!(normalize_server_url("http://user:pass@127.0.0.1:8080").is_err());
    }

    #[test]
    fn allows_reverse_proxy_path() {
        assert_eq!(
            normalize_server_url("https://example.com/scopeftp/").unwrap(),
            "https://example.com/scopeftp"
        );
        assert_eq!(
            normalize_server_url("https://example.com/scopeftp").unwrap(),
            "https://example.com/scopeftp"
        );
    }

    #[test]
    fn name_limits() {
        assert!(normalize_device_name("").is_err());
        assert_eq!(
            normalize_device_name(" 我的 MacBook ").unwrap(),
            "我的 MacBook"
        );
        assert!(normalize_device_name(&"a".repeat(65)).is_err());

        assert!(normalize_server_name("  ").is_err());
        assert_eq!(normalize_server_name(" 开发环境 ").unwrap(), "开发环境");
        assert!(normalize_server_name(&"a".repeat(65)).is_err());
    }

    #[test]
    fn fresh_config_creates_default_server_and_persists() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);

        let config = load_or_init(&path).unwrap();
        assert!(path.exists(), "首次启动应落盘配置");
        assert_eq!(config.servers.len(), 1);
        let server = config.active().expect("应有默认服务器");
        assert_eq!(server.name, DEFAULT_SERVER_NAME);
        assert_eq!(server.server_url, builtin::default_server_url());
        assert!(!server.device_id.is_empty());
        assert_eq!(server.device_name, DEFAULT_DEVICE_NAME);
        assert_eq!(config.active_server_id, server.id);

        let again = load_or_init(&path).unwrap();
        assert_eq!(again.active_server_id, config.active_server_id);
        assert_eq!(again.servers[0].device_id, server.device_id, "deviceId 必须持久化");

        // 不能落盘凭据
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(!content.contains("appKey"));
        assert!(!content.contains("appSecret"));
        assert!(!content.contains(builtin::app_secret()));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn legacy_single_server_config_is_migrated_to_v2() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        write_file(
            &path,
            r#"{
  "serverUrl": "http://127.0.0.1:9999/",
  "appKey": "legacy-key",
  "appSecret": "legacy-secret-should-be-removed",
  "deviceId": "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "deviceName": "旧设备"
}"#,
        );

        let config = load_or_init(&path).unwrap();
        assert_eq!(config.servers.len(), 1, "旧配置应迁移为一台服务器");
        let server = config.active().unwrap();
        assert_eq!(server.name, DEFAULT_SERVER_NAME);
        assert_eq!(server.server_url, "http://127.0.0.1:9999");
        assert_eq!(server.device_id, "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
        assert_eq!(server.device_name, "旧设备");
        assert!(!server.id.is_empty());
        assert_eq!(config.active_server_id, server.id);

        // 迁移后必须写成 v2 结构，且清洗凭据字段
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("\"activeServerId\""));
        assert!(content.contains("\"servers\""));
        assert!(!content.contains("legacy-key"));
        assert!(!content.contains("legacy-secret-should-be-removed"));
        assert!(!content.contains("\"appKey\""));
        assert!(!content.contains("\"appSecret\""));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600, "配置文件权限必须是 0600");
        }

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn empty_server_url_and_invalid_active_are_repaired() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        write_file(
            &path,
            r#"{
  "activeServerId": "missing-id",
  "servers": [
    {"id": "s1", "name": "", "serverUrl": "", "deviceId": "", "deviceName": ""},
    {"id": "s2", "name": "测试", "serverUrl": "http://127.0.0.1:9000", "deviceId": "d2", "deviceName": "测试机"}
  ]
}"#,
        );

        let config = load_or_init(&path).unwrap();
        assert_eq!(config.servers.len(), 2);
        assert_eq!(config.servers[0].server_url, builtin::default_server_url());
        assert!(!config.servers[0].device_id.is_empty());
        assert_eq!(config.servers[0].name, DEFAULT_SERVER_NAME);
        assert_eq!(config.servers[0].device_name, DEFAULT_DEVICE_NAME);
        assert_eq!(config.active_server_id, "s1", "无效 active 应回退到第一台");

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn save_server_creates_new_active_and_edits_preserve_device_id() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        let mut config = load_or_init(&path).unwrap();
        let first_id = config.servers[0].id.clone();
        let first_device = config.servers[0].device_id.clone();

        let created = config
            .save_server(None, "测试环境", "http://127.0.0.1:9000/", "测试设备")
            .unwrap();
        assert_ne!(created.id, first_id);
        assert_eq!(created.server_url, "http://127.0.0.1:9000", "应去掉末尾 /");
        assert!(!created.device_id.is_empty());
        assert_eq!(config.servers.len(), 2);
        assert_eq!(config.active_server_id, created.id, "新建后应自动激活");

        // 编辑：保留 id 与 deviceId
        let edited = config
            .save_server(
                Some(&created.id),
                "测试环境-改",
                "https://example.com",
                "测试设备-改",
            )
            .unwrap();
        assert_eq!(edited.id, created.id);
        assert_eq!(edited.device_id, created.device_id);
        assert_eq!(edited.name, "测试环境-改");
        assert_eq!(edited.device_name, "测试设备-改");

        // 编辑第一台不应改变其 deviceId
        let edited_first = config
            .save_server(Some(&first_id), "默认-改", "http://127.0.0.1:8080", "设备")
            .unwrap();
        assert_eq!(edited_first.device_id, first_device);

        // 校验失败不留脏数据
        assert!(config
            .save_server(None, "", "http://127.0.0.1:8080", "设备")
            .is_err());
        assert!(config
            .save_server(None, "x", "not-a-url", "设备")
            .is_err());
        assert!(config
            .save_server(None, "x", "http://127.0.0.1:8080", "")
            .is_err());
        assert_eq!(config.servers.len(), 2);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn delete_server_keeps_at_least_one_and_switches_active() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        let mut config = load_or_init(&path).unwrap();
        let first_id = config.servers[0].id.clone();
        let second = config
            .save_server(None, "第二台", "http://127.0.0.1:9000", "设备")
            .unwrap();

        // 删除 active（第二台）→ 自动切到剩下的第一台
        config.delete_server(&second.id).unwrap();
        assert_eq!(config.servers.len(), 1);
        assert_eq!(config.active_server_id, first_id);

        // 只剩一台时不允许删除
        assert!(config.delete_server(&first_id).is_err());
        assert_eq!(config.servers.len(), 1);
        assert!(config.delete_server("不存在的-id").is_err());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn set_active_validates_id() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        let mut config = load_or_init(&path).unwrap();
        let first_id = config.servers[0].id.clone();
        let second = config
            .save_server(None, "第二台", "http://127.0.0.1:9000", "设备")
            .unwrap();

        config.set_active(&first_id).unwrap();
        assert_eq!(config.active_server_id, first_id);
        config.set_active(&second.id).unwrap();
        assert_eq!(config.active_server_id, second.id);
        assert!(config.set_active("missing").is_err());
        assert_eq!(config.active_server_id, second.id, "失败的切换不应生效");

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn view_has_servers_and_no_secret() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        let mut config = load_or_init(&path).unwrap();
        let created = config
            .save_server(None, "测试", "http://127.0.0.1:9000", "设备")
            .unwrap();

        let view = config.view();
        assert_eq!(view.app_key, builtin::app_key());
        assert!(view.has_secret);
        assert_eq!(view.servers.len(), 2);
        assert_eq!(view.active_server_id, created.id);
        let server = view
            .servers
            .iter()
            .find(|server| server.id == created.id)
            .unwrap();
        assert_eq!(server.name, "测试");
        assert_eq!(server.server_url, "http://127.0.0.1:9000");
        assert!(server.configured);

        let json = serde_json::to_string(&view).unwrap();
        assert!(json.contains("\"activeServerId\""));
        assert!(json.contains("\"servers\""));
        assert!(json.contains("\"appKey\""));
        assert!(json.contains("\"hasSecret\":true"));
        assert!(!json.contains("appSecret"), "视图不能包含 appSecret 字段");
        assert!(
            !json.contains(builtin::app_secret()),
            "视图不能包含内置 secret 值"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn custom_credentials_override_builtin_and_can_be_cleared() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        let mut config = load_or_init(&path).unwrap();

        let created = config
            .save_server_with_credentials(
                None,
                "自定义密钥",
                "http://127.0.0.1:9000",
                "设备",
                Some("my-app"),
                Some("my-secret-0123456789"),
            )
            .unwrap();
        assert_eq!(created.effective_app_key(), "my-app");
        assert_eq!(created.effective_app_secret(), "my-secret-0123456789");
        assert!(created.has_custom_secret());

        let view = config.view();
        assert_eq!(view.app_key, "my-app");
        let server_view = view
            .servers
            .iter()
            .find(|server| server.id == created.id)
            .unwrap();
        assert_eq!(server_view.app_key, "my-app");
        assert_eq!(server_view.effective_app_key, "my-app");
        assert!(server_view.has_custom_secret);
        assert!(
            !serde_json::to_string(&view).unwrap().contains("my-secret-0123456789"),
            "视图不能包含自定义 secret"
        );

        persist(&path, &config).unwrap();
        let loaded = load_or_init(&path).unwrap();
        let loaded_server = loaded.find(&created.id).unwrap();
        assert_eq!(loaded_server.effective_app_secret(), "my-secret-0123456789");

        // 编辑时 secret 传 None：保留原 secret；appKey 传空：回退内置值。
        let edited = config
            .save_server_with_credentials(
                Some(&created.id),
                "自定义密钥",
                "http://127.0.0.1:9000",
                "设备",
                Some(""),
                None,
            )
            .unwrap();
        assert_eq!(edited.effective_app_key(), builtin::app_key());
        assert_eq!(edited.effective_app_secret(), "my-secret-0123456789");

        // AppSecret 传空串：只清除自定义 secret，AppKey 仍可保留自定义值。
        let secret_cleared = config
            .save_server_with_credentials(
                Some(&created.id),
                "自定义密钥",
                "http://127.0.0.1:9000",
                "设备",
                Some("my-app"),
                Some(""),
            )
            .unwrap();
        assert_eq!(secret_cleared.effective_app_key(), "my-app");
        assert_eq!(secret_cleared.effective_app_secret(), builtin::app_secret());
        assert!(!secret_cleared.has_custom_secret());

        // 两项都传空串：都回退内置默认值。
        let cleared = config
            .save_server_with_credentials(
                Some(&created.id),
                "自定义密钥",
                "http://127.0.0.1:9000",
                "设备",
                Some(""),
                Some(""),
            )
            .unwrap();
        assert_eq!(cleared.effective_app_key(), builtin::app_key());
        assert_eq!(cleared.effective_app_secret(), builtin::app_secret());
        assert!(!cleared.has_custom_secret());

        let _ = std::fs::remove_dir_all(&dir);
    }
}
