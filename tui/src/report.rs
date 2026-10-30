//! "Save a support report": one text file with what a helper needs to know (this computer, the
//! TV, the records kept for it and the end of the log), so people can attach it when asking for
//! help instead of describing everything.

use crate::app::App;
use crate::backup::Store;
use crate::log::{Level, LogEntry};
use crate::worker::Status;
use anyhow::{Context, Result};
use std::fmt::Write as _;
use std::fs;
use std::path::PathBuf;

/// The last this many log lines go in.
const LOG_LINES: usize = 500;
/// Lines kept of each command's output.
const OUTPUT_LINES: usize = 8;

/// Writes the report to the Downloads folder (else the app's folder) and returns its path.
pub fn save(app: &App) -> Result<PathBuf> {
    let text = build(
        app.status.as_ref(),
        &app.store,
        app.dry_run,
        &app.log.entries(false),
    );
    let dir = dirs::download_dir()
        .filter(|d| d.is_dir())
        .or_else(crate::setup::phone_downloads)
        .unwrap_or_else(|| app.store.app_dir.clone());
    fs::create_dir_all(&dir)?;
    let time = LogEntry::now(Level::Info, "").time.replace(':', "");
    let path = dir.join(format!("tvhome-support-{time}.txt"));
    fs::write(&path, text).with_context(|| format!("writing {}", path.display()))?;
    Ok(path)
}

pub fn build(status: Option<&Status>, store: &Store, dry_run: bool, log: &[&LogEntry]) -> String {
    let mut r = String::new();
    let _ = writeln!(r, "TV Home setup app: support report");
    let _ = writeln!(r, "==================================");
    let _ = writeln!(r, "App version:  {}", env!("CARGO_PKG_VERSION"));
    let _ = writeln!(
        r,
        "Computer:     {} {}",
        std::env::consts::OS,
        std::env::consts::ARCH
    );
    let _ = writeln!(r, "Practice mode: {}", if dry_run { "yes" } else { "no" });
    let _ = writeln!(r, "Records:      {}", store.backup.display());
    if let Some(st) = status {
        let _ = writeln!(r, "adb:          {} ({})", st.adb_version, st.adb_path);
        let _ = writeln!(r, "\nDevices adb knows:");
        for d in &st.devices {
            let _ = writeln!(
                r,
                "  {}  {}  {}",
                d.serial,
                d.state,
                d.model.as_deref().unwrap_or("")
            );
        }
        if let Some(t) = &st.tv {
            let _ = writeln!(r, "\nTV:");
            let _ = writeln!(r, "  {} (Android {}, SDK {})", t.name(), t.android, t.sdk);
            let _ = writeln!(
                r,
                "  manufacturer {}  brand {}  model {}",
                t.manufacturer, t.brand, t.model
            );
            let _ = writeln!(r, "  address {}  state {}", t.serial, t.state);
            let _ = writeln!(
                r,
                "  TV Home {}",
                t.launcher_version.as_deref().unwrap_or("not installed")
            );
            let _ = writeln!(r, "  Home opens {}", t.home.as_deref().unwrap_or("?"));
            let _ = writeln!(r, "  on screen {}", t.focused.as_deref().unwrap_or("?"));
            let _ = writeln!(
                r,
                "  phone remote service {}",
                if t.a11y_on { "on" } else { "off" }
            );
            let _ = writeln!(r, "  Google TV {}", if t.google_tv { "yes" } else { "no" });
        }
    } else {
        let _ = writeln!(r, "\nNot connected to a TV.");
    }

    let _ = writeln!(
        r,
        "\nRecords kept for this TV ({}):",
        store.backup.display()
    );
    let mut files: Vec<PathBuf> = fs::read_dir(&store.backup)
        .map(|d| {
            d.flatten()
                .map(|e| e.path())
                .filter(|p| p.is_file())
                .collect()
        })
        .unwrap_or_default();
    files.sort();
    if files.is_empty() {
        let _ = writeln!(r, "  (none)");
    }
    for f in files {
        let name = f.file_name().unwrap_or_default().to_string_lossy();
        let body = fs::read_to_string(&f).unwrap_or_default();
        let _ = writeln!(r, "  --- {name}");
        for l in body.lines().take(200) {
            let _ = writeln!(r, "  {l}");
        }
    }

    // Long command output (getprop, package lists) is cut short so the steps stay readable.
    let mut lines = Vec::new();
    let mut run = 0;
    for e in log {
        if e.level == Level::Out && !e.is_problem() {
            run += 1;
            if run == OUTPUT_LINES + 1 {
                lines.push(format!("{}        …", e.time));
            }
            if run > OUTPUT_LINES {
                continue;
            }
        } else {
            run = 0;
        }
        lines.push(format!("{} {} {}", e.time, e.level.tag(), redact(&e.text)));
    }
    let _ = writeln!(
        r,
        "\nLog (last {LOG_LINES} lines; long command output shortened):"
    );
    for l in &lines[lines.len().saturating_sub(LOG_LINES)..] {
        let _ = writeln!(r, "{l}");
    }
    r
}

/// Pairing codes are one-time, but still: they stay out of the report.
fn redact(text: &str) -> String {
    match text.find(" pair ") {
        Some(i) => {
            let (head, tail) = text.split_at(i + " pair ".len());
            let mut parts: Vec<&str> = tail.split(' ').collect();
            if parts.len() >= 2 {
                parts.truncate(1);
                format!("{head}{} ******", parts[0])
            } else {
                text.to_string()
            }
        }
        None => text.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device::{DeviceLine, TvInfo};

    #[test]
    fn report_has_the_tv_records_and_log_without_pairing_codes() {
        let d = std::env::temp_dir().join(format!("tvhome-report-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        let store = Store::at(&d);
        store
            .append_unique(crate::backup::DISABLED, "com.xiaomi.statistic")
            .unwrap();
        let st = Status {
            adb_path: "/x/adb".into(),
            adb_version: "Android Debug Bridge version 1.0.41".into(),
            devices: vec![DeviceLine {
                serial: "192.168.0.44:5555".into(),
                state: "device".into(),
                model: Some("MOOQ0".into()),
            }],
            tv: Some(TvInfo {
                serial: "192.168.0.44:5555".into(),
                model: "MOOQ0".into(),
                launcher_version: Some("1.9.1".into()),
                ..Default::default()
            }),
        };
        let a = LogEntry::now(Level::Cmd, "$ adb pair 192.168.0.50:37123 123456");
        let b = LogEntry::now(Level::Out, "Success");
        let props: Vec<LogEntry> = (0..300)
            .map(|i| LogEntry::now(Level::Out, format!("[prop.{i}]: [x]")))
            .collect();
        let mut log: Vec<&LogEntry> = props.iter().collect();
        log.extend([&a, &b]);
        let text = build(Some(&st), &store, false, &log);
        assert!(
            text.contains("[prop.7]") && !text.contains("[prop.8]"),
            "long output is shortened"
        );
        assert!(text.contains("MOOQ0"));
        assert!(text.contains("TV Home 1.9.1"));
        assert!(text.contains("--- disabled-by-us.txt\n  com.xiaomi.statistic"));
        assert!(text.contains("adb pair 192.168.0.50:37123 ******"));
        assert!(!text.contains("123456"));
        assert!(text.ends_with("Success\n"));
    }
}
