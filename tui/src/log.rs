//! The live log: every command run, what it printed, and errors. Kept in memory for the log panel
//! (newest 5000 lines) and appended to `tui.log` in the app folder.

use std::collections::VecDeque;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    /// A command that was run (with its timing and exit code).
    Cmd,
    /// What a command printed.
    Out,
    Info,
    Warn,
    Error,
}

impl Level {
    pub fn tag(self) -> &'static str {
        match self {
            Level::Cmd => "CMD",
            Level::Out => "   ",
            Level::Info => "INF",
            Level::Warn => "WRN",
            Level::Error => "ERR",
        }
    }
}

#[derive(Debug, Clone)]
pub struct LogEntry {
    pub time: String,
    pub level: Level,
    pub text: String,
}

impl LogEntry {
    pub fn now(level: Level, text: impl Into<String>) -> Self {
        LogEntry {
            time: clock(),
            level,
            text: text.into(),
        }
    }

    /// Counts toward the error log: errors, warnings, and command output that reads like a
    /// failure (`Failure [...]`, `Exception`).
    pub fn is_problem(&self) -> bool {
        match self.level {
            Level::Error | Level::Warn => true,
            Level::Out => {
                let t = &self.text;
                t.contains("Failure") || t.contains("Exception") || t.contains("error:")
            }
            _ => false,
        }
    }
}

/// Local time of day, HH:MM:SS. Without a time-zone crate this uses the offset the OS reports
/// for `date`; it falls back to UTC.
fn clock() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
        + utc_offset();
    let s = secs.rem_euclid(86_400);
    format!("{:02}:{:02}:{:02}", s / 3600, (s / 60) % 60, s % 60)
}

fn utc_offset() -> i64 {
    use std::sync::OnceLock;
    static OFFSET: OnceLock<i64> = OnceLock::new();
    *OFFSET.get_or_init(|| {
        // "+0800" from `date +%z` (macOS, Linux, Git Bash); Windows without it: UTC.
        std::process::Command::new("date")
            .arg("+%z")
            .output()
            .ok()
            .and_then(|o| parse_offset(String::from_utf8_lossy(&o.stdout).trim()))
            .unwrap_or(0)
    })
}

fn parse_offset(z: &str) -> Option<i64> {
    if z.len() != 5 {
        return None;
    }
    let sign = match &z[..1] {
        "+" => 1,
        "-" => -1,
        _ => return None,
    };
    let h: i64 = z[1..3].parse().ok()?;
    let m: i64 = z[3..5].parse().ok()?;
    Some(sign * (h * 3600 + m * 60))
}

pub struct Log {
    entries: VecDeque<LogEntry>,
    file: Option<PathBuf>,
}

const KEEP: usize = 5000;

impl Log {
    pub fn new(file: Option<PathBuf>) -> Self {
        if let Some(f) = &file
            && let Some(dir) = f.parent()
        {
            let _ = fs::create_dir_all(dir);
        }
        Log {
            entries: VecDeque::new(),
            file,
        }
    }

    pub fn push(&mut self, e: LogEntry) {
        if let Some(f) = &self.file
            && let Ok(mut h) = OpenOptions::new().create(true).append(true).open(f)
        {
            let _ = writeln!(h, "{} {} {}", e.time, e.level.tag(), e.text);
        }
        self.entries.push_back(e);
        while self.entries.len() > KEEP {
            self.entries.pop_front();
        }
    }

    pub fn info(&mut self, text: impl Into<String>) {
        self.push(LogEntry::now(Level::Info, text));
    }

    pub fn error(&mut self, text: impl Into<String>) {
        self.push(LogEntry::now(Level::Error, text));
    }

    pub fn entries(&self, problems_only: bool) -> Vec<&LogEntry> {
        self.entries
            .iter()
            .filter(|e| !problems_only || e.is_problem())
            .collect()
    }

    pub fn problem_count(&self) -> usize {
        self.entries.iter().filter(|e| e.is_problem()).count()
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offsets() {
        assert_eq!(parse_offset("+0800"), Some(8 * 3600));
        assert_eq!(parse_offset("-0530"), Some(-(5 * 3600 + 30 * 60)));
        assert_eq!(parse_offset("bad"), None);
    }

    #[test]
    fn problems_filter() {
        let mut l = Log::new(None);
        l.push(LogEntry::now(Level::Cmd, "$ adb shell pm install"));
        l.push(LogEntry::now(
            Level::Out,
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]",
        ));
        l.push(LogEntry::now(Level::Out, "Success"));
        l.error("boom");
        assert_eq!(l.entries(false).len(), 4);
        assert_eq!(l.entries(true).len(), 2);
        assert_eq!(l.problem_count(), 2);
    }

    #[test]
    fn keeps_the_newest() {
        let mut l = Log::new(None);
        for i in 0..KEEP + 10 {
            l.info(format!("{i}"));
        }
        assert_eq!(l.len(), KEEP);
        assert_eq!(l.entries(false)[0].text, "10");
    }

    #[test]
    fn appends_to_the_file() {
        let f = std::env::temp_dir().join(format!("tvhome-log-{}.log", std::process::id()));
        let _ = fs::remove_file(&f);
        let mut l = Log::new(Some(f.clone()));
        l.info("hello");
        assert!(fs::read_to_string(&f).unwrap().contains("INF hello"));
    }
}
