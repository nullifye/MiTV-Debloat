//! First-run help: getting adb (Google's Platform Tools, unpacked into the app's own folder, so
//! nothing else on the computer changes) and finding TVs on the Wi-Fi.

use crate::github;
use anyhow::{Context, Result, bail};
use std::fs;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::thread;
use std::time::Duration;

/// Google's terms for the Platform Tools download (shown before downloading).
pub const TERMS_URL: &str = "https://developer.android.com/studio/terms";

/// Google's download for this computer, or `None` where Google doesn't make one (e.g. Linux on
/// ARM: install `adb` from the distribution instead).
pub fn platform_tools_url() -> Option<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", _) => {
            Some("https://dl.google.com/android/repository/platform-tools-latest-windows.zip")
        }
        ("macos", _) => {
            Some("https://dl.google.com/android/repository/platform-tools-latest-darwin.zip")
        }
        ("linux", "x86_64") => {
            Some("https://dl.google.com/android/repository/platform-tools-latest-linux.zip")
        }
        _ => None,
    }
}

/// Running in Termux on an Android phone: adb comes from `pkg install android-tools`.
pub fn termux() -> bool {
    std::env::var_os("TERMUX_VERSION").is_some()
        || std::env::var("PREFIX").is_ok_and(|p| p.contains("com.termux"))
}

/// The phone's Downloads folder as Termux sees it (`~/storage/downloads`, there once
/// `termux-setup-storage` has been run), else `None`.
pub fn phone_downloads() -> Option<PathBuf> {
    if !termux() {
        return None;
    }
    dirs::home_dir()
        .map(|h| h.join("storage").join("downloads"))
        .filter(|d| d.is_dir())
}

/// Where the app keeps its own copy of adb.
pub fn local_adb(app_dir: &Path) -> PathBuf {
    let exe = if cfg!(windows) { "adb.exe" } else { "adb" };
    app_dir.join("platform-tools").join(exe)
}

/// Downloads Platform Tools and unpacks it into `<app_dir>/platform-tools`. Returns adb's path.
pub fn install_platform_tools(
    app_dir: &Path,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<PathBuf> {
    let Some(url) = platform_tools_url() else {
        bail!(
            "Google doesn't offer Platform Tools for this computer: install adb with your system's package manager."
        );
    };
    fs::create_dir_all(app_dir)?;
    let zip_path = app_dir.join("platform-tools.zip");
    let _ = fs::remove_file(&zip_path); // always the latest
    github::download(url, &zip_path, 0, progress).context("downloading Platform Tools")?;
    let target = app_dir.join("platform-tools");
    if target.exists() {
        fs::remove_dir_all(&target).ok();
    }
    unzip(&zip_path, app_dir)?;
    let _ = fs::remove_file(&zip_path);
    let adb = local_adb(app_dir);
    if !adb.is_file() {
        bail!("the download didn't contain adb");
    }
    Ok(adb)
}

/// Unpacks a zip into `dest`, keeping Unix permissions (adb must stay executable) and refusing
/// paths that would land outside `dest`.
pub fn unzip(zip_path: &Path, dest: &Path) -> Result<()> {
    let file = fs::File::open(zip_path)?;
    let mut archive = zip::ZipArchive::new(file).context("not a valid zip file")?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)?;
        let Some(rel) = entry.enclosed_name() else {
            continue;
        };
        let out = dest.join(rel);
        if entry.is_dir() {
            fs::create_dir_all(&out)?;
            continue;
        }
        if let Some(parent) = out.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut f = fs::File::create(&out).with_context(|| format!("writing {}", out.display()))?;
        io::copy(&mut entry, &mut f)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = entry.unix_mode().unwrap_or(0o644);
            // Executables in Platform Tools (adb, fastboot) keep their x bit.
            fs::set_permissions(&out, fs::Permissions::from_mode(mode & 0o777 | 0o400))?;
        }
    }
    Ok(())
}

/// This computer's address on the local network (no packets are sent: connecting a UDP socket
/// only picks the route).
pub fn local_ipv4() -> Option<Ipv4Addr> {
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("8.8.8.8:80").ok()?;
    match s.local_addr().ok()? {
        SocketAddr::V4(a) if !a.ip().is_loopback() && !a.ip().is_unspecified() => Some(*a.ip()),
        _ => None,
    }
}

/// The other addresses in the same /24 network (home routers use /24 almost always).
pub fn neighbours(me: Ipv4Addr) -> Vec<Ipv4Addr> {
    let [a, b, c, _] = me.octets();
    (1..=254u8)
        .map(|d| Ipv4Addr::new(a, b, c, d))
        .filter(|ip| *ip != me)
        .collect()
}

/// Addresses with `port` open (5555: network debugging), checked in parallel. `progress` gets
/// (checked, total).
pub fn scan(
    hosts: &[Ipv4Addr],
    port: u16,
    timeout: Duration,
    progress: &(dyn Fn(usize, usize) + Sync),
) -> Vec<Ipv4Addr> {
    let total = hosts.len();
    let done = Arc::new(AtomicUsize::new(0));
    let found = Arc::new(std::sync::Mutex::new(Vec::new()));
    let chunks: Vec<Vec<Ipv4Addr>> = hosts
        .chunks(total.div_ceil(48).max(1))
        .map(|c| c.to_vec())
        .collect();
    thread::scope(|s| {
        for chunk in chunks {
            let done = done.clone();
            let found = found.clone();
            s.spawn(move || {
                for ip in chunk {
                    if TcpStream::connect_timeout(&SocketAddr::from((ip, port)), timeout).is_ok() {
                        found.lock().unwrap().push(ip);
                    }
                    let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                    progress(n, total);
                }
            });
        }
    });
    let mut v = Arc::try_unwrap(found)
        .map(|m| m.into_inner().unwrap())
        .unwrap_or_default();
    v.sort();
    v
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn there_is_a_download_for_this_computer() {
        // CI and development run on Windows, macOS and x86_64 Linux.
        assert!(
            platform_tools_url()
                .unwrap()
                .starts_with("https://dl.google.com/")
        );
    }

    #[test]
    fn neighbours_are_the_rest_of_the_slash_24() {
        let n = neighbours(Ipv4Addr::new(192, 168, 0, 12));
        assert_eq!(n.len(), 253);
        assert_eq!(n[0], Ipv4Addr::new(192, 168, 0, 1));
        assert!(!n.contains(&Ipv4Addr::new(192, 168, 0, 12)));
        assert_eq!(*n.last().unwrap(), Ipv4Addr::new(192, 168, 0, 254));
    }

    #[test]
    fn scan_finds_an_open_port() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let hosts = [Ipv4Addr::new(127, 0, 0, 1), Ipv4Addr::new(127, 0, 0, 2)];
        let calls = AtomicUsize::new(0);
        let found = scan(&hosts, port, Duration::from_millis(300), &|_, _| {
            calls.fetch_add(1, Ordering::Relaxed);
        });
        assert!(found.contains(&Ipv4Addr::new(127, 0, 0, 1)));
        assert_eq!(calls.load(Ordering::Relaxed), 2);
    }

    #[test]
    fn unzip_keeps_permissions_and_refuses_escapes() {
        let dir = std::env::temp_dir().join(format!("tvhome-unzip-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let zip_path = dir.join("t.zip");
        {
            let mut w = zip::ZipWriter::new(fs::File::create(&zip_path).unwrap());
            let opts = zip::write::SimpleFileOptions::default().unix_permissions(0o755);
            w.start_file("platform-tools/adb", opts).unwrap();
            w.write_all(b"#!/bin/sh\n").unwrap();
            w.start_file("../evil", zip::write::SimpleFileOptions::default())
                .unwrap();
            w.write_all(b"x").unwrap();
            w.finish().unwrap();
        }
        let out = dir.join("out");
        unzip(&zip_path, &out).unwrap();
        let adb = out.join("platform-tools/adb");
        assert!(adb.is_file());
        assert!(!dir.join("evil").exists());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&adb).unwrap().permissions().mode() & 0o111,
                0o111
            );
        }
        assert_eq!(
            local_adb(&out),
            out.join("platform-tools")
                .join(if cfg!(windows) { "adb.exe" } else { "adb" })
        );
    }
}
