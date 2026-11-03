//! 本机物理地址（MAC）识别。
//!
//! 服务端要求所有 `/api/**` 请求携带 `X-Device-Mac`（规范化大写冒号格式
//! `AA:BB:CC:DD:EE:FF`），缺失或非法会被拉黑；因此客户端在取不到 MAC 时
//! 直接阻断请求，绝不发出没有该请求头的请求。
//!
//! 只使用标准库：桌面端读取平台命令 / sysfs，不新增 crate 依赖（离线可用）。

use std::sync::OnceLock;

/// 本机物理地址（大写冒号格式）；读不到时为空串。
///
/// 只在第一次调用时探测一次，之后复用缓存值。
pub fn device_mac() -> &'static str {
    static CACHE: OnceLock<String> = OnceLock::new();
    CACHE.get_or_init(|| detect().unwrap_or_default()).as_str()
}

/// 规范化 MAC 字符串。
///
/// 去掉 `:`、`-`、`.` 及空白等分隔符后必须是 12 位十六进制；合法时返回大写
/// 冒号格式（`AA:BB:CC:DD:EE:FF`），否则返回 `None`。
pub fn normalize_mac(raw: &str) -> Option<String> {
    let hex: String = raw
        .chars()
        .filter(|ch| !ch.is_whitespace() && !matches!(ch, ':' | '-' | '.'))
        .collect();
    if hex.len() != 12 || !hex.chars().all(|ch| ch.is_ascii_hexdigit()) {
        return None;
    }
    let hex = hex.to_ascii_uppercase();
    let mut out = String::with_capacity(17);
    for (index, ch) in hex.chars().enumerate() {
        if index > 0 && index % 2 == 0 {
            out.push(':');
        }
        out.push(ch);
    }
    Some(out)
}

/// 探测本机第一个合法的物理地址（各平台实现见下）。
#[cfg(target_os = "macos")]
fn detect() -> Option<String> {
    let output = std::process::Command::new("ifconfig").output().ok()?;
    if !output.status.success() {
        return None;
    }
    parse_macos_ifconfig(&String::from_utf8_lossy(&output.stdout))
}

/// 解析 `ifconfig` 输出：物理 `en*` 优先（en0 → en1 → 其它 en*），
/// 其次回退到第一个非虚拟接口。避免取到 anpi/awdl/utun 等虚拟网卡的 MAC。
#[cfg(target_os = "macos")]
fn parse_macos_ifconfig(output: &str) -> Option<String> {
    let mut interfaces: Vec<(String, String)> = Vec::new();
    let mut interface = String::new();
    for line in output.lines() {
        // 接口头（顶格）：`en0: flags=8863<...>`。
        if !line.starts_with(|ch: char| ch.is_whitespace()) {
            interface = line
                .split(':')
                .next()
                .unwrap_or_default()
                .trim()
                .to_string();
            continue;
        }
        let trimmed = line.trim_start();
        if let Some(rest) = trimmed.strip_prefix("ether ") {
            if let Some(mac) = normalize_mac(rest) {
                interfaces.push((interface.clone(), mac));
            }
        }
    }
    for preferred in ["en0", "en1"] {
        if let Some((_, mac)) = interfaces.iter().find(|(name, _)| name == preferred) {
            return Some(mac.clone());
        }
    }
    if let Some((_, mac)) = interfaces.iter().find(|(name, _)| name.starts_with("en")) {
        return Some(mac.clone());
    }
    interfaces
        .into_iter()
        .find(|(name, _)| !is_virtual_macos_interface(name))
        .map(|(_, mac)| mac)
}

/// macOS 虚拟/隧道接口（其 MAC 不是本机物理地址）。
#[cfg(target_os = "macos")]
fn is_virtual_macos_interface(name: &str) -> bool {
    name == "lo0"
        || ["anpi", "awdl", "llw", "utun", "bridge", "gif", "stf", "ap"]
            .iter()
            .any(|prefix| name.starts_with(prefix))
}

/// Linux：遍历 `/sys/class/net/*/address`，跳过回环与常见虚拟网卡。
#[cfg(target_os = "linux")]
fn detect() -> Option<String> {
    let entries = std::fs::read_dir("/sys/class/net").ok()?;
    let mut names: Vec<String> = entries
        .flatten()
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .collect();
    // read_dir 顺序不保证，排序后取第一个，保证多次运行结果稳定。
    names.sort();
    for name in names {
        if is_virtual_interface(&name) {
            continue;
        }
        let path = format!("/sys/class/net/{name}/address");
        let Ok(raw) = std::fs::read_to_string(&path) else {
            continue;
        };
        if let Some(mac) = normalize_mac(&raw) {
            return Some(mac);
        }
    }
    None
}

/// 回环与虚拟网桥/容器网卡（其 MAC 不是本机物理地址）。
#[cfg(target_os = "linux")]
fn is_virtual_interface(name: &str) -> bool {
    name == "lo"
        || ["docker", "veth", "br-", "tun", "tap", "virbr"]
            .iter()
            .any(|prefix| name.starts_with(prefix))
}

/// Windows：`getmac /fo csv /nh` 输出中提取第一个合法 MAC。
#[cfg(target_os = "windows")]
fn detect() -> Option<String> {
    let output = std::process::Command::new("getmac")
        .args(["/fo", "csv", "/nh"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    parse_getmac(&String::from_utf8_lossy(&output.stdout))
}

/// 解析 `getmac` CSV 行：`"AA-BB-CC-DD-EE-FF","\\Device\\Tcpip_{...}"`。
#[cfg(target_os = "windows")]
fn parse_getmac(output: &str) -> Option<String> {
    for line in output.lines() {
        for field in line.split(',') {
            let field = field.trim().trim_matches('"');
            if field.is_empty() {
                continue;
            }
            if let Some(mac) = normalize_mac(field) {
                return Some(mac);
            }
        }
    }
    None
}

/// 其它平台：不识别，返回空串（调用方会阻断请求）。
#[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
fn detect() -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_mac_accepts_common_separators() {
        for (raw, expected) in [
            ("aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF"),
            ("AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE:FF"),
            ("aabb.ccdd.eeff", "AA:BB:CC:DD:EE:FF"),
            ("aabbccddeeff", "AA:BB:CC:DD:EE:FF"),
            ("  aa bb cc dd ee ff  ", "AA:BB:CC:DD:EE:FF"),
            ("02:00:00:00:00:01\n", "02:00:00:00:00:01"),
        ] {
            assert_eq!(normalize_mac(raw).as_deref(), Some(expected), "raw={raw:?}");
        }
    }

    #[test]
    fn normalize_mac_rejects_invalid_values() {
        for raw in [
            "",
            "   ",
            "aa:bb:cc:dd:ee",
            "aa:bb:cc:dd:ee:ff:00",
            "gg:bb:cc:dd:ee:ff",
            "not-a-mac",
            "aa:bb:cc:dd:ee:ff:gg",
            "0:0:0:0:0:1",
        ] {
            assert!(normalize_mac(raw).is_none(), "raw={raw:?} 应判为非法");
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_prefers_physical_en0_over_virtual_interfaces() {
        let sample = "\
lo0: flags=8049<UP,LOOPBACK,RUNNING,MULTICAST> mtu 16384
\tether 00:00:00:00:00:00
anpi1: flags=8863<UP,BROADCAST,RUNNING,SIMPLEX,MULTICAST> mtu 1500
\tether 02:12:5d:7d:d1:c3
en0: flags=8863<UP,BROADCAST,RUNNING,SIMPLEX,MULTICAST> mtu 1500
\tether aa:bb:cc:dd:ee:ff
awdl0: flags=8863<UP,BROADCAST,RUNNING,SIMPLEX,MULTICAST> mtu 1500
\tether 12:34:56:78:9a:bc
";
        assert_eq!(
            parse_macos_ifconfig(sample).as_deref(),
            Some("AA:BB:CC:DD:EE:FF"),
            "应优先 en0，而不是排在前面的 anpi1"
        );
    }

    #[test]
    fn device_mac_is_normalized_when_available() {
        let mac = device_mac();
        if mac.is_empty() {
            // 无网卡 / 权限受限的环境（例如某些 CI 容器）读不到 MAC 不算失败。
            eprintln!("当前环境未读到本机 MAC，跳过 device_mac 规范化断言");
            return;
        }
        assert!(
            normalize_mac(mac).is_some(),
            "device_mac 应是合法 MAC，实际为 {mac:?}"
        );
        assert_eq!(normalize_mac(mac).as_deref(), Some(mac));
    }
}
