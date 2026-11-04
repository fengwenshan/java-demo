//! 本机网络信息（物理地址 + 局域网 IPv4）。
//!
//! 详情页展示「本机 MAC / 本机 IP」用。与 `mac.rs` 一样只用标准库：先做
//! 不发包的 UDP 路由探测（默认路由那张网卡），失败再按平台命令兜底；
//! 统一排除回环（127/8）、链路本地（169.254/16）这类不可展示的地址。

use std::net::{IpAddr, Ipv4Addr, UdpSocket};

/// 本机网络信息；取不到的字段为空串（前端显示「未获取到」）。
#[derive(Debug, Clone, serde::Serialize)]
pub struct LocalNetworkInfo {
    pub mac: String,
    pub ip: String,
}

/// 本机物理地址 + 默认路由网卡的局域网 IPv4。
pub fn local_network_info() -> LocalNetworkInfo {
    LocalNetworkInfo {
        mac: crate::mac::device_mac().to_string(),
        ip: local_ipv4().map(|ip| ip.to_string()).unwrap_or_default(),
    }
}

/// 默认路由网卡的局域网 IPv4；读不到返回 `None`。
///
/// 先做 UDP 探测：`connect` 只让内核选路由（不发包），`local_addr()` 就是
/// 默认路由网卡的地址；无默认路由或系统限制时退回平台命令解析。
pub fn local_ipv4() -> Option<Ipv4Addr> {
    probe_default_route().or_else(platform_local_ipv4)
}

/// 探测目标：只要不真的发包，选哪个公网地址都行（AliDNS）。
const PROBE_TARGET: &str = "223.5.5.5:80";

fn probe_default_route() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect(PROBE_TARGET).ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if is_lan_ipv4(ip) => Some(ip),
        _ => None,
    }
}

/// 是否是可展示的局域网 IPv4：排除回环（127/8）、未指定、链路本地
/// （169.254/16）、广播与组播。
pub(crate) fn is_lan_ipv4(ip: Ipv4Addr) -> bool {
    !(ip.is_loopback()
        || ip.is_unspecified()
        || ip.is_link_local()
        || ip.is_broadcast()
        || ip.is_multicast())
}

fn parse_ipv4(raw: &str) -> Option<Ipv4Addr> {
    let ip: Ipv4Addr = raw.trim().parse().ok()?;
    is_lan_ipv4(ip).then_some(ip)
}

// ============================================================================
// macOS
// ============================================================================

#[cfg(target_os = "macos")]
fn platform_local_ipv4() -> Option<Ipv4Addr> {
    // 1) 默认路由接口 → ipconfig getifaddr <接口>
    if let Ok(output) = std::process::Command::new("route")
        .args(["-n", "get", "default"])
        .output()
    {
        if output.status.success() {
            let text = String::from_utf8_lossy(&output.stdout);
            if let Some(interface) = parse_route_interface(&text) {
                if let Some(ip) = ipconfig_getifaddr(&interface) {
                    return Some(ip);
                }
            }
        }
    }
    // 2) 常见物理网卡
    for interface in ["en0", "en1"] {
        if let Some(ip) = ipconfig_getifaddr(interface) {
            return Some(ip);
        }
    }
    // 3) 解析 ifconfig（回环/隧道会被过滤）
    let output = std::process::Command::new("ifconfig").output().ok()?;
    if !output.status.success() {
        return None;
    }
    parse_ifconfig_inet(&String::from_utf8_lossy(&output.stdout))
}

#[cfg(target_os = "macos")]
fn ipconfig_getifaddr(interface: &str) -> Option<Ipv4Addr> {
    let output = std::process::Command::new("ipconfig")
        .args(["getifaddr", interface])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    parse_ipv4(&String::from_utf8_lossy(&output.stdout))
}

/// 从 `route -n get default` 输出里取 `interface: en0`。
#[cfg(target_os = "macos")]
fn parse_route_interface(output: &str) -> Option<String> {
    output.lines().find_map(|line| {
        line.trim()
            .strip_prefix("interface:")
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty())
    })
}

/// 从 `ifconfig` 输出里找物理网卡的 `inet` 地址（en0 → 其它 en* → 非虚拟接口）。
#[cfg(target_os = "macos")]
fn parse_ifconfig_inet(output: &str) -> Option<Ipv4Addr> {
    let mut interface = String::new();
    let mut candidates: Vec<(String, Ipv4Addr)> = Vec::new();
    for line in output.lines() {
        if !line.starts_with(char::is_whitespace) {
            interface = line
                .split(':')
                .next()
                .unwrap_or_default()
                .trim()
                .to_string();
            continue;
        }
        let trimmed = line.trim_start();
        let Some(rest) = trimmed.strip_prefix("inet ") else {
            continue;
        };
        let raw = rest.split_whitespace().next().unwrap_or_default();
        if let Some(ip) = parse_ipv4(raw) {
            candidates.push((interface.clone(), ip));
        }
    }
    candidates
        .iter()
        .find(|(name, _)| name == "en0")
        .or_else(|| candidates.iter().find(|(name, _)| name.starts_with("en")))
        .or_else(|| {
            candidates
                .iter()
                .find(|(name, _)| !is_virtual_macos_interface(name))
        })
        .map(|(_, ip)| *ip)
}

/// macOS 虚拟/隧道接口（其地址不是本机局域网地址）。
#[cfg(target_os = "macos")]
fn is_virtual_macos_interface(name: &str) -> bool {
    name == "lo0"
        || ["anpi", "awdl", "llw", "utun", "bridge", "gif", "stf", "ap"]
            .iter()
            .any(|prefix| name.starts_with(prefix))
}

// ============================================================================
// Linux
// ============================================================================

#[cfg(target_os = "linux")]
fn platform_local_ipv4() -> Option<Ipv4Addr> {
    // 1) `ip -4 route get <公网地址>` 输出里取 `src`。
    if let Ok(output) = std::process::Command::new("ip")
        .args(["-4", "route", "get", "223.5.5.5"])
        .output()
    {
        if output.status.success() {
            let text = String::from_utf8_lossy(&output.stdout);
            if let Some(ip) = parse_ip_route_src(&text) {
                return Some(ip);
            }
        }
    }
    // 2) 枚举 scope global 的 IPv4，跳过回环/虚拟网卡。
    let output = std::process::Command::new("ip")
        .args(["-4", "-o", "addr", "show", "scope", "global"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    parse_ip_addr(&String::from_utf8_lossy(&output.stdout))
}

#[cfg(target_os = "linux")]
fn parse_ip_route_src(output: &str) -> Option<Ipv4Addr> {
    let mut tokens = output.split_whitespace();
    while let Some(token) = tokens.next() {
        if token == "src" {
            return tokens.next().and_then(parse_ipv4);
        }
    }
    None
}

/// 解析 `ip -4 -o addr show scope global` 的一行：
/// `2: enp3s0    inet 10.0.0.8/24 brd 10.0.0.255 scope global dynamic enp3s0`。
#[cfg(target_os = "linux")]
fn parse_ip_addr(output: &str) -> Option<Ipv4Addr> {
    output.lines().find_map(|line| {
        let mut parts = line.split_whitespace();
        let _index = parts.next()?;
        let name = parts.next()?.trim_end_matches(':');
        let mut parts = parts.skip_while(|token| *token != "inet");
        parts.next()?;
        let cidr = parts.next()?;
        let ip: Ipv4Addr = cidr.split('/').next()?.parse().ok()?;
        (is_lan_ipv4(ip) && !is_virtual_linux_interface(name)).then_some(ip)
    })
}

/// Linux 回环与虚拟网卡（docker/veth/网桥/隧道）。
#[cfg(target_os = "linux")]
fn is_virtual_linux_interface(name: &str) -> bool {
    name == "lo"
        || ["docker", "veth", "br-", "virbr", "tun", "tap", "wg"]
            .iter()
            .any(|prefix| name.starts_with(prefix))
}

// ============================================================================
// Windows
// ============================================================================

#[cfg(target_os = "windows")]
fn platform_local_ipv4() -> Option<Ipv4Addr> {
    let output = std::process::Command::new("ipconfig").output().ok()?;
    if !output.status.success() {
        return None;
    }
    parse_ipconfig(&String::from_utf8_lossy(&output.stdout))
}

/// 解析 `ipconfig` 输出：中英文行都以 `IPv4` 开头，取冒号后的地址。
#[cfg(target_os = "windows")]
fn parse_ipconfig(output: &str) -> Option<Ipv4Addr> {
    output.lines().find_map(|line| {
        if !line.contains("IPv4") {
            return None;
        }
        parse_ipv4(line.split_once(':')?.1)
    })
}

/// 其它平台：不识别。
#[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
fn platform_local_ipv4() -> Option<Ipv4Addr> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_lan_ipv4_rejects_loopback_and_link_local() {
        for raw in [
            "127.0.0.1",
            "127.1.2.3",
            "0.0.0.0",
            "169.254.1.2",
            "224.0.0.1",
            "255.255.255.255",
        ] {
            let ip: Ipv4Addr = raw.parse().unwrap();
            assert!(!is_lan_ipv4(ip), "{raw} 不应作为本机 IP 展示");
        }
        for raw in ["192.168.1.8", "10.0.0.8", "172.16.0.2", "100.64.0.1"] {
            let ip: Ipv4Addr = raw.parse().unwrap();
            assert!(is_lan_ipv4(ip), "{raw} 应允许展示");
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_route_output_extracts_interface() {
        let sample = "\
   route to: default
destination: default
       mask: default
    gateway: 192.168.1.1
  interface: en0
      flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
";
        assert_eq!(parse_route_interface(sample).as_deref(), Some("en0"));
        assert_eq!(parse_route_interface("no interface here"), None);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_ifconfig_prefers_en0_and_skips_loopback() {
        let sample = "\
lo0: flags=8049<UP,LOOPBACK,RUNNING,MULTICAST> mtu 16384
\tinet 127.0.0.1 netmask 0xff000000
utun0: flags=8051<UP,POINTOPOINT,RUNNING,MULTICAST> mtu 1500
\tinet 10.8.0.2 --> 10.8.0.1 netmask 0xffffffff
en0: flags=8863<UP,BROADCAST,RUNNING,SIMPLEX,MULTICAST> mtu 1500
\tether aa:bb:cc:dd:ee:ff
\tinet 192.168.1.8 netmask 0xffffff00 broadcast 192.168.1.255
";
        assert_eq!(
            parse_ifconfig_inet(sample),
            Some(Ipv4Addr::new(192, 168, 1, 8)),
            "应取 en0 的局域网地址，而不是 lo0/utun0"
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_route_output_extracts_src() {
        let sample = "223.5.5.5 via 192.168.1.1 dev wlp3s0 src 192.168.1.8 uid 1000\n    cache\n";
        assert_eq!(
            parse_ip_route_src(sample),
            Some(Ipv4Addr::new(192, 168, 1, 8))
        );
        assert_eq!(parse_ip_route_src("unreachable"), None);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_ip_addr_skips_loopback_and_docker() {
        let sample = "\
1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever
3: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0\\       valid_lft forever
2: enp3s0    inet 10.0.0.8/24 brd 10.0.0.255 scope global dynamic enp3s0\\       valid_lft forever
";
        assert_eq!(parse_ip_addr(sample), Some(Ipv4Addr::new(10, 0, 0, 8)));
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn windows_ipconfig_skips_non_ipv4_lines() {
        let sample = "\
以太网适配器 以太网:

   连接特定的 DNS 后缀 . . . . . . . :
   本地链接 IPv6 地址. . . . . . . . : fe80::1%12
   IPv4 地址 . . . . . . . . . . . . : 192.168.1.8
   子网掩码  . . . . . . . . . . . . : 255.255.255.0
";
        assert_eq!(parse_ipconfig(sample), Some(Ipv4Addr::new(192, 168, 1, 8)));
    }

    /// 实机验证：本机 MAC/IP 能取到时必须是合法值、且不是回环地址。
    #[test]
    fn local_network_info_is_valid_when_available() {
        let info = local_network_info();
        eprintln!("本机 MAC: {}, IP: {}", info.mac, info.ip);
        if !info.mac.is_empty() {
            assert!(
                crate::mac::normalize_mac(&info.mac).is_some(),
                "MAC 应合法，实际为 {:?}",
                info.mac
            );
        }
        if !info.ip.is_empty() {
            let ip: Ipv4Addr = info.ip.parse().expect("ip 应是合法 IPv4");
            assert!(!ip.is_loopback(), "不应返回回环地址：{ip}");
            assert!(is_lan_ipv4(ip), "不应返回不可展示地址：{ip}");
        }
    }
}
