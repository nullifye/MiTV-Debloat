//! Where things are kept, and the `backup/` files shared with the bash scripts. The formats match
//! the scripts exactly (one item per line, LF), so `scripts/restore.sh` can undo what the TUI did
//! and the TUI's Restore can undo what the scripts did.
//!
//! Inside the repository the TUI uses the repository's `backup/`, `.tv` and `shots/`. Run from
//! anywhere else (a downloaded binary), everything lives in the OS data folder
//! (`~/Library/Application Support/tvhome-tui`, `%APPDATA%\tvhome-tui`, `~/.local/share/tvhome-tui`).

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

pub const DISABLED: &str = "disabled-by-us.txt";
pub const STOCK_HOME: &str = "stock-home.txt";
pub const HOME_DEFAULT: &str = "home-default.txt";
pub const A11Y_BEFORE: &str = "accessibility-enabled-before.txt";
pub const BG_RESTRICTED: &str = "bg-restricted.txt";
pub const SPEED_TWEAKS: &str = "speed-tweaks-before.txt";
pub const PRIVATE_DNS: &str = "private-dns-before.txt";

/// A serial number as a folder name: letters, digits, `-` and `_` (the scripts use the same rule).
pub fn safe_id(id: &str) -> String {
    id.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

#[derive(Debug, Clone)]
pub struct Store {
    /// The repository root when run inside it.
    pub repo: Option<PathBuf>,
    /// The connected TV's records, `backup/<serial>/` (see `use_tv`); `backup/` itself until a
    /// TV is connected, when nothing may be written.
    pub backup: PathBuf,
    /// `backup/`, holding one folder per TV (the scripts' one, inside the repository).
    pub backup_root: PathBuf,
    pub tv_file: PathBuf,
    pub shots: PathBuf,
    /// Downloads, the log and settings: always the app's own data folder.
    pub app_dir: PathBuf,
}

impl Store {
    /// Finds the repository from `start` upwards (it has `scripts/env.sh` and `launcher/`).
    pub fn detect(start: &Path, app_dir_override: Option<PathBuf>) -> Self {
        let app_dir = app_dir_override.unwrap_or_else(|| {
            dirs::data_dir()
                .unwrap_or_else(std::env::temp_dir)
                .join("tvhome-tui")
        });
        let repo = start
            .ancestors()
            .find(|d| d.join("scripts/env.sh").is_file() && d.join("launcher").is_dir())
            .map(Path::to_path_buf);
        match &repo {
            Some(r) => Store {
                backup: r.join("backup"),
                backup_root: r.join("backup"),
                tv_file: r.join(".tv"),
                shots: r.join("shots"),
                repo: repo.clone(),
                app_dir,
            },
            None => Store {
                backup: app_dir.join("backup"),
                backup_root: app_dir.join("backup"),
                tv_file: app_dir.join(".tv"),
                // On a phone (Termux), where the gallery and file apps can see them.
                shots: crate::setup::phone_downloads()
                    .map(|d| d.join("TV screenshots"))
                    .unwrap_or_else(|| app_dir.join("shots")),
                repo: None,
                app_dir,
            },
        }
    }

    /// A store rooted at one folder, with a TV ("tv") chosen (tests).
    #[cfg(test)]
    pub fn at(dir: &Path) -> Self {
        Store {
            repo: None,
            backup: dir.join("backup/tv"),
            backup_root: dir.join("backup"),
            tv_file: dir.join(".tv"),
            shots: dir.join("shots"),
            app_dir: dir.to_path_buf(),
        }
    }

    /// Keeps each TV's records apart, in `backup/<id>/` (`id`: its serial number, see
    /// `device::tv_id`), the same folder the scripts use (`tv_backup` in scripts/env.sh). `label`
    /// goes in its `tv.txt`, for people looking at the folder. Records from before per-TV folders
    /// were loose in `backup/`: they move into the folder of the TV that `backup/getprop.txt`
    /// names, or of the first TV seen when it names none. Returns the folder now in use.
    pub fn use_tv(&mut self, id: &str, label: &str) -> Result<PathBuf> {
        let id = safe_id(id);
        let dir = self.backup_root.join(&id);
        fs::create_dir_all(&dir).with_context(|| format!("creating {}", dir.display()))?;
        let visible = |p: &Path| {
            !p.file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .starts_with('.')
        };
        let entries: Vec<PathBuf> = fs::read_dir(&self.backup_root)?
            .flatten()
            .map(|e| e.path())
            .filter(|p| visible(p))
            .collect();
        if entries.iter().any(|p| p.is_file()) {
            let owner = fs::read_to_string(self.backup_root.join("getprop.txt"))
                .ok()
                .and_then(|t| {
                    t.lines().find_map(|l| {
                        l.trim_end()
                            .strip_prefix("[ro.serialno]: [")?
                            .strip_suffix(']')
                            .map(safe_id)
                    })
                })
                .filter(|o| !o.is_empty());
            if owner.is_none() || owner.as_deref() == Some(id.as_str()) {
                for e in entries {
                    if e == dir || e.join("tv.txt").is_file() {
                        continue;
                    }
                    let to = dir.join(e.file_name().unwrap_or_default());
                    if !to.exists() {
                        fs::rename(&e, &to).with_context(|| {
                            format!("moving {} to {}", e.display(), dir.display())
                        })?;
                    }
                }
            }
        }
        fs::write(dir.join("tv.txt"), format!("{}\n", label.trim()))?;
        self.backup = dir;
        Ok(self.backup.clone())
    }

    /// Records may only be written once a TV is connected (they're per TV).
    fn tv_folder(&self) -> Result<&Path> {
        if self.backup == self.backup_root {
            anyhow::bail!("no TV is connected, so there's nowhere to keep this record");
        }
        fs::create_dir_all(&self.backup)
            .with_context(|| format!("creating {}", self.backup.display()))?;
        Ok(&self.backup)
    }

    pub fn downloads(&self) -> PathBuf {
        self.app_dir.join("downloads")
    }

    pub fn log_file(&self) -> PathBuf {
        self.app_dir.join("tui.log")
    }

    fn file(&self, name: &str) -> PathBuf {
        self.backup.join(name)
    }

    pub fn exists(&self, name: &str) -> bool {
        self.file(name).is_file()
    }

    /// Non-empty lines, without `\r`.
    pub fn lines(&self, name: &str) -> Vec<String> {
        fs::read_to_string(self.file(name))
            .map(|s| {
                s.lines()
                    .map(|l| l.trim_end_matches('\r').trim().to_string())
                    .filter(|l| !l.is_empty())
                    .collect()
            })
            .unwrap_or_default()
    }

    fn write(&self, name: &str, content: &str) -> Result<()> {
        self.tv_folder()?;
        fs::write(self.file(name), content).with_context(|| format!("writing backup/{name}"))
    }

    /// Adds a line unless it's already there (disable.sh's `grep -qx || echo >>`).
    pub fn append_unique(&self, name: &str, line: &str) -> Result<bool> {
        if self.lines(name).iter().any(|l| l == line) {
            return Ok(false);
        }
        self.tv_folder()?;
        let path = self.file(name);
        let needs_newline = fs::read(&path)
            .map(|b| !b.is_empty() && !b.ends_with(b"\n"))
            .unwrap_or(false);
        let mut f = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)?;
        if needs_newline {
            f.write_all(b"\n")?;
        }
        writeln!(f, "{line}")?;
        Ok(true)
    }

    // ---------------------------------------------------------------- the TV's address

    pub fn read_tv(&self) -> Option<String> {
        fs::read_to_string(&self.tv_file)
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    }

    pub fn write_tv(&self, serial: &str) -> Result<()> {
        if let Some(dir) = self.tv_file.parent() {
            fs::create_dir_all(dir)?;
        }
        fs::write(&self.tv_file, format!("{serial}\n"))?;
        Ok(())
    }

    // ---------------------------------------------------------------- what to put back

    /// The TV's own launcher: stock-home.txt, else the last line of the older home-default.txt.
    pub fn stock_home(&self) -> Option<String> {
        self.lines(STOCK_HOME)
            .into_iter()
            .next()
            .or_else(|| self.lines(HOME_DEFAULT).into_iter().last())
    }

    /// Records the stock launcher once (never overwritten: later it would be ours).
    pub fn record_stock_home(&self, component: &str) -> Result<bool> {
        if self.stock_home().is_some() {
            return Ok(false);
        }
        self.write(STOCK_HOME, &format!("{component}\n"))?;
        Ok(true)
    }

    /// `accessibility_enabled` as it was before the first install (`null`, `0` or `1`).
    pub fn a11y_before(&self) -> Option<String> {
        self.lines(A11Y_BEFORE).into_iter().next()
    }

    pub fn record_a11y_before(&self, value: &str) -> Result<bool> {
        if self.exists(A11Y_BEFORE) {
            return Ok(false);
        }
        self.write(A11Y_BEFORE, &format!("{value}\n"))?;
        Ok(true)
    }

    /// `wifi_scan_always_enabled=<v>` from speed-tweaks-before.txt.
    pub fn wifi_scan_before(&self) -> Option<String> {
        self.lines(SPEED_TWEAKS).iter().find_map(|l| {
            l.strip_prefix("wifi_scan_always_enabled=")
                .map(str::to_string)
        })
    }

    pub fn record_wifi_scan_before(&self, value: &str) -> Result<bool> {
        if self.wifi_scan_before().is_some() {
            return Ok(false);
        }
        self.append_unique(SPEED_TWEAKS, &format!("wifi_scan_always_enabled={value}"))
    }

    /// `(mode, specifier)` from `before: <mode> / <spec>`.
    pub fn private_dns_before(&self) -> Option<(String, String)> {
        let line = self
            .lines(PRIVATE_DNS)
            .into_iter()
            .find_map(|l| l.strip_prefix("before: ").map(str::to_string))?;
        let (m, s) = line.split_once(" / ")?;
        Some((m.trim().to_string(), s.trim().to_string()))
    }

    pub fn record_private_dns_before(&self, mode: &str, spec: &str) -> Result<bool> {
        if self.private_dns_before().is_some() {
            return Ok(false);
        }
        self.write(PRIVATE_DNS, &format!("before: {mode} / {spec}\n"))?;
        Ok(true)
    }
}

/// The TUI's own settings (recent TVs), kept in the app folder.
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Config {
    #[serde(default)]
    pub recent_tvs: Vec<String>,
}

impl Config {
    fn path(store: &Store) -> PathBuf {
        store.app_dir.join("config.json")
    }

    pub fn load(store: &Store) -> Self {
        fs::read_to_string(Self::path(store))
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, store: &Store) -> Result<()> {
        fs::create_dir_all(&store.app_dir)?;
        fs::write(Self::path(store), serde_json::to_string_pretty(self)?)?;
        Ok(())
    }

    /// Most recent first, at most 8.
    pub fn remember(&mut self, serial: &str) {
        self.recent_tvs.retain(|s| s != serial);
        self.recent_tvs.insert(0, serial.to_string());
        self.recent_tvs.truncate(8);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("tvhome-tui-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn disabled_log_matches_disable_sh() {
        let d = tmp("disabled");
        let s = Store::at(&d);
        assert!(s.append_unique(DISABLED, "com.a").unwrap());
        assert!(!s.append_unique(DISABLED, "com.a").unwrap());
        assert!(s.append_unique(DISABLED, "com.b").unwrap());
        assert_eq!(
            fs::read_to_string(d.join("backup/tv").join(DISABLED)).unwrap(),
            "com.a\ncom.b\n"
        );
    }

    #[test]
    fn append_after_a_file_without_final_newline() {
        let d = tmp("newline");
        fs::create_dir_all(d.join("backup/tv")).unwrap();
        fs::write(d.join("backup/tv").join(DISABLED), "com.a").unwrap();
        Store::at(&d).append_unique(DISABLED, "com.b").unwrap();
        assert_eq!(
            fs::read_to_string(d.join("backup/tv").join(DISABLED)).unwrap(),
            "com.a\ncom.b\n"
        );
    }

    #[test]
    fn stock_home_prefers_new_file_then_old() {
        let d = tmp("stock");
        let s = Store::at(&d);
        assert_eq!(s.stock_home(), None);
        fs::create_dir_all(d.join("backup/tv")).unwrap();
        fs::write(
            d.join("backup/tv").join(HOME_DEFAULT),
            "noise\r\ncom.google.android.tvlauncher/.MainActivity\r\n",
        )
        .unwrap();
        assert_eq!(
            s.stock_home().as_deref(),
            Some("com.google.android.tvlauncher/.MainActivity")
        );
        // Already known (old file): not recorded again.
        assert!(!s.record_stock_home("x/.Y").unwrap());
        fs::remove_file(d.join("backup/tv").join(HOME_DEFAULT)).unwrap();
        assert!(
            s.record_stock_home("com.google.android.apps.tv.launcherx/.home.HomeActivity")
                .unwrap()
        );
        assert_eq!(
            fs::read_to_string(d.join("backup/tv").join(STOCK_HOME)).unwrap(),
            "com.google.android.apps.tv.launcherx/.home.HomeActivity\n"
        );
    }

    #[test]
    fn values_are_recorded_once() {
        let d = tmp("once");
        let s = Store::at(&d);
        assert!(s.record_a11y_before("null").unwrap());
        assert!(!s.record_a11y_before("1").unwrap());
        assert_eq!(s.a11y_before().as_deref(), Some("null"));

        assert!(s.record_private_dns_before("null", "null").unwrap());
        assert_eq!(s.private_dns_before(), Some(("null".into(), "null".into())));
        assert!(!s.record_private_dns_before("hostname", "x").unwrap());

        // speed-tweaks-before.txt can hold other lines (written by hand): the scan line is added.
        fs::write(
            d.join("backup/tv").join(SPEED_TWEAKS),
            "com.x: allow / allow\n",
        )
        .unwrap();
        assert!(s.record_wifi_scan_before("1").unwrap());
        assert_eq!(s.wifi_scan_before().as_deref(), Some("1"));
        assert!(!s.record_wifi_scan_before("0").unwrap());
    }

    #[test]
    fn detects_the_repository() {
        let d = tmp("repo");
        fs::create_dir_all(d.join("scripts")).unwrap();
        fs::write(d.join("scripts/env.sh"), "").unwrap();
        fs::create_dir_all(d.join("launcher/app")).unwrap();
        let s = Store::detect(&d.join("launcher/app"), Some(d.join("appdata")));
        assert_eq!(s.repo.as_deref(), Some(d.as_path()));
        assert_eq!(s.backup, d.join("backup"));
        assert_eq!(s.tv_file, d.join(".tv"));
        let elsewhere = tmp("elsewhere");
        let s = Store::detect(&elsewhere, Some(elsewhere.join("appdata")));
        assert!(s.repo.is_none());
        assert_eq!(s.backup, elsewhere.join("appdata/backup"));
    }

    #[test]
    fn each_tv_gets_its_own_records() {
        let d = tmp("pertv");
        let root = d.join("backup");
        let mut s = Store::at(&d);
        s.backup = root.clone();
        assert!(
            s.append_unique(DISABLED, "x").is_err(),
            "nothing is written before a TV is chosen"
        );
        // Records from before per-TV folders, loose in backup/, made for the TV getprop.txt names.
        fs::create_dir_all(root.join("dumpsys")).unwrap();
        fs::write(root.join(DISABLED), "com.old\n").unwrap();
        fs::write(
            root.join("getprop.txt"),
            "[ro.boot.serialno]: [MITV123]\n[ro.serialno]: [MITV123]\n",
        )
        .unwrap();
        fs::write(root.join(".DS_Store"), "").unwrap();
        // Another TV first: it starts clean and leaves them alone.
        assert_eq!(
            s.use_tv("SONY:9/x", "Sony KD-55X75K").unwrap(),
            root.join("SONY_9_x")
        );
        assert!(s.lines(DISABLED).is_empty());
        assert!(root.join(DISABLED).is_file());
        s.append_unique(DISABLED, "com.sony.promos").unwrap();
        // The TV they were made for takes them, folders too; the other TV's folder stays.
        assert_eq!(
            s.use_tv("MITV123", "Xiaomi MOOQ0\n1.2.3.4:5555").unwrap(),
            root.join("MITV123")
        );
        assert_eq!(s.lines(DISABLED), vec!["com.old"]);
        assert!(
            root.join("MITV123/dumpsys").is_dir() && root.join("MITV123/getprop.txt").is_file()
        );
        assert!(root.join(".DS_Store").is_file() && !root.join(DISABLED).exists());
        assert_eq!(
            fs::read_to_string(root.join("MITV123/tv.txt")).unwrap(),
            "Xiaomi MOOQ0\n1.2.3.4:5555\n"
        );
        s.use_tv("SONY:9/x", "Sony").unwrap();
        assert_eq!(s.lines(DISABLED), vec!["com.sony.promos"]);

        // Without getprop.txt, the first TV seen takes them.
        let d = tmp("pertv-first");
        let mut s = Store::at(&d);
        fs::create_dir_all(d.join("backup")).unwrap();
        fs::write(d.join("backup").join(STOCK_HOME), "com.x/.Home\n").unwrap();
        s.use_tv("ABC", "").unwrap();
        assert_eq!(s.stock_home().as_deref(), Some("com.x/.Home"));
    }

    #[test]
    fn recent_tvs() {
        let mut c = Config::default();
        c.remember("a:5555");
        c.remember("b:5555");
        c.remember("a:5555");
        assert_eq!(c.recent_tvs, vec!["a:5555", "b:5555"]);
    }
}
