//! Best-effort power observations and LAN wake methods. A missing ADB connection alone never
//! proves the TV is electrically off; labels describe what was observed, not a mains measurement.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct TvPowerConfig {
    pub wifi_mac: String,
    pub ethernet_mac: String,
    // Keep old IR/HDMI config keys when settings are re-saved, but never offer or execute them.
    #[serde(flatten)]
    pub legacy: BTreeMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Online,
    Standby,
    Offline,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WakeKind {
    Adb,
    Wifi,
    Ethernet,
}

impl WakeKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::Adb => "ADB Wake Up (reachable TV)",
            Self::Wifi => "Wake-on-Wi-Fi",
            Self::Ethernet => "Wired Wake-on-LAN",
        }
    }
}

impl State {
    pub fn label(self) -> &'static str {
        match self {
            Self::Online => "Online",
            Self::Standby => "Standby",
            Self::Offline => "Offline",
            Self::Unknown => "Unknown",
        }
    }
}

/// Android's own screen/wakefulness report is stronger evidence than an ADB transport state.
pub fn classify(adb_connected: bool, power_dump: Option<&str>) -> (State, &'static str) {
    if !adb_connected {
        return (
            State::Offline,
            "ADB/network unreachable; this does not prove the TV is powered off",
        );
    }
    let Some(dump) = power_dump else {
        return (
            State::Unknown,
            "ADB responds but the power state could not be read",
        );
    };
    if dump.lines().any(|l| {
        matches!(
            l.trim(),
            "mWakefulness=Awake" | "mWakefulness=Dreaming" | "mInteractive=true"
        )
    }) {
        (State::Online, "Android reports screen awake")
    } else if dump.lines().any(|l| {
        matches!(
            l.trim(),
            "mWakefulness=Asleep" | "mWakefulness=Dozing" | "mInteractive=false"
        )
    }) {
        (State::Standby, "Android reports screen asleep or dozing")
    } else {
        (
            State::Unknown,
            "ADB responds, but Android did not report a recognised power state",
        )
    }
}

pub fn parse_mac(s: &str) -> Result<[u8; 6], String> {
    let parts: Vec<&str> = s.split(':').collect();
    if parts.len() != 6 || parts.iter().any(|p| p.len() != 2) {
        return Err("Use six hexadecimal pairs, e.g. AA:BB:CC:DD:EE:FF".into());
    }
    let mut mac = [0; 6];
    for (i, p) in parts.iter().enumerate() {
        mac[i] = u8::from_str_radix(p, 16).map_err(|_| "MAC contains a non-hexadecimal digit")?;
    }
    if mac == [0; 6] || mac[0] & 1 != 0 {
        return Err("Use a device's unicast MAC address".into());
    }
    Ok(mac)
}

/// Only the *current interface* MAC, never a nearby access point's BSSID or a saved network's
/// randomized MAC. Android may redact this on some TVs; then manual entry remains available.
pub fn wifi_mac_from_dump(dump: &str) -> Option<String> {
    dump.lines()
        .find(|line| {
            line.trim_start().starts_with("mWifiInfo ")
                && line.contains("Supplicant state: COMPLETED")
        })
        .and_then(|line| {
            line.split_once("MAC: ")
                .map(|(_, tail)| tail.split([',', ' ']).next().unwrap_or(""))
        })
        .filter(|mac| parse_mac(mac).is_ok())
        .map(str::to_string)
}

pub fn ethernet_mac_from_dump(dump: &str) -> Option<String> {
    dump.lines()
        .map(str::trim_start)
        .find(|line| line.starts_with("eth0:") && line.contains("hwAddress: "))
        .and_then(|line| {
            line.split_once("hwAddress: ")
                .map(|(_, tail)| tail.split([',', ' ']).next().unwrap_or(""))
        })
        .filter(|mac| parse_mac(mac).is_ok())
        .map(str::to_string)
}

pub fn magic_packet(mac: [u8; 6]) -> [u8; 102] {
    let mut packet = [0xff; 102];
    for n in 0..16 {
        packet[6 + n * 6..12 + n * 6].copy_from_slice(&mac);
    }
    packet
}

/// Broadcast on each local IPv4 /24, rather than assuming the TV's old IP is still assigned.
pub fn wake_lan(mac: &str, broadcast: Ipv4Addr) -> Result<(), String> {
    let packet = magic_packet(parse_mac(mac)?);
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).map_err(|e| e.to_string())?;
    socket.set_broadcast(true).map_err(|e| e.to_string())?;
    for port in [9, 7] {
        socket
            .send_to(&packet, SocketAddr::new(IpAddr::V4(broadcast), port))
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn states() {
        assert_eq!(
            classify(true, Some("mWakefulness=Awake\n")).0,
            State::Online
        );
        assert_eq!(
            classify(true, Some("mWakefulness=Asleep\n")).0,
            State::Standby
        );
        assert_eq!(
            classify(true, Some("mWakefulness=Dreaming\n")).0,
            State::Online
        );
        assert_eq!(classify(true, Some("garbled\n")).0, State::Unknown);
        assert_eq!(classify(false, None).0, State::Offline);
    }
    #[test]
    fn packet() {
        let m = parse_mac("02:ab:00:11:22:ff").unwrap();
        let p = magic_packet(m);
        assert_eq!(&p[..6], &[255; 6]);
        assert_eq!(&p[6..12], &m);
        assert_eq!(&p[96..], &m);
        assert!(parse_mac("ff:ff:ff:ff:ff:ff").is_err());
    }
    #[test]
    fn reads_interface_mac_not_router_or_saved_randomized_mac() {
        let wifi = "mWifiInfo SSID: tv, BSSID: aa:bb:cc:dd:ee:01, MAC: 78:f2:35:a7:97:0f, Supplicant state: COMPLETED, RSSI: -44\n mRandomizedMacAddress: 82:a8:dd:db:e6:d5\n";
        assert_eq!(
            wifi_mac_from_dump(wifi).as_deref(),
            Some("78:f2:35:a7:97:0f")
        );
        assert_eq!(
            wifi_mac_from_dump(
                "mWifiInfo SSID: <unknown>, MAC: 02:01:02:03:04:05, Supplicant state: DISCONNECTED"
            ),
            None
        );
        assert_eq!(ethernet_mac_from_dump("  eth0:NetworkInterfaceState{ iface: eth0, up: false, hwAddress: 04:7a:0b:8c:76:44, networkInfo: [] }").as_deref(), Some("04:7a:0b:8c:76:44"));
    }
    #[test]
    fn old_http_settings_are_preserved_but_not_active() {
        let value = serde_json::json!({"wifi_mac":"", "ethernet_mac":"", "ir":{"url":"http://blaster.local/on"}, "hdmi":null});
        let config: TvPowerConfig = serde_json::from_value(value.clone()).unwrap();
        assert_eq!(
            config.legacy.get("ir").unwrap()["url"],
            "http://blaster.local/on"
        );
        assert_eq!(serde_json::to_value(config).unwrap(), value);
    }
}
