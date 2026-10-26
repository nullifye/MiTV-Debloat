//! GitHub Releases: the list of published launcher versions and downloading an APK, with progress
//! and a SHA-256 to compare against the release page.

use anyhow::{Context, Result, bail};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::cmp::Ordering;
use std::fs;
use std::io::{Read, Write};
use std::path::Path;
use std::time::Duration;

pub const DEFAULT_REPO: &str = "nullifye/MiTV-Debloat";
const UA: &str = concat!("tvhome-tui/", env!("CARGO_PKG_VERSION"));

#[derive(Debug, Clone, PartialEq)]
pub struct Asset {
    pub name: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Release {
    pub tag: String,
    /// The tag without its leading `v`.
    pub version: String,
    pub title: String,
    /// `YYYY-MM-DD`.
    pub date: String,
    pub notes: String,
    pub prerelease: bool,
    /// The launcher APK (`tvhome-<version>.apk`), if the release has one.
    pub apk: Option<Asset>,
}

#[derive(Deserialize)]
struct ApiRelease {
    tag_name: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    published_at: Option<String>,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    assets: Vec<ApiAsset>,
}

#[derive(Deserialize)]
struct ApiAsset {
    name: String,
    size: u64,
    browser_download_url: String,
}

/// Releases from the GitHub API's JSON, newest first, drafts left out.
pub fn parse_releases(json: &str) -> Result<Vec<Release>> {
    let api: Vec<ApiRelease> =
        serde_json::from_str(json).context("unexpected reply from GitHub")?;
    Ok(api
        .into_iter()
        .filter(|r| !r.draft)
        .map(|r| {
            let apk = r
                .assets
                .into_iter()
                .find(|a| a.name.starts_with("tvhome-") && a.name.ends_with(".apk"))
                .map(|a| Asset {
                    name: a.name,
                    size: a.size,
                    url: a.browser_download_url,
                });
            Release {
                version: r.tag_name.trim_start_matches('v').to_string(),
                title: r.name.unwrap_or_else(|| r.tag_name.clone()),
                tag: r.tag_name,
                date: r
                    .published_at
                    .unwrap_or_default()
                    .chars()
                    .take(10)
                    .collect(),
                notes: r.body.unwrap_or_default(),
                prerelease: r.prerelease,
                apk,
            }
        })
        .collect())
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(600)))
        .build()
        .into()
}

pub fn fetch_releases(repo: &str) -> Result<Vec<Release>> {
    let url = format!("https://api.github.com/repos/{repo}/releases?per_page=20");
    let mut resp = agent()
        .get(&url)
        .header("User-Agent", UA)
        .header("Accept", "application/vnd.github+json")
        .call()
        .with_context(|| format!("couldn't reach GitHub ({url})"))?;
    let body = resp
        .body_mut()
        .read_to_string()
        .context("reading GitHub's reply")?;
    parse_releases(&body)
}

/// Downloads to `dest` (via a `.part` file), checks the size and returns the SHA-256 (hex).
/// A finished download already there with the right size is reused.
pub fn download(
    url: &str,
    dest: &Path,
    expected: u64,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<String> {
    if let Some(dir) = dest.parent() {
        fs::create_dir_all(dir)?;
    }
    if expected > 0
        && fs::metadata(dest)
            .map(|m| m.len() == expected)
            .unwrap_or(false)
    {
        progress(expected, expected);
        return sha256_file(dest);
    }
    let resp = agent()
        .get(url)
        .header("User-Agent", UA)
        .call()
        .with_context(|| format!("couldn't download {url}"))?;
    let total = resp
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(expected);
    let part = {
        let mut p = dest.as_os_str().to_owned();
        p.push(".part");
        std::path::PathBuf::from(p)
    };
    let mut out =
        fs::File::create(&part).with_context(|| format!("creating {}", part.display()))?;
    let mut reader = resp.into_body().into_reader();
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    let mut done = 0u64;
    loop {
        let n = reader.read(&mut buf).context("download interrupted")?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
        hasher.update(&buf[..n]);
        done += n as u64;
        progress(done, total);
    }
    out.flush()?;
    drop(out);
    if expected > 0 && done != expected {
        let _ = fs::remove_file(&part);
        bail!("download incomplete: got {done} of {expected} bytes");
    }
    fs::rename(&part, dest)?;
    Ok(hex(&hasher.finalize()))
}

pub fn sha256_file(path: &Path) -> Result<String> {
    let mut f = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex(&hasher.finalize()))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Compares dotted versions numerically: 1.9.1 > 1.9, 1.10 > 1.9.
pub fn compare_versions(a: &str, b: &str) -> Ordering {
    let parse = |v: &str| -> Vec<u64> {
        v.trim_start_matches('v')
            .split(['.', '-'])
            .map(|p| {
                p.chars()
                    .take_while(char::is_ascii_digit)
                    .collect::<String>()
                    .parse()
                    .unwrap_or(0)
            })
            .collect()
    };
    let (pa, pb) = (parse(a), parse(b));
    for i in 0..pa.len().max(pb.len()) {
        let (x, y) = (
            pa.get(i).copied().unwrap_or(0),
            pb.get(i).copied().unwrap_or(0),
        );
        match x.cmp(&y) {
            Ordering::Equal => continue,
            o => return o,
        }
    }
    Ordering::Equal
}

pub fn human_size(bytes: u64) -> String {
    if bytes >= 1024 * 1024 {
        format!("{:.1} MB", bytes as f64 / 1_048_576.0)
    } else if bytes >= 1024 {
        format!("{} KB", bytes / 1024)
    } else {
        format!("{bytes} B")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"[
      {"tag_name":"v1.9.1","name":"TV Home 1.9.1","published_at":"2026-10-25T12:00:00Z","body":"notes",
       "draft":false,"prerelease":false,
       "assets":[{"name":"tvhome-tui-1.9.1-x86_64-unknown-linux-gnu.tar.gz","size":10,"browser_download_url":"https://x/tui"},
                 {"name":"tvhome-1.9.1.apk","size":704671,"browser_download_url":"https://x/tvhome-1.9.1.apk"}]},
      {"tag_name":"v2.0","draft":true,"assets":[]},
      {"tag_name":"v1.9","published_at":"2026-10-24T01:00:00Z","assets":[]}
    ]"#;

    #[test]
    fn parses_releases_and_finds_the_apk() {
        let r = parse_releases(SAMPLE).unwrap();
        assert_eq!(r.len(), 2, "drafts are left out");
        assert_eq!(r[0].version, "1.9.1");
        assert_eq!(r[0].date, "2026-10-25");
        assert_eq!(r[0].apk.as_ref().unwrap().name, "tvhome-1.9.1.apk");
        assert_eq!(r[0].apk.as_ref().unwrap().size, 704671);
        assert_eq!(r[1].title, "v1.9");
        assert!(r[1].apk.is_none());
    }

    #[test]
    fn versions() {
        assert_eq!(compare_versions("1.9.1", "1.9"), Ordering::Greater);
        assert_eq!(compare_versions("v1.10", "1.9.9"), Ordering::Greater);
        assert_eq!(compare_versions("1.9", "1.9.0"), Ordering::Equal);
        assert_eq!(compare_versions("1.8", "1.9"), Ordering::Less);
    }

    #[test]
    fn sizes_and_hashes() {
        assert_eq!(human_size(704671), "688 KB");
        assert_eq!(human_size(3 * 1024 * 1024), "3.0 MB");
        let f = std::env::temp_dir().join(format!("tvhome-sha-{}", std::process::id()));
        fs::write(&f, b"abc").unwrap();
        assert_eq!(
            sha256_file(&f).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
