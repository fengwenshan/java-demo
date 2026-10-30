//! ScopeFTP-client 桌面客户端（Tauri 2）。
//!
//! 所有网络请求、HMAC 签名、文件读写均在 Rust 侧完成，WebView 拿不到 `appSecret`；
//! AppKey / AppSecret 默认使用编译期内置值（见 [`builtin`]）；每台服务器可选自定义，自定义 secret 只保存在本地配置（0600），不返回 WebView。
//! 本地配置支持多台服务器（v2，见 [`config`]），大文件上传支持分片与断点续传
//! （见 [`uploads`]）。

mod api;
mod builtin;
mod commands;
mod config;
mod signer;
mod uploads;

use std::path::PathBuf;
use std::sync::Mutex;

use tauri::Manager;

/// 全局应用状态：多服务器本地配置 + HTTP 客户端 + 本地文件路径。
pub struct AppState {
    pub config: Mutex<config::AppConfig>,
    pub http: reqwest::Client,
    pub config_path: PathBuf,
    /// 断点续传状态文件（app config dir 下的 `uploads.json`）。
    pub upload_state_path: PathBuf,
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // reqwest 使用 rustls-no-provider（环境无 cmake，避开 aws-lc-rs），
    // 这里安装 ring 加密后端。
    let _ = rustls::crypto::ring::default_provider().install_default();

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let config_path = config::config_path(app.handle()).map_err(std::io::Error::other)?;
            let upload_state_path = config_path
                .parent()
                .map(|dir| dir.join(uploads::UPLOAD_STATE_FILE_NAME))
                .unwrap_or_else(|| PathBuf::from(uploads::UPLOAD_STATE_FILE_NAME));
            // 配置目录不可写时（例如受限沙箱）不阻断启动：本次会话使用内存配置，
            // 保存配置时会返回明确错误提示。
            let app_config = config::load_or_init(&config_path)
                .unwrap_or_else(|error| {
                    eprintln!("加载配置失败，使用内存默认配置：{error}");
                    config::default_config()
                });
            // 启动时清理超过 24h 的断点续传记录。
            if let Ok(mut store) = uploads::UploadStore::load(&upload_state_path) {
                if store.cleanup_stale(api::now_millis()) > 0 {
                    let _ = store.save();
                }
            }
            let http = reqwest::Client::builder()
                .user_agent(concat!("ScopeFTP-client/", env!("CARGO_PKG_VERSION")))
                .connect_timeout(std::time::Duration::from_secs(10))
                .build()
                .map_err(|e| std::io::Error::other(format!("初始化 HTTP 客户端失败：{e}")))?;

            app.manage(AppState {
                config: Mutex::new(app_config),
                http,
                config_path,
                upload_state_path,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_config,
            commands::save_server,
            commands::delete_server,
            commands::set_active_server,
            commands::file_info,
            commands::verify_connection,
            commands::activate,
            commands::list_storage_roots,
            commands::upload_file,
            commands::list_files,
            commands::list_server_dir,
            commands::search_server_files,
            commands::download_file,
            commands::delete_file,
            commands::download_server_file,
            commands::delete_server_file,
            commands::rename_server_file,
            commands::copy_server_path,
            commands::move_server_path,
            commands::list_audit_logs,
            commands::read_server_file_content,
            commands::save_server_file_content,
            commands::extract_zip_file,
            commands::open_server_file,
            commands::terminal_exec,
        ])
        .run(tauri::generate_context!())
        .expect("运行 Tauri 应用失败");
}
