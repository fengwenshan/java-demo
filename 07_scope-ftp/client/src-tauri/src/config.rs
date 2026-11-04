//! 本地配置读写：`app_config_dir()/config.json`，权限 0600。
//!
//! 配置为 v3 多服务器树形格式（转发链）：
//!
//! ```json
//! {
//!   "configVersion": 3,
//!   "activeServerId": "uuid",
//!   "servers": [
//!     {
//!       "id": "uuid",
//!       "kind": "direct",
//!       "name": "开发环境",
//!       "serverUrl": "http://127.0.0.1:8080",
//!       "deviceId": "uuid",
//!       "deviceName": "我的 MacBook"
//!     },
//!     {
//!       "id": "uuid",
//!       "kind": "relay",
//!       "parentId": "uuid",
//!       "name": "B",
//!       "serverUrl": "http://10.0.0.5:8080",
//!       "deviceId": "uuid",
//!       "deviceName": "我的 MacBook"
//!     }
//!   ]
//! }
//! ```
//!
//! - 每台服务器有独立的 `id` / `deviceId` / `deviceName`；
//! - `kind=direct` 是直连服务器，`kind=relay` 的 `parentId` 指向上一跳，形成 `A → B → C` 的树；
//! - AppKey / AppSecret 默认内置（见 [`crate::builtin`]）；relay 必须显式填写，不回退内置值；
//! - secret 落盘 0600；常规视图不返回，仅设置页点击密码框眼睛时按 id 读取自定义值；
//! - 旧版单配置（v1）与 v2 平铺列表在读取时自动迁移为 v3（全部按 direct 处理）。

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

/// 当前配置版本：v3 引入 `kind` / `parentId` 树形转发链。
pub const CONFIG_VERSION: u32 = 3;

/// 转发链客户端安全上限（防止异常配置导致死循环；产品层不设层级限制）。
const MAX_CHAIN_HOPS: usize = 64;

/// 服务器类型：直连（direct）或代理服务器（relay）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ServerKind {
    Direct,
    Relay,
}

impl Default for ServerKind {
    fn default() -> Self {
        Self::Direct
    }
}

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
    /// 自定义 AppSecret；空串表示使用内置默认值。落盘权限 0600；
    /// 常规视图不返回，仅设置页点击密码框眼睛时按 id 读取自定义值。
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub app_secret: String,
    /// 服务器类型：direct（直连）或 relay（通过上一跳转发访问）。
    /// 缺省 direct，兼容 v2 配置。
    #[serde(default)]
    pub kind: ServerKind,
    /// relay 的上一跳服务器 id；direct 为空。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
}

impl ServerProfile {
    /// 新建一台直连服务器（生成 id 与 deviceId）。
    pub fn new(name: &str, server_url: &str, device_name: &str) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            name: name.to_string(),
            server_url: server_url.to_string(),
            device_id: Uuid::new_v4().to_string(),
            device_name: device_name.to_string(),
            app_key: String::new(),
            app_secret: String::new(),
            kind: ServerKind::Direct,
            parent_id: None,
        }
    }

    /// 新建一台代理服务器（生成独立 id 与 deviceId，凭据必填）。
    pub fn new_relay(
        name: &str,
        server_url: &str,
        device_name: &str,
        app_key: &str,
        app_secret: &str,
        parent_id: &str,
    ) -> Self {
        let mut profile = Self::new(name, server_url, device_name);
        profile.kind = ServerKind::Relay;
        profile.parent_id = Some(parent_id.to_string());
        profile.app_key = app_key.to_string();
        profile.app_secret = app_secret.to_string();
        profile
    }

    /// 生效的 AppKey：direct 自定义值优先，否则使用内置默认值；relay 必须显式填写。
    pub fn effective_app_key(&self) -> &str {
        if self.kind == ServerKind::Relay {
            return self.app_key.trim();
        }
        let custom = self.app_key.trim();
        if custom.is_empty() {
            builtin::app_key()
        } else {
            custom
        }
    }

    /// 生效的 AppSecret：direct 自定义值优先，否则使用内置默认值；relay 必须显式填写。
    pub fn effective_app_secret(&self) -> &str {
        if self.kind == ServerKind::Relay {
            return self.app_secret.trim();
        }
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

    /// 是否已配置：地址非空；relay 还要求 AppKey / AppSecret 都非空（不回退内置值）。
    pub fn is_configured(&self) -> bool {
        if self.server_url.trim().is_empty() {
            return false;
        }
        if self.kind == ServerKind::Relay {
            return !self.app_key.trim().is_empty() && !self.app_secret.trim().is_empty();
        }
        true
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
            kind: self.kind,
            parent_id: self.parent_id.clone(),
            parent_name: None,
            chain_path: Vec::new(),
            chain_hops: Vec::new(),
            root_id: self.id.clone(),
            root_name: self.name.clone(),
        }
    }
}

/// 持久化配置 v3（树形转发链）。
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    /// 配置版本：v2 文件缺省为 0，加载时迁移为 3。
    #[serde(default)]
    pub config_version: u32,
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
    /// 服务器类型：direct / relay。
    pub kind: ServerKind,
    /// relay 的上一跳 id。
    pub parent_id: Option<String>,
    /// relay 的上一跳名称。
    pub parent_name: Option<String>,
    /// 从根到当前服务器的链名称：`["A", "B", "C"]`。
    pub chain_path: Vec<String>,
    /// 从根到当前服务器的链（可点击切换）。
    pub chain_hops: Vec<ChainHop>,
    /// 链的根（直连服务器）id / 名称。
    pub root_id: String,
    pub root_name: String,
}

/// 转发链上的一跳（仅用于前端展示/点击切换）。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChainHop {
    pub id: String,
    pub name: String,
}

/// 返回给前端的配置视图：没有 `appSecret`，`appKey` 为当前服务器生效值。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigView {
    pub config_version: u32,
    /// 当前激活服务器；空配置时为 `null`。
    pub active_server: Option<ServerView>,
    pub active_server_id: String,
    pub servers: Vec<ServerView>,
    pub app_key: String,
    pub has_secret: bool,
}

impl AppConfig {
    /// 当前激活的服务器（active 无效时回退到第一台；空配置返回 None）。
    pub fn active(&self) -> Option<&ServerProfile> {
        self.servers
            .iter()
            .find(|server| server.id == self.active_server_id)
            .or_else(|| self.servers.first())
    }

    /// 当前激活的服务器；不存在时返回可展示的中文错误。
    pub fn require_active(&self) -> Result<&ServerProfile, String> {
        self.active()
            .ok_or_else(|| "请先添加服务器，再使用浏览/上传等功能".to_string())
    }

    /// 按 id 查找服务器。
    pub fn find(&self, id: &str) -> Option<&ServerProfile> {
        self.servers.iter().find(|server| server.id == id.trim())
    }

    /// 解析从根到 `id` 的转发链（顺序 root → target）：
    /// - 按 `parentId` 回溯并反转；
    /// - 校验父存在、无环、层级不超过 [`MAX_CHAIN_HOPS`]、根必须是 direct。
    pub fn resolve_chain(&self, id: &str) -> Result<Vec<&ServerProfile>, String> {
        let mut chain = Vec::new();
        let mut seen = std::collections::HashSet::new();
        let mut current = self
            .find(id)
            .ok_or_else(|| "服务器不存在，可能已被删除".to_string())?;
        loop {
            if !seen.insert(current.id.clone()) {
                return Err("转发链存在环，请在设置中修复该服务器".to_string());
            }
            chain.push(current);
            if chain.len() > MAX_CHAIN_HOPS {
                return Err("转发层级过深，请检查代理服务器配置".to_string());
            }
            let Some(parent_id) = current.parent_id.as_deref() else {
                break;
            };
            current = self
                .find(parent_id)
                .ok_or_else(|| "上级服务器不存在，请先修复配置".to_string())?;
        }
        chain.reverse();
        if chain
            .first()
            .map(|server| server.kind != ServerKind::Direct)
            .unwrap_or(false)
        {
            return Err("转发链的根必须是直连服务器".to_string());
        }
        Ok(chain)
    }

    /// 前端视图：appKey 为当前生效值，不包含 appSecret；每台服务器带上链信息。
    pub fn view(&self) -> ConfigView {
        let mut views = Vec::with_capacity(self.servers.len());
        for server in &self.servers {
            let mut view = server.view();
            match self.resolve_chain(&server.id) {
                Ok(chain) => {
                    view.parent_name = server
                        .parent_id
                        .as_deref()
                        .and_then(|parent| self.find(parent))
                        .map(|parent| parent.name.clone());
                    view.chain_path = chain.iter().map(|hop| hop.name.clone()).collect();
                    view.chain_hops = chain
                        .iter()
                        .map(|hop| ChainHop {
                            id: hop.id.clone(),
                            name: hop.name.clone(),
                        })
                        .collect();
                    view.root_id = chain.first().map(|hop| hop.id.clone()).unwrap_or_default();
                    view.root_name = chain
                        .first()
                        .map(|hop| hop.name.clone())
                        .unwrap_or_default();
                    // configured 需要整条链的地址和凭据都有效。
                    view.configured =
                        view.configured && chain.iter().all(|hop| hop.is_configured());
                }
                Err(_) => {
                    // 父不存在 / 环 / 层级过深：该服务器标记为未配置，等待用户修复。
                    view.configured = false;
                }
            }
            views.push(view);
        }
        let active = self
            .active()
            .and_then(|server| views.iter().find(|view| view.id == server.id).cloned());
        ConfigView {
            config_version: self.config_version,
            active_server: active,
            active_server_id: self
                .active()
                .map(|server| server.id.clone())
                .unwrap_or_default(),
            servers: views,
            app_key: self
                .active()
                .map(|server| server.effective_app_key().to_string())
                .unwrap_or_default(),
            has_secret: self
                .active()
                .map(|server| !server.effective_app_secret().trim().is_empty())
                .unwrap_or(false),
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

    /// 带可选凭据的保存：供 Tauri 命令使用（IPC 层会先调用
    /// [`validate_required_credentials`] 强制 AppKey/AppSecret 必填）。
    ///
    /// 本方法保留旧配置迁移所需的宽松语义：
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
            if server.kind != ServerKind::Direct {
                return Err("该服务器是代理服务器，请通过代理服务器设置保存".to_string());
            }
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

    /// 新建 / 编辑一个代理服务器（relay）：
    /// - `id == None`：新建（生成 id 与 deviceId），并自动设为 active；
    /// - `id == Some`：编辑，保留原 id 与 deviceId；
    /// - `app_secret == None` 表示沿用已保存的 secret；新建或原有 secret 为空时必填；
    /// - 校验上级服务器存在、凭据非空、不会形成环；失败时回滚到调用前的配置。
    pub fn save_relay(
        &mut self,
        id: Option<&str>,
        parent_id: &str,
        name: &str,
        server_url: &str,
        device_name: &str,
        app_key: &str,
        app_secret: Option<&str>,
    ) -> Result<ServerProfile, String> {
        let snapshot = self.clone();
        let parent = self
            .find(parent_id)
            .ok_or_else(|| "上级服务器不存在".to_string())?;
        if parent.id.is_empty() {
            return Err("上级服务器无效".to_string());
        }
        let parent_id = parent.id.clone();
        let name = normalize_server_name(name)?;
        let server_url = normalize_server_url(server_url)?;
        if server_url.is_empty() {
            return Err("服务器地址不能为空".to_string());
        }
        let device_name = normalize_device_name(device_name)?;
        let app_key = normalize_credential(app_key, "AppKey", 128)?;
        if app_key.is_empty() {
            return Err("代理服务器 AppKey 必填".to_string());
        }
        let app_secret = match app_secret {
            Some(value) => Some(normalize_credential(value, "AppSecret", 256)?),
            None => None,
        };

        let mut relay = if let Some(id) = id.map(str::trim).filter(|value| !value.is_empty()) {
            let server = self
                .servers
                .iter()
                .find(|server| server.id == id)
                .ok_or_else(|| "服务器不存在，可能已被删除".to_string())?
                .clone();
            if server.kind != ServerKind::Relay {
                return Err("该服务器不是代理服务器".to_string());
            }
            server
        } else {
            ServerProfile::new_relay(
                &name,
                &server_url,
                &device_name,
                &app_key,
                app_secret.as_deref().unwrap_or_default(),
                &parent_id,
            )
        };
        relay.kind = ServerKind::Relay;
        relay.parent_id = Some(parent_id.clone());
        relay.name = name;
        relay.server_url = server_url;
        relay.device_name = device_name;
        relay.app_key = app_key;
        if let Some(secret) = app_secret {
            if !secret.is_empty() {
                relay.app_secret = secret;
            }
        }
        if relay.app_secret.trim().is_empty() {
            return Err("代理服务器 AppSecret 必填".to_string());
        }
        relay.ensure_ids();

        if let Some(index) = self.servers.iter().position(|server| server.id == relay.id) {
            self.servers[index] = relay.clone();
        } else {
            self.servers.push(relay.clone());
            self.active_server_id = relay.id.clone();
        }
        // 用 resolve_chain 验证父存在 / 环 / 根类型；失败则回滚到调用前的克隆。
        if let Err(error) = self.resolve_chain(&relay.id) {
            *self = snapshot;
            return Err(error);
        }
        Ok(relay)
    }

    /// 删除一台服务器及其所有后代（级联）：
    /// - 返回被删除的所有 id（含自身与子树），供调用方清理续传状态；
    /// - 允许删到空配置（不再保留「至少一台」保护）；
    /// - active 被删时回退到父级，没有父级则回退到剩余的直连服务器/第一台，空则清空。
    pub fn delete_server(&mut self, id: &str) -> Result<Vec<String>, String> {
        let id = id.trim();
        if id.is_empty() {
            return Err("缺少要删除的服务器 id".to_string());
        }
        if self.find(id).is_none() {
            return Err("服务器不存在，可能已被删除".to_string());
        }
        let mut doomed = std::collections::HashSet::new();
        let mut stack = vec![id.to_string()];
        while let Some(current) = stack.pop() {
            if !doomed.insert(current.clone()) {
                continue;
            }
            for server in &self.servers {
                if server.parent_id.as_deref() == Some(current.as_str()) {
                    stack.push(server.id.clone());
                }
            }
        }
        let fallback = self
            .find(id)
            .and_then(|server| server.parent_id.clone())
            .filter(|parent| !doomed.contains(parent))
            .or_else(|| {
                self.servers
                    .iter()
                    .find(|server| {
                        server.kind == ServerKind::Direct && !doomed.contains(&server.id)
                    })
                    .map(|server| server.id.clone())
            })
            .unwrap_or_default();
        self.servers.retain(|server| !doomed.contains(&server.id));
        if doomed.contains(&self.active_server_id) {
            self.active_server_id = fallback;
        }
        Ok(doomed.into_iter().collect())
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

    /// 补齐/修复配置：缺失的 id/deviceId、空地址、parent 清洗、active 指向、configVersion。
    ///
    /// 注意：**不再自动补默认服务器**——`servers=[]` 时保持空配置，否则删完重启又会
    /// 自动出现一台默认服务器。首次安装的默认服务器由 [`load_or_init`] 显式生成。
    ///
    /// 返回是否发生了修改（需要落盘）。
    fn normalize(&mut self) -> bool {
        let mut changed = false;

        for (index, server) in self.servers.iter_mut().enumerate() {
            if server.ensure_ids() {
                changed = true;
            }
            // 清洗 parentId：不能指向自己；direct 不允许有 parent。
            if server.parent_id.as_deref() == Some(server.id.as_str()) {
                server.parent_id = None;
                changed = true;
            }
            if server.kind == ServerKind::Direct && server.parent_id.is_some() {
                server.parent_id = None;
                changed = true;
            }
            let trimmed_url = server.server_url.trim().trim_end_matches('/').to_string();
            if trimmed_url != server.server_url {
                server.server_url = trimmed_url;
                changed = true;
            }
            if server.server_url.is_empty() && server.kind == ServerKind::Direct {
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

        if self.servers.is_empty() {
            if !self.active_server_id.is_empty() {
                self.active_server_id.clear();
                changed = true;
            }
        } else if !self
            .servers
            .iter()
            .any(|server| server.id == self.active_server_id)
        {
            self.active_server_id = self
                .servers
                .iter()
                .find(|server| server.kind == ServerKind::Direct)
                .map(|server| server.id.clone())
                .unwrap_or_else(|| self.servers[0].id.clone());
            changed = true;
        }

        if self.config_version < CONFIG_VERSION {
            self.config_version = CONFIG_VERSION;
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

/// 默认配置（内存态 / 首次安装）：显式创建一台内置默认地址的 direct 服务器 + 新 deviceId。
///
/// 注意：只有「配置文件不存在 / 内容为空」或内存兜底时才调用本函数；
/// [`AppConfig::normalize`] 不会自动补默认服务器，空配置会保持为空。
pub fn default_config() -> AppConfig {
    let mut config = AppConfig {
        config_version: CONFIG_VERSION,
        ..AppConfig::default()
    };
    let server = ServerProfile::new(
        DEFAULT_SERVER_NAME,
        builtin::default_server_url(),
        DEFAULT_DEVICE_NAME,
    );
    config.active_server_id = server.id.clone();
    config.servers.push(server);
    config.normalize();
    config
}

/// 从旧版单服务器配置（v1）迁移为 v3 单服务器配置。
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
        kind: ServerKind::Direct,
        parent_id: None,
    };
    profile.ensure_ids();
    AppConfig {
        config_version: CONFIG_VERSION,
        active_server_id: profile.id.clone(),
        servers: vec![profile],
    }
}

/// 读取配置；必要时补齐并落盘（0600）：
/// - 文件不存在 / 内容为空（首次安装）：创建默认 direct 服务器；
/// - 旧版单服务器格式（v1）：迁移为 v3 并持久化（同时清洗旧的 appKey / appSecret 字段）；
/// - v2 平铺列表（无 `configVersion`）：补 `configVersion=3`，所有服务器 `kind=direct`、
///   `parentId=null`，迁移后立即持久化；
/// - v3：补齐缺失的 id/deviceId、空 serverUrl、无效 activeServerId；
/// - 配置文件存在但 `servers=[]`：保持空配置，**不自动补默认服务器**。
pub fn load_or_init(path: &Path) -> Result<AppConfig, String> {
    let existed = path.exists();
    let mut needs_persist = !existed;
    let mut config;

    if existed {
        let content = std::fs::read_to_string(path).map_err(|e| format!("读取配置失败：{e}"))?;
        if content.trim().is_empty() {
            // 空文件视为首次安装：生成默认 direct 服务器。
            config = default_config();
            needs_persist = true;
        } else {
            let raw: Value =
                serde_json::from_str(&content).map_err(|e| format!("配置文件格式错误：{e}"))?;
            // 旧版本会在文件里写 appKey / appSecret：读取时忽略，并在本次落盘时自然移除。
            if raw.get("appKey").is_some() || raw.get("appSecret").is_some() {
                needs_persist = true;
            }
            if raw.get("servers").map(Value::is_array).unwrap_or(false) {
                config =
                    serde_json::from_value(raw).map_err(|e| format!("配置文件格式错误：{e}"))?;
                if config.config_version < CONFIG_VERSION {
                    // v2 → v3：所有服务器按直连处理，parent 清空；迁移后立即持久化。
                    config.config_version = CONFIG_VERSION;
                    for server in &mut config.servers {
                        server.kind = ServerKind::Direct;
                        server.parent_id = None;
                    }
                    needs_persist = true;
                }
            } else {
                // v1 单服务器配置 → v3。
                config = migrate_v1(&raw);
                needs_persist = true;
            }
        }
    } else {
        config = default_config();
        needs_persist = true;
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

/// 规范化并校验可选凭据：允许空串（旧配置迁移用），否则去首尾空白并限制长度。
/// 新保存路径由 [`validate_required_credentials`] 保证非空。
/// 保存服务器时的必填凭证校验：服务端启动时必须显式指定 AppKey/AppSecret，
/// 客户端不再回退内置默认值（Tauri IPC 保存前调用）。
///
/// - 新建（`id` 为空）：AppKey 与 AppSecret 都必须非空；
/// - 编辑：AppKey 必填；`app_secret == None` 表示沿用已保存的 secret。
pub fn validate_required_credentials(
    id: Option<&str>,
    app_key: Option<&str>,
    app_secret: Option<&str>,
) -> Result<(), String> {
    let is_new = id
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .is_none();
    let missing_app_key = app_key
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .is_none();
    if missing_app_key {
        return Err("AppKey 必填：请填写与服务端启动时一致的 AppKey".to_string());
    }
    match app_secret.map(str::trim) {
        Some("") => Err("AppSecret 必填：请填写与服务端启动时一致的 AppSecret".to_string()),
        None if is_new => Err("AppSecret 必填：请填写与服务端启动时一致的 AppSecret".to_string()),
        _ => Ok(()),
    }
}

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
        assert_eq!(
            again.servers[0].device_id, server.device_id,
            "deviceId 必须持久化"
        );

        // 不能落盘凭据
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(!content.contains("appKey"));
        assert!(!content.contains("appSecret"));
        assert!(!content.contains(builtin::app_secret()));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn legacy_single_server_config_is_migrated_to_v3() {
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
        assert_eq!(config.config_version, CONFIG_VERSION);
        let server = config.active().unwrap();
        assert_eq!(server.name, DEFAULT_SERVER_NAME);
        assert_eq!(server.server_url, "http://127.0.0.1:9999");
        assert_eq!(server.device_id, "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
        assert_eq!(server.device_name, "旧设备");
        assert_eq!(server.kind, ServerKind::Direct);
        assert!(server.parent_id.is_none());
        assert!(!server.id.is_empty());
        assert_eq!(config.active_server_id, server.id);

        // 迁移后必须写成 v3 结构，且清洗凭据字段
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("\"configVersion\": 3"));
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
        assert!(config.save_server(None, "x", "not-a-url", "设备").is_err());
        assert!(config
            .save_server(None, "x", "http://127.0.0.1:8080", "")
            .is_err());
        assert_eq!(config.servers.len(), 2);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn delete_server_keeps_remaining_and_switches_active() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        let mut config = load_or_init(&path).unwrap();
        let first_id = config.servers[0].id.clone();
        let second = config
            .save_server(None, "第二台", "http://127.0.0.1:9000", "设备")
            .unwrap();

        // 删除 active（第二台）→ 自动切到剩下的第一台
        let deleted = config.delete_server(&second.id).unwrap();
        assert_eq!(deleted, vec![second.id.clone()]);
        assert_eq!(config.servers.len(), 1);
        assert_eq!(config.active_server_id, first_id);

        // 允许删到空配置；active 清空
        let deleted = config.delete_server(&first_id).unwrap();
        assert_eq!(deleted, vec![first_id]);
        assert!(config.servers.is_empty());
        assert!(config.active_server_id.is_empty());
        assert!(config.view().active_server.is_none());
        assert!(config.require_active().is_err());
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
            !serde_json::to_string(&view)
                .unwrap()
                .contains("my-secret-0123456789"),
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

    #[test]
    fn save_requires_app_key_and_app_secret() {
        // 新建服务器：AppKey / AppSecret 都必须填写（服务端启动时必须指定，没有内置默认值）。
        assert!(validate_required_credentials(None, None, None).is_err());
        assert!(validate_required_credentials(None, Some("   "), Some("secret")).is_err());
        assert!(validate_required_credentials(None, Some("key"), None).is_err());
        assert!(validate_required_credentials(None, Some("key"), Some(" ")).is_err());
        assert!(validate_required_credentials(None, Some("key"), Some("secret")).is_ok());

        // 编辑服务器：AppKey 必填；AppSecret 传 None 表示沿用已保存值。
        assert!(validate_required_credentials(Some("s1"), None, None).is_err());
        assert!(validate_required_credentials(Some("s1"), Some("key"), None).is_ok());
        assert!(validate_required_credentials(Some("s1"), Some("key"), Some("  ")).is_err());
        assert!(validate_required_credentials(Some("s1"), Some("key"), Some("secret")).is_ok());
    }

    #[test]
    fn v2_config_migrates_to_v3_direct_servers() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        write_file(
            &path,
            r#"{"activeServerId":"a","servers":[{"id":"a","name":"A","serverUrl":"http://a","deviceId":"da","deviceName":"dev","appKey":"ka","appSecret":"sa"}]}"#,
        );
        let config = load_or_init(&path).unwrap();
        assert_eq!(config.config_version, CONFIG_VERSION);
        assert_eq!(config.servers[0].kind, ServerKind::Direct);
        assert!(config.servers[0].parent_id.is_none());
        assert_eq!(config.servers[0].effective_app_key(), "ka");

        // 迁移后立即持久化，写回 configVersion。
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("\"configVersion\": 3"), "{content}");

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn delete_cascades_and_allows_empty_config() {
        let mut config = AppConfig {
            config_version: 3,
            active_server_id: "a".into(),
            servers: vec![],
        };
        let a = config.save_server(None, "A", "http://a", "dev").unwrap();
        let b = config
            .save_relay(None, &a.id, "B", "http://b", "dev", "kb", Some("sb"))
            .unwrap();
        let c = config
            .save_relay(None, &b.id, "C", "http://c", "dev", "kc", Some("sc"))
            .unwrap();
        let deleted = config.delete_server(&a.id).unwrap();
        let mut deleted_sorted = deleted.clone();
        deleted_sorted.sort();
        let mut expected = vec![a.id.clone(), b.id.clone(), c.id.clone()];
        expected.sort();
        assert_eq!(deleted_sorted, expected);
        assert!(config.servers.is_empty());
        assert!(config.active_server_id.is_empty());
        assert!(config.view().active_server.is_none());
        assert!(config.resolve_chain(&c.id).is_err());
    }

    #[test]
    fn empty_config_is_not_repopulated_on_reload() {
        let dir = temp_dir();
        let path = dir.join(CONFIG_FILE_NAME);
        write_file(
            &path,
            r#"{"configVersion":3,"activeServerId":"","servers":[]}"#,
        );
        let config = load_or_init(&path).unwrap();
        assert!(config.servers.is_empty());
        assert!(config.active_server_id.is_empty());
        assert!(config.view().active_server.is_none());
        let reloaded = load_or_init(&path).unwrap();
        assert!(reloaded.servers.is_empty());
        assert!(reloaded.view().active_server.is_none());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn resolve_chain_returns_root_to_target_order() {
        let mut config = AppConfig::default();
        let a = config.save_server(None, "A", "http://a", "dev").unwrap();
        let b = config
            .save_relay(None, &a.id, "B", "http://b", "dev", "kb", Some("sb"))
            .unwrap();
        let c = config
            .save_relay(None, &b.id, "C", "http://c", "dev", "kc", Some("sc"))
            .unwrap();

        let chain = config.resolve_chain(&c.id).unwrap();
        let names: Vec<&str> = chain.iter().map(|hop| hop.name.as_str()).collect();
        assert_eq!(names, vec!["A", "B", "C"]);
        assert_eq!(chain[0].id, a.id);
        assert_eq!(chain[2].id, c.id);

        let chain = config.resolve_chain(&a.id).unwrap();
        assert_eq!(chain.len(), 1);
        assert_eq!(chain[0].kind, ServerKind::Direct);
        assert!(config.resolve_chain("missing").is_err());
    }

    #[test]
    fn invalid_parent_self_or_cycle_marks_server_unconfigured() {
        let mut config = AppConfig::default();
        let a = config.save_server(None, "A", "http://a", "dev").unwrap();
        let b = config
            .save_relay(None, &a.id, "B", "http://b", "dev", "kb", Some("sb"))
            .unwrap();
        let c = config
            .save_relay(None, &b.id, "C", "http://c", "dev", "kc", Some("sc"))
            .unwrap();

        // 父不存在 → configured=false，不静默改成 direct。
        config
            .servers
            .iter_mut()
            .find(|server| server.id == b.id)
            .unwrap()
            .parent_id = Some("missing-parent".to_string());
        assert!(config.resolve_chain(&b.id).is_err());
        assert!(config.resolve_chain(&c.id).is_err());
        let view = config.view();
        assert!(
            !view
                .servers
                .iter()
                .find(|item| item.id == b.id)
                .unwrap()
                .configured
        );
        assert!(
            !view
                .servers
                .iter()
                .find(|item| item.id == c.id)
                .unwrap()
                .configured
        );
        assert_eq!(
            view.servers
                .iter()
                .find(|item| item.id == b.id)
                .unwrap()
                .kind,
            ServerKind::Relay,
            "父缺失不应把 relay 静默改成 direct"
        );

        // 环：B → C → B。
        config
            .servers
            .iter_mut()
            .find(|server| server.id == b.id)
            .unwrap()
            .parent_id = Some(c.id.clone());
        assert!(config.resolve_chain(&b.id).is_err());
        assert!(config.resolve_chain(&c.id).is_err());
        assert!(config.resolve_chain(&b.id).unwrap_err().contains("环"));

        // relay 不能作为根。
        config
            .servers
            .iter_mut()
            .find(|server| server.id == c.id)
            .unwrap()
            .parent_id = None;
        assert!(config.resolve_chain(&c.id).is_err());
        assert!(
            !config
                .view()
                .servers
                .iter()
                .find(|item| item.id == c.id)
                .unwrap()
                .configured
        );

        // 自指 parent 在 normalize 时被清洗。
        config
            .servers
            .iter_mut()
            .find(|server| server.id == c.id)
            .unwrap()
            .parent_id = Some(c.id.clone());
        let mut reloaded = config.clone();
        reloaded.normalize();
        assert!(reloaded.find(&c.id).unwrap().parent_id.is_none());
    }

    #[test]
    fn relay_view_exposes_chain_and_never_leaks_secret() {
        let mut config = AppConfig {
            config_version: CONFIG_VERSION,
            ..AppConfig::default()
        };
        let a = config.save_server(None, "A", "http://a", "dev").unwrap();
        let b = config
            .save_relay(
                None,
                &a.id,
                "B",
                "http://b",
                "dev-b",
                "kb",
                Some("relay-secret-should-not-leak"),
            )
            .unwrap();

        let view = config.view();
        assert_eq!(view.config_version, CONFIG_VERSION);
        assert!(view.active_server.is_some());
        let server = view.servers.iter().find(|item| item.id == b.id).unwrap();
        assert_eq!(server.kind, ServerKind::Relay);
        assert_eq!(server.parent_id.as_deref(), Some(a.id.as_str()));
        assert_eq!(server.parent_name.as_deref(), Some("A"));
        assert_eq!(server.chain_path, vec!["A".to_string(), "B".to_string()]);
        assert_eq!(server.chain_hops.len(), 2);
        assert_eq!(server.chain_hops[0].id, a.id);
        assert_eq!(server.root_id, a.id);
        assert_eq!(server.root_name, "A");
        assert_eq!(server.effective_app_key, "kb");
        assert!(server.configured);

        let json = serde_json::to_string(&view).unwrap();
        assert!(!json.contains("relay-secret-should-not-leak"));
        assert!(!json.contains("appSecret"), "视图不能包含 appSecret 字段");

        // 整条链有效才算 configured：把 A 的地址清空后 B 也不可用。
        config
            .servers
            .iter_mut()
            .find(|item| item.id == a.id)
            .unwrap()
            .server_url = String::new();
        let view = config.view();
        assert!(
            !view
                .servers
                .iter()
                .find(|item| item.id == b.id)
                .unwrap()
                .configured
        );
    }

    #[test]
    fn save_relay_validates_credentials_and_preserves_secret() {
        let mut config = AppConfig::default();
        let a = config.save_server(None, "A", "http://a", "dev").unwrap();

        // AppKey / AppSecret 必填。
        assert!(config
            .save_relay(None, &a.id, "B", "http://b", "dev", "", Some("sb"))
            .is_err());
        assert!(config
            .save_relay(None, &a.id, "B", "http://b", "dev", "kb", Some(""))
            .is_err());
        assert!(config
            .save_relay(
                None,
                "missing-parent",
                "B",
                "http://b",
                "dev",
                "kb",
                Some("sb")
            )
            .is_err());
        assert_eq!(config.servers.len(), 1);

        let b = config
            .save_relay(None, &a.id, "B", "http://b", "dev", "kb", Some("sb"))
            .unwrap();
        assert_eq!(b.kind, ServerKind::Relay);
        assert_eq!(b.effective_app_key(), "kb");
        assert_eq!(b.effective_app_secret(), "sb");
        assert_eq!(config.active_server_id, b.id, "新建 relay 应自动激活");

        // 编辑时 secret 传 None：保留原 secret；deviceId 不变。
        let edited = config
            .save_relay(Some(&b.id), &a.id, "B2", "http://b2", "dev2", "kb2", None)
            .unwrap();
        assert_eq!(edited.id, b.id);
        assert_eq!(edited.device_id, b.device_id);
        assert_eq!(edited.name, "B2");
        assert_eq!(edited.server_url, "http://b2");
        assert_eq!(edited.effective_app_key(), "kb2");
        assert_eq!(edited.effective_app_secret(), "sb", "None 应保留原 secret");

        // 编辑时可更新 secret。
        let edited = config
            .save_relay(
                Some(&b.id),
                &a.id,
                "B2",
                "http://b2",
                "dev2",
                "kb2",
                Some("sb2"),
            )
            .unwrap();
        assert_eq!(edited.effective_app_secret(), "sb2");

        // 通过 save_server 编辑 relay 应被拒绝，避免把代理服务器当成直连改坏。
        assert!(config
            .save_server(Some(&b.id), "B2", "http://b2", "dev2")
            .is_err());
        // 非 relay 服务器不能用 save_relay 编辑。
        assert!(config
            .save_relay(Some(&a.id), &a.id, "A", "http://a", "dev", "ka", Some("sa"))
            .is_err());
        // AppSecret 只落盘 0600，不进入视图。
        assert!(!serde_json::to_string(&config.view())
            .unwrap()
            .contains("sb2"));
    }

    #[test]
    fn save_relay_rolls_back_on_cycle() {
        let mut config = AppConfig::default();
        let a = config.save_server(None, "A", "http://a", "dev").unwrap();
        let b = config
            .save_relay(None, &a.id, "B", "http://b", "dev", "kb", Some("sb"))
            .unwrap();
        let c = config
            .save_relay(None, &b.id, "C", "http://c", "dev", "kc", Some("sc"))
            .unwrap();

        // 把 B 的上级改成自己的子节点 C → 成环，必须回滚。
        let snapshot = config.clone();
        let error = config
            .save_relay(Some(&b.id), &c.id, "B", "http://b", "dev", "kb", None)
            .unwrap_err();
        assert!(error.contains("环"), "{error}");
        assert_eq!(config.servers.len(), snapshot.servers.len());
        assert_eq!(
            config.find(&b.id).unwrap().parent_id.as_deref(),
            Some(a.id.as_str()),
            "失败后应回滚 parent"
        );
    }
}
