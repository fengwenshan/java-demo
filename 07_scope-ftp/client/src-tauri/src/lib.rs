//! ScopeFTP 桌面客户端（Tauri 2）。
//!
//! 所有网络请求、HMAC 签名、文件读写均在 Rust 侧完成，WebView 拿不到 `appSecret`；
//! AppKey / AppSecret 默认使用编译期内置值（见 [`builtin`]）；每台服务器可选自定义，自定义 secret 只保存在本地配置（0600），不返回 WebView。
//! 本地配置支持多台服务器（v3 树形转发链，见 [`config`]），请求按链逐跳签名
//! （见 [`api`]），大文件上传支持分片与断点续传（见 [`uploads`]）。

mod api;
mod builtin;
mod commands;
mod config;
mod mac;
mod net;
mod signer;
mod uploads;

use std::path::PathBuf;
use std::sync::Mutex;

#[cfg(target_os = "macos")]
use tauri::RunEvent;
use tauri::{
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Manager, WindowEvent,
};

/// 全局应用状态：多服务器本地配置 + HTTP 客户端 + 本地文件路径。
pub struct AppState {
    pub config: Mutex<config::AppConfig>,
    pub http: reqwest::Client,
    pub config_path: PathBuf,
    /// 断点续传状态文件（app config dir 下的 `uploads.json`）。
    pub upload_state_path: PathBuf,
}

/// 显示并聚焦主窗口；窗口被 X 隐藏后仍可复用。
fn show_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// 菜单栏 / 托盘图标：白色 + 透明。
///
/// macOS 用模板图（纯黑 + alpha），系统按菜单栏明暗自动反色；
/// Windows / Linux 用白色位图。图形是简化版应用图标：文件夹 + 上下箭头。
fn tray_icon_image() -> Option<tauri::image::Image<'static>> {
    #[cfg(target_os = "macos")]
    const BYTES: &[u8] = include_bytes!("../icons/tray/tray-template-36.png");
    #[cfg(all(not(target_os = "macos"), target_os = "windows"))]
    const BYTES: &[u8] = include_bytes!("../icons/tray/tray-white-32.png");
    #[cfg(all(not(target_os = "macos"), not(target_os = "windows")))]
    const BYTES: &[u8] = include_bytes!("../icons/tray/tray-white-48.png");
    tauri::image::Image::from_bytes(BYTES).ok()
}

/// 系统托盘（macOS 菜单栏）图标：左键/右键都直接打开窗口，不弹下拉菜单。
fn create_tray(app: &tauri::App) -> tauri::Result<()> {
    let icon = tray_icon_image()
        .ok_or_else(|| tauri::Error::AssetNotFound("缺少托盘图标，无法创建系统托盘".into()))?;

    TrayIconBuilder::new()
        .icon(icon)
        .icon_as_template(cfg!(target_os = "macos"))
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left | MouseButton::Right,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // reqwest 使用 rustls-no-provider（环境无 cmake，避开 aws-lc-rs），
    // 这里安装 ring 加密后端。
    let _ = rustls::crypto::ring::default_provider().install_default();

    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .setup(|app| {
            let config_path = config::config_path(app.handle()).map_err(std::io::Error::other)?;
            let upload_state_path = config_path
                .parent()
                .map(|dir| dir.join(uploads::UPLOAD_STATE_FILE_NAME))
                .unwrap_or_else(|| PathBuf::from(uploads::UPLOAD_STATE_FILE_NAME));
            // 配置目录不可写时（例如受限沙箱）不阻断启动：本次会话使用内存配置，
            // 保存配置时会返回明确错误提示。
            let app_config = config::load_or_init(&config_path).unwrap_or_else(|error| {
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
                .user_agent(concat!("ScopeFTP/", env!("CARGO_PKG_VERSION")))
                .connect_timeout(std::time::Duration::from_secs(10))
                .build()
                .map_err(|e| std::io::Error::other(format!("初始化 HTTP 客户端失败：{e}")))?;

            app.manage(AppState {
                config: Mutex::new(app_config),
                http,
                config_path,
                upload_state_path,
            });
            create_tray(app)?;

            // 自绘标题栏：Windows 去掉原生边框，最小化/最大化/关闭由前端标题栏负责；
            // macOS 保留 Overlay 的系统红绿灯，Linux 继续用系统边框。
            #[cfg(target_os = "windows")]
            if let Some(window) = app.get_webview_window("main") {
                if let Err(error) = window.set_decorations(false) {
                    eprintln!("关闭系统标题栏失败：{error}");
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // macOS 习惯：点窗口 X 只关闭窗口（隐藏到托盘），真正退出走 Dock 右键「退出」或 ⌘Q。
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_config,
            commands::reveal_server_secret,
            commands::save_server,
            commands::save_relay,
            commands::delete_server,
            commands::set_active_server,
            commands::file_info,
            commands::verify_connection,
            commands::verify_server,
            commands::verify_connection_preview,
            commands::verify_relay_preview,
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
            commands::create_server_entry,
            commands::copy_server_path,
            commands::move_server_path,
            commands::list_audit_logs,
            commands::read_server_file_content,
            commands::save_server_file_content,
            commands::extract_zip_file,
            commands::open_server_file,
            commands::terminal_exec,
            commands::get_local_network_info,
            commands::get_platform_info,
        ])
        .build(tauri::generate_context!())
        .expect("运行 Tauri 应用失败");

    app.run(|app_handle, event| {
        #[cfg(target_os = "macos")]
        if let RunEvent::Reopen {
            has_visible_windows,
            ..
        } = event
        {
            if !has_visible_windows {
                show_main_window(app_handle);
            }
        }
        #[cfg(not(target_os = "macos"))]
        {
            let _ = (app_handle, event);
        }
    });
}
