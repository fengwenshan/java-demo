//! 签名实现，严格对应 `docs/02-api-contract.md` 第 2 节。
//!
//! 签名串固定 8 行，`\n` 分隔，末尾不加换行：
//! `v1 / appKey / timestampMillis / nonce / METHOD / path / canonicalQuery / payloadHash`

use hmac::{Hmac, Mac};
use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use sha2::{Digest, Sha256};

/// 签名版本，契约固定为 `v1`。
pub const SIGNATURE_VERSION: &str = "v1";

/// 空 body 的 SHA-256（GET / DELETE 的 payloadHash）。
pub const EMPTY_SHA256: &str = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/// RFC3986 percent-encoding 保留字符集：仅 `A-Z a-z 0-9 - . _ ~` 原样保留。
const QUERY_ENCODE_SET: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

/// 计算字节内容的 SHA-256，返回小写十六进制。
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// 对单个查询参数做契约 2.4 规定的 RFC3986 percent-encoding。
pub fn encode_query_component(value: &str) -> String {
    utf8_percent_encode(value, QUERY_ENCODE_SET).to_string()
}

/// 查询串规范化（契约 2.4）：
/// 解码 → 重新 RFC3986 编码 → 按 key、value 升序排序 → `key=value` 用 `&` 连接。
pub fn canonical_query(raw_query: &str) -> String {
    if raw_query.is_empty() {
        return String::new();
    }

    let mut pairs: Vec<(String, String)> = raw_query
        .split('&')
        .map(|pair| match pair.split_once('=') {
            Some((key, value)) => (decode_component(key), decode_component(value)),
            None => (decode_component(pair), String::new()),
        })
        .collect();

    let mut encoded: Vec<(String, String)> = pairs
        .drain(..)
        .map(|(key, value)| (encode_query_component(&key), encode_query_component(&value)))
        .collect();
    encoded.sort();

    encoded
        .into_iter()
        .map(|(key, value)| format!("{key}={value}"))
        .collect::<Vec<_>>()
        .join("&")
}

/// percent-decoding（UTF-8；`+` 不当作空格）。
fn decode_component(value: &str) -> String {
    percent_decode_str(value).decode_utf8_lossy().into_owned()
}

/// 拼装契约 2.3 的签名串。
#[allow(clippy::too_many_arguments)]
pub fn canonical_string(
    app_key: &str,
    timestamp_millis: i64,
    nonce: &str,
    method: &str,
    path: &str,
    canonical_query: &str,
    payload_hash: &str,
) -> String {
    format!(
        "{SIGNATURE_VERSION}\n{app_key}\n{timestamp_millis}\n{nonce}\n{method}\n{path}\n{canonical_query}\n{payload_hash}"
    )
}

/// HMAC-SHA256，返回小写十六进制。
pub fn hmac_sha256_hex(secret: &str, message: &str) -> String {
    let mut mac =
        Hmac::<Sha256>::new_from_slice(secret.as_bytes()).expect("HMAC-SHA256 支持任意长度的密钥");
    mac.update(message.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

/// 计算一次请求的签名值。
#[allow(clippy::too_many_arguments)]
pub fn sign_request(
    app_secret: &str,
    app_key: &str,
    timestamp_millis: i64,
    nonce: &str,
    method: &str,
    path: &str,
    canonical_query: &str,
    payload_hash: &str,
) -> String {
    let canonical = canonical_string(
        app_key,
        timestamp_millis,
        nonce,
        method,
        path,
        canonical_query,
        payload_hash,
    );
    hmac_sha256_hex(app_secret, &canonical)
}

/// 把 `/api/files?page=1` 拆成 path 与 query。
pub fn split_path_and_query(target: &str) -> (&str, &str) {
    match target.split_once('?') {
        Some((path, query)) => (path, query),
        None => (target, ""),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "demo-secret-please-change-32-bytes-min";
    const APP_KEY: &str = "demo-app";
    const TIMESTAMP: i64 = 1_760_000_000_000;
    const NONCE: &str = "0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f";

    /// 契约 2.3 测试向量 1：GET /api/auth/verify（无请求体）。
    #[test]
    fn contract_vector_get_auth_verify() {
        let canonical = canonical_string(
            APP_KEY,
            TIMESTAMP,
            NONCE,
            "GET",
            "/api/auth/verify",
            "",
            EMPTY_SHA256,
        );
        assert_eq!(
            canonical,
            concat!(
                "v1\n",
                "demo-app\n",
                "1760000000000\n",
                "0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f\n",
                "GET\n",
                "/api/auth/verify\n",
                "\n",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            )
        );
        assert_eq!(
            hmac_sha256_hex(SECRET, &canonical),
            "9ca81faf26909bec390a1bba9a38a02c812843d9a64443a1e226522ceaa5a14b"
        );
    }

    /// 契约 2.3 测试向量 2：POST /api/files/upload（文件内容 "hello"）。
    #[test]
    fn contract_vector_upload_hello() {
        let content_hash = sha256_hex(b"hello");
        assert_eq!(
            content_hash,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );

        let canonical = canonical_string(
            APP_KEY,
            TIMESTAMP,
            NONCE,
            "POST",
            "/api/files/upload",
            "",
            &content_hash,
        );
        assert_eq!(
            canonical,
            concat!(
                "v1\n",
                "demo-app\n",
                "1760000000000\n",
                "0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f\n",
                "POST\n",
                "/api/files/upload\n",
                "\n",
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        );
        assert_eq!(
            hmac_sha256_hex(SECRET, &canonical),
            "4ce78a59afffd8264f7277fcebbf74daefab380eb95adae218005e6d79302c0c"
        );
    }

    /// 契约 2.4 示例：`size=20&page=1&keyword=中文 空格`。
    #[test]
    fn canonical_query_matches_contract_example() {
        let canonical = canonical_query("size=20&page=1&keyword=中文 空格");
        assert_eq!(
            canonical,
            "keyword=%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC&page=1&size=20"
        );
    }

    /// `+` 不当作空格；`*`/`!`/`'` 等非保留字符必须被编码。
    #[test]
    fn canonical_query_does_not_treat_plus_as_space() {
        assert_eq!(canonical_query("q=a+b"), "q=a%2Bb");
        assert_eq!(canonical_query("q=a*b!'()~"), "q=a%2Ab%21%27%28%29~");
    }

    /// 查询串 key/value 均按 UTF-8 字节序排序；空查询返回空串（签名串中为空行）。
    #[test]
    fn canonical_query_sorts_and_empties() {
        assert_eq!(canonical_query(""), "");
        assert_eq!(canonical_query("b=2&a=2&a=1"), "a=1&a=2&b=2");
    }

    /// 空 body 哈希与契约一致。
    #[test]
    fn empty_payload_hash_matches_contract() {
        assert_eq!(sha256_hex(b""), EMPTY_SHA256);
    }

    /// 签名串恰好 8 行、末尾无换行。
    #[test]
    fn canonical_string_shape() {
        let canonical = canonical_string(
            APP_KEY,
            TIMESTAMP,
            NONCE,
            "DELETE",
            "/api/files/1",
            "",
            EMPTY_SHA256,
        );
        let lines: Vec<&str> = canonical.split('\n').collect();
        assert_eq!(lines.len(), 8);
        assert!(!canonical.ends_with('\n'));
        assert_eq!(lines[4], "DELETE");
        assert_eq!(lines[6], "");
    }
}
