//! 内置客户端凭据与默认服务端地址。
//!
//! 用户不再在设置页填写 AppKey / AppSecret：客户端在编译期把凭据内置，
//! 通过 `option_env!` 支持构建时覆盖（避免把真实凭据提交进仓库）：
//!
//! - `UPLOAD_CLIENT_APP_KEY`
//! - `UPLOAD_CLIENT_APP_SECRET`
//! - `UPLOAD_CLIENT_SERVER_URL`
//!
//! 未设置时使用与服务端演示默认值一致的配置。未自定义时 `config.json` 不保存凭据；
//! 内置 secret 不会返回给前端。

/// 演示用默认 AppKey（与服务端默认配置一致）。
const DEFAULT_APP_KEY: &str = "demo-app";
/// 演示用默认 AppSecret（与服务端默认配置一致）。
const DEFAULT_APP_SECRET: &str = "demo-secret-please-change-32-bytes-min";
/// 默认服务端地址（本地开发实例）。
const DEFAULT_SERVER_URL: &str = "http://127.0.0.1:8080";

/// 内置 AppKey（构建时可用 `UPLOAD_CLIENT_APP_KEY` 覆盖）。
pub fn app_key() -> &'static str {
    option_env!("UPLOAD_CLIENT_APP_KEY").unwrap_or(DEFAULT_APP_KEY)
}

/// 内置 AppSecret（构建时可用 `UPLOAD_CLIENT_APP_SECRET` 覆盖）。
///
/// 只在 Rust 侧用于 HMAC 签名：不写盘、不返回前端。
pub fn app_secret() -> &'static str {
    option_env!("UPLOAD_CLIENT_APP_SECRET").unwrap_or(DEFAULT_APP_SECRET)
}

/// 默认服务端地址（构建时可用 `UPLOAD_CLIENT_SERVER_URL` 覆盖）。
///
/// `config.json` 中 `serverUrl` 为空（首次启动 / 旧配置）时用该值填充并持久化。
pub fn default_server_url() -> &'static str {
    option_env!("UPLOAD_CLIENT_SERVER_URL").unwrap_or(DEFAULT_SERVER_URL)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_values_are_non_empty() {
        assert!(!app_key().trim().is_empty(), "内置 appKey 不能为空");
        assert!(!app_secret().trim().is_empty(), "内置 appSecret 不能为空");
        let server_url = default_server_url();
        assert!(!server_url.trim().is_empty(), "默认服务器地址不能为空");
        assert!(
            server_url.starts_with("http://") || server_url.starts_with("https://"),
            "默认服务器地址必须是 http(s) 地址: {server_url}"
        );
    }

    #[test]
    fn builtin_defaults_match_demo_when_not_overridden() {
        if option_env!("UPLOAD_CLIENT_APP_KEY").is_none() {
            assert_eq!(app_key(), "demo-app");
        }
        if option_env!("UPLOAD_CLIENT_APP_SECRET").is_none() {
            assert_eq!(app_secret(), "demo-secret-please-change-32-bytes-min");
        }
        if option_env!("UPLOAD_CLIENT_SERVER_URL").is_none() {
            assert_eq!(default_server_url(), "http://127.0.0.1:8080");
        }
    }
}
