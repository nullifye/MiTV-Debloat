//! Reading the TV: pure parsers for adb's output (unit tested), and the facts the Status screen
//! shows.

use std::collections::{BTreeMap, BTreeSet};

/// The launcher this project installs.
pub const PKG: &str = "my.rafhan.app.tvhome";
pub const HOME_ACTIVITY: &str = "my.rafhan.app.tvhome/.MainActivity";
pub const A11Y_SERVICE: &str =
    "my.rafhan.app.tvhome/my.rafhan.app.tvhome.RemoteAccessibilityService";
/// Google TV's setup app and launcher: both answer Home (see the Google TV handover).
pub const GTV_SETUP: &str = "com.google.android.tungsten.setupwraith";
pub const GTV_LAUNCHER: &str = "com.google.android.apps.tv.launcherx";
pub const REMOTE_PORT: u16 = 8486;

/// One line of `adb devices -l`.
#[derive(Debug, Clone, PartialEq)]
pub struct DeviceLine {
    pub serial: String,
    /// `device`, `unauthorized`, `offline`, …
    pub state: String,
    pub model: Option<String>,
}

pub fn parse_devices(out: &str) -> Vec<DeviceLine> {
    out.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with("List of devices") && !l.starts_with('*'))
        .filter_map(|l| {
            let mut it = l.split_whitespace();
            let serial = it.next()?.to_string();
            let state = it.next()?.to_string();
            let model = it
                .find_map(|t| t.strip_prefix("model:"))
                .map(|m| m.replace('_', " "));
            Some(DeviceLine {
                serial,
                state,
                model,
            })
        })
        .collect()
}

/// `ip` → `ip:5555`; `ip:port` stays.
pub fn normalize_address(input: &str) -> Option<String> {
    let s = input.trim();
    if s.is_empty() || s.contains(char::is_whitespace) {
        return None;
    }
    let (host, port) = match s.rsplit_once(':') {
        Some((h, p)) => (h, p.parse::<u16>().ok()?),
        None => (s, 5555),
    };
    let valid_host = !host.is_empty()
        && host
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-');
    valid_host.then(|| format!("{host}:{port}"))
}

/// `adb connect` prints "connected to …" or "already connected to …" on success, and "failed to
/// connect …" / "cannot connect …" otherwise (often still with exit code 0).
pub fn connect_succeeded(out: &str) -> bool {
    let o = out.to_lowercase();
    (o.contains("connected to") && !o.contains("failed") && !o.contains("cannot"))
        || o.contains("already connected")
}

/// `[key]: [value]` lines of `getprop`.
pub fn parse_getprop(out: &str) -> BTreeMap<String, String> {
    out.lines()
        .filter_map(|l| {
            let l = l.trim();
            let (k, v) = l.split_once("]: [")?;
            Some((
                k.trim_start_matches('[').to_string(),
                v.trim_end_matches(']').to_string(),
            ))
        })
        .collect()
}

/// `package:<name>` lines of `pm list packages` (with `-f`, `package:<path>=<name>`).
pub fn parse_packages(out: &str) -> BTreeSet<String> {
    out.lines()
        .filter_map(|l| l.trim().strip_prefix("package:"))
        .map(|rest| {
            rest.rsplit_once('=')
                .map(|(_, n)| n)
                .unwrap_or(rest)
                .to_string()
        })
        .filter(|p| !p.is_empty())
        .collect()
}

/// The component on the last line of `cmd package resolve-activity --brief …` (`pkg/.Activity`).
pub fn parse_resolved_component(out: &str) -> Option<String> {
    out.lines()
        .map(str::trim)
        .rev()
        .find(|l| !l.is_empty())
        .filter(|l| l.contains('/') && !l.contains(' '))
        .map(str::to_string)
}

/// Whether a resolved HOME component is worth recording as "the TV's own launcher": not ours,
/// not Android's chooser, not the boot-time fallback.
pub fn is_recordable_stock_home(component: &str) -> bool {
    !component.starts_with(&format!("{PKG}/"))
        && !component.contains("ResolverActivity")
        && !component.contains("FallbackHome")
        && component.contains('/')
}

/// Home apps with their priority, from `cmd package query-activities --brief …`: each component
/// line follows a `priority=N …` line. Android sends Home to the highest priority; only between
/// equals does the choice made with `set-home-activity` count. Installed apps get 0, Google TV's
/// launcher 2 and its setup app 1.
pub fn parse_home_priorities(out: &str) -> Vec<(String, i32)> {
    let mut prio = 0;
    let mut v = Vec::new();
    for l in out.lines().map(str::trim) {
        if let Some(rest) = l.strip_prefix("priority=") {
            prio = rest
                .split_whitespace()
                .next()
                .and_then(|n| n.parse().ok())
                .unwrap_or(0);
        } else if l.contains('/') && !l.contains(' ') {
            v.push((l.to_string(), prio));
            prio = 0;
        }
    }
    v
}

/// Components listed by `cmd package query-activities --brief …` (lines containing `/`).
pub fn parse_components(out: &str) -> Vec<String> {
    out.lines()
        .map(str::trim)
        .filter(|l| l.contains('/') && !l.contains(' '))
        .map(str::to_string)
        .collect()
}

pub fn package_of(component: &str) -> &str {
    component.split('/').next().unwrap_or(component)
}

/// The focused app from `dumpsys window` (`mFocusedApp=…`), falling back to
/// `dumpsys activity activities` (`ResumedActivity: …`): the package name only.
pub fn parse_focused_package(dump: &str) -> Option<String> {
    let line = dump
        .lines()
        .find(|l| l.contains("mFocusedApp="))
        .or_else(|| dump.lines().find(|l| l.contains("ResumedActivity")))
        .or_else(|| dump.lines().find(|l| l.contains("mCurrentFocus=")))?;
    // "… ActivityRecord{231d57e u0 my.rafhan.app.tvhome/.MainActivity t13969}" or
    // "mCurrentFocus=Window{7b95bbc u0 my.rafhan.app.tvhome/my.rafhan.app.tvhome.MainActivity}"
    line.split_whitespace()
        .find(|t| t.contains('/'))
        .map(|t| package_of(t.trim_end_matches('}')).to_string())
}

/// `versionName=1.9.1` from `dumpsys package <pkg>`.
pub fn parse_version_name(dump: &str) -> Option<String> {
    dump.lines()
        .find_map(|l| l.trim().strip_prefix("versionName="))
        .map(|v| v.trim().to_string())
}

/// `versionCode=8 minSdk=29 …` from `dumpsys package <pkg>`.
pub fn parse_version_code(dump: &str) -> Option<u64> {
    dump.lines().find_map(|l| {
        let rest = l.trim().strip_prefix("versionCode=")?;
        rest.split_whitespace().next()?.parse().ok()
    })
}

/// A `settings get` answer: `null` and empty both mean "not set".
pub fn setting_value(out: &str) -> Option<String> {
    let v = out.trim();
    (!v.is_empty() && v != "null").then(|| v.to_string())
}

/// `enabled_accessibility_services` with our service added (others kept, order kept). `None`
/// when it's already there.
pub fn a11y_with_ours(current: Option<&str>) -> Option<String> {
    match current {
        None => Some(A11Y_SERVICE.to_string()),
        Some(c) if c.split(':').any(|s| s == A11Y_SERVICE) => None,
        Some(c) => Some(format!("{c}:{A11Y_SERVICE}")),
    }
}

/// `enabled_accessibility_services` without ours: `Some("")` means delete the setting, `None`
/// means nothing to change. Matches restore.sh, which drops every entry starting `<PKG>/`.
pub fn a11y_without_ours(current: Option<&str>) -> Option<String> {
    let c = current?;
    let kept: Vec<&str> = c
        .split(':')
        .filter(|s| !s.is_empty() && !s.starts_with(&format!("{PKG}/")))
        .collect();
    let joined = kept.join(":");
    (joined != c).then_some(joined)
}

/// The package part of `settings get secure default_input_method` (`pkg/.Service`).
pub fn ime_package(out: &str) -> Option<String> {
    setting_value(out).map(|v| package_of(&v).to_string())
}

/// `pm install` / `pm uninstall` outcome.
#[derive(Debug, PartialEq)]
pub enum InstallResult {
    Success,
    /// A copy signed with another key is installed (GitHub Release vs self-built).
    Incompatible,
    Failed(String),
}

pub fn parse_install(out: &str) -> InstallResult {
    if out.contains("UPDATE_INCOMPATIBLE") {
        InstallResult::Incompatible
    } else if out.lines().any(|l| l.trim() == "Success") {
        InstallResult::Success
    } else {
        InstallResult::Failed(out.trim().to_string())
    }
}

/// The TV's IPv4 address from `ip -f inet addr show wlan0` / `eth0` (`inet 192.168.0.44/24 …`).
pub fn parse_inet(out: &str) -> Option<String> {
    out.split_whitespace()
        .skip_while(|t| *t != "inet")
        .nth(1)
        .map(|a| a.split('/').next().unwrap_or(a).to_string())
}

/// How a TV was found on the network.
#[derive(Debug, Clone, PartialEq)]
pub enum FoundKind {
    /// Port 5555 open: classic network debugging.
    Network,
    /// Wireless debugging, already paired: connect directly.
    Wireless,
    /// Wireless debugging waiting to be paired with a code.
    Pairing,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Found {
    pub addr: String,
    pub kind: FoundKind,
}

/// `adb mdns services`: newer Google TVs announce Wireless debugging this way
/// (`adb-<serial>-<x>  _adb-tls-connect._tcp  192.168.1.50:37423`).
pub fn parse_mdns(out: &str) -> Vec<Found> {
    let mut v: Vec<Found> = out
        .lines()
        .filter_map(|l| {
            let parts: Vec<&str> = l.split_whitespace().collect();
            let (service, addr) = (parts.get(1)?, parts.get(2)?);
            normalize_address(addr).filter(|_| addr.contains(':'))?;
            let kind = if service.contains("tls-pairing") {
                FoundKind::Pairing
            } else if service.contains("tls-connect") {
                FoundKind::Wireless
            } else if service.starts_with("_adb.") || service.starts_with("_adb._tcp") {
                FoundKind::Network
            } else {
                return None;
            };
            Some(Found {
                addr: addr.to_string(),
                kind,
            })
        })
        .collect();
    v.dedup();
    v
}

/// A stable name for a TV, for keeping its records apart: its serial number, else the address.
pub fn tv_id(props: &BTreeMap<String, String>, serial: &str) -> String {
    ["ro.serialno", "ro.boot.serialno", "persist.sys.serialno"]
        .iter()
        .filter_map(|k| props.get(*k))
        .find(|v| !v.is_empty() && v.as_str() != "unknown" && v.chars().any(|c| c != '0'))
        .cloned()
        .unwrap_or_else(|| serial.split(':').next().unwrap_or(serial).to_string())
}

/// A name people recognise for an app package: well-known ones by name, others from the package
/// (`com.example.radioplayer` → "Radioplayer").
pub fn friendly_app_name(pkg: &str) -> String {
    const KNOWN: &[(&str, &str)] = &[
        ("com.google.android.youtube.tv", "YouTube"),
        ("com.google.android.youtube.tvkids", "YouTube Kids"),
        ("com.netflix.ninja", "Netflix"),
        ("com.amazon.amazonvideo.livingroom", "Prime Video"),
        ("com.disney.disneyplus", "Disney+"),
        ("com.spotify.tv.android", "Spotify"),
        ("com.mediatek.wwtv.tvcenter", "Live TV"),
        ("com.android.tv", "Live TV"),
        ("com.android.tv.settings", "Settings"),
        ("com.google.android.apps.tv.launcherx", "Google TV home"),
        ("com.google.android.tvlauncher", "Android TV home"),
        (PKG, "TV Home"),
    ];
    if let Some((_, n)) = KNOWN.iter().find(|(p, _)| *p == pkg) {
        return n.to_string();
    }
    const GENERIC: &[&str] = &[
        "com", "org", "net", "tv", "app", "apps", "android", "google", "my", "main",
    ];
    let part = pkg
        .split('.')
        .rev()
        .find(|p| !GENERIC.contains(p) && p.len() > 2)
        .unwrap_or(pkg);
    let mut c = part.chars();
    match c.next() {
        Some(f) => f.to_uppercase().chain(c).collect(),
        None => pkg.to_string(),
    }
}

/// Everything the Status screen shows about the connected TV.
#[derive(Debug, Clone, Default)]
pub struct TvInfo {
    pub serial: String,
    /// Serial number (see `tv_id`): which backup folder is this TV's.
    pub id: String,
    pub state: String,
    pub model: String,
    pub brand: String,
    pub android: String,
    pub sdk: u32,
    pub ip: Option<String>,
    pub launcher_version: Option<String>,
    pub launcher_code: Option<u64>,
    pub home: Option<String>,
    pub focused: Option<String>,
    pub a11y_on: bool,
    pub google_tv: bool,
}

impl TvInfo {
    pub fn home_is_ours(&self) -> bool {
        self.home.as_deref().map(package_of) == Some(PKG)
    }

    /// Someone is probably watching: an app other than the home screen is in front.
    pub fn busy_with(&self) -> Option<&str> {
        let f = self.focused.as_deref()?;
        let home_pkg = self.home.as_deref().map(package_of);
        (f != PKG && Some(f) != home_pkg && f != "com.android.systemui").then_some(f)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn devices_list() {
        let out = "List of devices attached\n192.168.0.44:5555\tdevice product:MOOQ0 model:MiTV_MOOQ0 device:x transport_id:3\n10.0.0.2:5555\tunauthorized transport_id:4\n\n";
        let d = parse_devices(out);
        assert_eq!(d.len(), 2);
        assert_eq!(d[0].serial, "192.168.0.44:5555");
        assert_eq!(d[0].state, "device");
        assert_eq!(d[0].model.as_deref(), Some("MiTV MOOQ0"));
        assert_eq!(d[1].state, "unauthorized");
        assert!(
            parse_devices("* daemon started successfully\nList of devices attached\n").is_empty()
        );
    }

    #[test]
    fn addresses() {
        assert_eq!(
            normalize_address(" 192.168.0.44 ").as_deref(),
            Some("192.168.0.44:5555")
        );
        assert_eq!(
            normalize_address("192.168.0.44:40123").as_deref(),
            Some("192.168.0.44:40123")
        );
        assert_eq!(
            normalize_address("tv.local").as_deref(),
            Some("tv.local:5555")
        );
        assert_eq!(normalize_address(""), None);
        assert_eq!(normalize_address("1.2.3.4:99999"), None);
        assert_eq!(normalize_address("1.2.3.4; rm -rf"), None);
    }

    #[test]
    fn connect_outputs() {
        assert!(connect_succeeded("connected to 192.168.0.44:5555"));
        assert!(connect_succeeded("already connected to 192.168.0.44:5555"));
        assert!(!connect_succeeded(
            "failed to connect to '192.168.0.9:5555': No route to host"
        ));
        assert!(!connect_succeeded(
            "cannot connect to 192.168.0.9:5555: Connection refused"
        ));
    }

    #[test]
    fn getprop() {
        let p = parse_getprop(
            "[ro.build.version.sdk]: [29]\n[ro.product.model]: [MiTV-MOOQ0]\r\n[empty]: []\n",
        );
        assert_eq!(p["ro.build.version.sdk"], "29");
        assert_eq!(p["ro.product.model"], "MiTV-MOOQ0");
        assert_eq!(p["empty"], "");
    }

    #[test]
    fn packages() {
        let s = parse_packages("package:com.a\r\npackage:/system/app/B/B.apk=com.b\nnoise\n");
        assert!(s.contains("com.a") && s.contains("com.b") && s.len() == 2);
    }

    #[test]
    fn resolved_home() {
        let out = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\ncom.google.android.apps.tv.launcherx/.home.HomeActivity\n";
        let c = parse_resolved_component(out).unwrap();
        assert_eq!(c, "com.google.android.apps.tv.launcherx/.home.HomeActivity");
        assert!(is_recordable_stock_home(&c));
        assert!(!is_recordable_stock_home(
            "my.rafhan.app.tvhome/.MainActivity"
        ));
        assert!(!is_recordable_stock_home(
            "android/com.android.internal.app.ResolverActivity"
        ));
        assert!(!is_recordable_stock_home(
            "com.android.tv.settings/.system.FallbackHome"
        ));
        assert_eq!(parse_resolved_component("No activity found\n"), None);
    }

    #[test]
    fn home_candidates() {
        let out = "2 activities found:\n  com.duokan.factorytest/.FactoryLauncher\n  com.android.tv.settings/.system.FallbackHome\n";
        assert_eq!(parse_components(out).len(), 2);
    }

    #[test]
    fn focused_package_android_10_and_14() {
        let a10 = "  mCurrentFocus=Window{e4727cb u0 my.rafhan.app.tvhome/my.rafhan.app.tvhome.MainActivity}\n  mFocusedApp=AppWindowToken{e623ad7 token=Token{c9b3a56 ActivityRecord{689c71 u0 my.rafhan.app.tvhome/.MainActivity t13963}}}\n";
        assert_eq!(
            parse_focused_package(a10).as_deref(),
            Some("my.rafhan.app.tvhome")
        );
        let a14 = "  mFocusedApp=ActivityRecord{abc u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.MainActivity t5}\n";
        assert_eq!(
            parse_focused_package(a14).as_deref(),
            Some("com.google.android.youtube.tv")
        );
        let resumed = "    ResumedActivity: ActivityRecord{1 u0 com.x/.Main t1}\n";
        assert_eq!(parse_focused_package(resumed).as_deref(), Some("com.x"));
        assert_eq!(parse_focused_package("nothing here"), None);
    }

    #[test]
    fn versions() {
        let d = "    versionCode=8 minSdk=29 targetSdk=36\n    versionName=1.9.1\n";
        assert_eq!(parse_version_name(d).as_deref(), Some("1.9.1"));
        assert_eq!(parse_version_code(d), Some(8));
        assert_eq!(parse_version_name("Unable to find package"), None);
    }

    #[test]
    fn accessibility_append_keeps_others() {
        assert_eq!(a11y_with_ours(None).as_deref(), Some(A11Y_SERVICE));
        assert_eq!(
            a11y_with_ours(Some("flar2.homebutton/a.i")).unwrap(),
            format!("flar2.homebutton/a.i:{A11Y_SERVICE}")
        );
        assert_eq!(a11y_with_ours(Some(&format!("x/y:{A11Y_SERVICE}"))), None);
    }

    #[test]
    fn accessibility_remove_like_restore_sh() {
        let both = format!("flar2.homebutton/a.i:{A11Y_SERVICE}");
        assert_eq!(
            a11y_without_ours(Some(&both)).as_deref(),
            Some("flar2.homebutton/a.i")
        );
        assert_eq!(a11y_without_ours(Some(A11Y_SERVICE)).as_deref(), Some(""));
        assert_eq!(a11y_without_ours(Some("flar2.homebutton/a.i")), None);
        assert_eq!(a11y_without_ours(None), None);
        assert_eq!(setting_value("null\n"), None);
        assert_eq!(setting_value(" 1 \n").as_deref(), Some("1"));
    }

    #[test]
    fn home_priorities() {
        let out = "5 activities found:\n  Activity #0:\n    priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    com.google.android.apps.tv.launcherx/.home.HomeActivity\n  Activity #1:\n    priority=1 preferredOrder=0 match=0x108000\n    com.google.android.tungsten.setupwraith/.RecoveryActivity\n  Activity #2:\n    priority=0 preferredOrder=0\n    my.rafhan.app.tvhome/.MainActivity\n  Activity #3:\n    priority=-1000 preferredOrder=0\n    com.android.tv.settings/.system.FallbackHome\n";
        assert_eq!(
            parse_home_priorities(out),
            vec![
                (
                    "com.google.android.apps.tv.launcherx/.home.HomeActivity".to_string(),
                    2
                ),
                (
                    "com.google.android.tungsten.setupwraith/.RecoveryActivity".to_string(),
                    1
                ),
                ("my.rafhan.app.tvhome/.MainActivity".to_string(), 0),
                (
                    "com.android.tv.settings/.system.FallbackHome".to_string(),
                    -1000
                ),
            ]
        );
        // Plain lists (older Android): priority unknown, taken as 0.
        assert_eq!(parse_home_priorities("com.a/.Home\n")[0].1, 0);
    }

    #[test]
    fn install_results() {
        assert_eq!(
            parse_install("Performing Streamed Install\nSuccess\n"),
            InstallResult::Success
        );
        assert_eq!(
            parse_install(
                "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package x signatures do not match]"
            ),
            InstallResult::Incompatible
        );
        assert!(matches!(
            parse_install("Failure [INSTALL_FAILED_OLDER_SDK]"),
            InstallResult::Failed(_)
        ));
    }

    #[test]
    fn inet_and_ime() {
        assert_eq!(
            parse_inet(
                "3: wlan0: <UP>\n    inet 192.168.0.44/24 brd 192.168.0.255 scope global wlan0\n"
            )
            .as_deref(),
            Some("192.168.0.44")
        );
        assert_eq!(parse_inet(""), None);
        assert_eq!(
            ime_package(
                "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME\n"
            )
            .as_deref(),
            Some("com.google.android.inputmethod.latin")
        );
    }

    #[test]
    fn mdns_services() {
        let out = "List of discovered mdns services\nadb-SONY123-AbCd\t_adb-tls-connect._tcp\t192.168.1.50:37423\nadb-SONY123-AbCd\t_adb-tls-pairing._tcp\t192.168.1.50:41235\nweird line\n";
        let f = parse_mdns(out);
        assert_eq!(
            f,
            vec![
                Found {
                    addr: "192.168.1.50:37423".into(),
                    kind: FoundKind::Wireless
                },
                Found {
                    addr: "192.168.1.50:41235".into(),
                    kind: FoundKind::Pairing
                },
            ]
        );
        assert!(parse_mdns("ERROR: mdns discovery not supported").is_empty());
    }

    #[test]
    fn tv_ids() {
        let mut p = BTreeMap::new();
        assert_eq!(tv_id(&p, "192.168.0.44:5555"), "192.168.0.44");
        p.insert("ro.serialno".to_string(), "0000000000".to_string());
        assert_eq!(
            tv_id(&p, "192.168.0.44:5555"),
            "192.168.0.44",
            "an all-zero serial isn't an id"
        );
        p.insert("ro.boot.serialno".to_string(), "MOOQ0ABC".to_string());
        assert_eq!(tv_id(&p, "x"), "MOOQ0ABC");
    }

    #[test]
    fn friendly_names() {
        assert_eq!(
            friendly_app_name("com.google.android.youtube.tv"),
            "YouTube"
        );
        assert_eq!(
            friendly_app_name("my.rafhan.app.radiostream"),
            "Radiostream"
        );
        assert_eq!(friendly_app_name("com.mxtech.videoplayer.pro"), "Pro");
        assert_eq!(friendly_app_name(PKG), "TV Home");
    }

    #[test]
    fn busy_detection() {
        let mut t = TvInfo {
            home: Some(HOME_ACTIVITY.into()),
            focused: Some(PKG.into()),
            ..Default::default()
        };
        assert_eq!(t.busy_with(), None);
        t.focused = Some("com.google.android.youtube.tv".into());
        assert_eq!(t.busy_with(), Some("com.google.android.youtube.tv"));
    }
}
