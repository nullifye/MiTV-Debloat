//! A plan: the exact steps an action will take, shown in full before anything runs, and the
//! engine that runs them. Builders (flows.rs) do their read-only checks first, so the preview shows
//! the real commands with the real values; only "did Home land on the launcher?" is decided while
//! running.

use crate::adb::{self, Output, Runner};
use crate::backup::{self, Store};
use crate::device::{self, InstallResult};
use crate::github;
use crate::log::{Level, LogEntry};
use std::path::PathBuf;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Kind {
    /// A failure stops the plan.
    Required,
    /// A failure is noted and the plan carries on (e.g. a permission this TV refuses).
    Optional,
}

/// How a command's output is judged.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Judge {
    /// Exit code 0 and no "Exception"/"Error:" in the output.
    Exit0,
    /// `pm install`: needs "Success"; a signature mismatch is reported separately.
    Install,
    /// `pm disable-user`: needs "disabled-user" in the reply.
    Disabled,
    /// `pm enable`: needs "enabled" in the reply.
    Enabled,
    /// `pm uninstall` / `adb uninstall`: needs "Success".
    Success,
    /// Anything counts: commands that cut the connection they arrive on (debugging off).
    Any,
}

/// Something written to backup/ (shared with the scripts).
#[derive(Debug, Clone, PartialEq)]
pub enum Record {
    StockHome(String),
    A11yBefore(String),
    WifiScanBefore(String),
    PrivateDnsBefore(String, String),
    Disabled(String),
    BgRestricted(String),
}

impl Record {
    fn describe(&self) -> String {
        match self {
            Record::StockHome(c) => format!("backup/{}: {c}", backup::STOCK_HOME),
            Record::A11yBefore(v) => format!("backup/{}: {v}", backup::A11Y_BEFORE),
            Record::WifiScanBefore(v) => format!(
                "backup/{}: wifi_scan_always_enabled={v}",
                backup::SPEED_TWEAKS
            ),
            Record::PrivateDnsBefore(m, s) => {
                format!("backup/{}: before: {m} / {s}", backup::PRIVATE_DNS)
            }
            Record::Disabled(p) => format!("backup/{}: + {p}", backup::DISABLED),
            Record::BgRestricted(p) => format!("backup/{}: + {p}", backup::BG_RESTRICTED),
        }
    }

    fn apply(&self, store: &Store) -> anyhow::Result<bool> {
        match self {
            Record::StockHome(c) => store.record_stock_home(c),
            Record::A11yBefore(v) => store.record_a11y_before(v),
            Record::WifiScanBefore(v) => store.record_wifi_scan_before(v),
            Record::PrivateDnsBefore(m, s) => store.record_private_dns_before(m, s),
            Record::Disabled(p) => store.append_unique(backup::DISABLED, p),
            Record::BgRestricted(p) => store.append_unique(backup::BG_RESTRICTED, p),
        }
    }
}

#[derive(Debug, Clone)]
pub enum Action {
    Adb(Vec<String>),
    Download {
        url: String,
        dest: PathBuf,
        size: u64,
    },
    /// Write to backup/ now (before the command that changes the TV).
    Record(Record),
    /// A health check after a change (decided while running). With `expect_launcher` (after
    /// pressing Home): wait until the launcher is in front. Without: check Home still has
    /// somewhere to go. If the check fails, the `rollback` commands put things back.
    VerifyHome(Box<Verify>),
}

#[derive(Debug, Clone)]
pub struct Verify {
    pub window: Vec<String>,
    pub activities: Vec<String>,
    pub resolve: Vec<String>,
    pub expect_launcher: bool,
    pub attempts: u32,
    pub delay_ms: u64,
    /// Run when the check fails (re-enable what was disabled, set the old Home).
    pub rollback: Vec<Vec<String>>,
    /// What the rollback does, in plain words ("the TV's own home screen was switched back on").
    pub rollback_note: String,
}

/// The start of every failed Home check's message (screens.rs explains it in plain words).
pub const HOME_FAILED: &str = "Home didn't come up";

#[derive(Debug, Clone)]
pub struct Step {
    pub label: String,
    pub action: Action,
    pub kind: Kind,
    pub judge: Judge,
    /// Written to backup/ when the step succeeds (e.g. a disabled package).
    pub on_success: Option<Record>,
    pub timeout: Duration,
}

impl Step {
    pub fn adb(label: impl Into<String>, argv: Vec<String>) -> Self {
        Step {
            label: label.into(),
            action: Action::Adb(argv),
            kind: Kind::Required,
            judge: Judge::Exit0,
            on_success: None,
            timeout: Duration::from_secs(60),
        }
    }

    pub fn record(label: impl Into<String>, r: Record) -> Self {
        Step {
            label: label.into(),
            action: Action::Record(r),
            kind: Kind::Optional,
            judge: Judge::Exit0,
            on_success: None,
            timeout: Duration::from_secs(5),
        }
    }

    pub fn optional(mut self) -> Self {
        self.kind = Kind::Optional;
        self
    }

    pub fn judge(mut self, j: Judge) -> Self {
        self.judge = j;
        self
    }

    pub fn then_record(mut self, r: Record) -> Self {
        self.on_success = Some(r);
        self
    }

    pub fn timeout(mut self, secs: u64) -> Self {
        self.timeout = Duration::from_secs(secs);
        self
    }

    /// The line shown in the preview.
    pub fn command_line(&self) -> String {
        match &self.action {
            Action::Adb(argv) => adb::display(argv),
            Action::Download { url, dest, size } => format!(
                "download {} ({}) → {}",
                url.rsplit('/').next().unwrap_or(url),
                github::human_size(*size),
                dest.display()
            ),
            Action::Record(r) => format!("write {}", r.describe()),
            Action::VerifyHome(v) => {
                let what = if v.expect_launcher {
                    format!(
                        "check the TV shows the launcher (dumpsys window), up to {} s",
                        v.attempts as u64 * v.delay_ms / 1000
                    )
                } else {
                    "check Home still has somewhere to go (cmd package resolve-activity)"
                        .to_string()
                };
                if v.rollback.is_empty() {
                    what
                } else {
                    format!(
                        "{what}; if not, put things back ({} command(s))",
                        v.rollback.len()
                    )
                }
            }
        }
    }

    pub fn changes_tv(&self) -> bool {
        matches!(self.action, Action::Adb(_))
    }
}

#[derive(Debug, Clone, Default)]
pub struct Plan {
    pub title: String,
    /// Shown above the steps (what this does, what it won't do).
    pub notes: Vec<String>,
    /// Shown in amber (someone watching, settings will be lost, …).
    pub warnings: Vec<String>,
    pub steps: Vec<Step>,
    /// What to do after a signature mismatch: offered by the UI.
    pub retry_after_uninstall: Option<Box<Plan>>,
    /// "This will:" in plain words, for the confirmation page (the steps are the details).
    pub summary: Vec<String>,
    /// Shown when it worked, e.g. "Press Home on your TV remote."
    pub done: String,
}

impl Plan {
    pub fn new(title: impl Into<String>) -> Self {
        Plan {
            title: title.into(),
            ..Default::default()
        }
    }

    pub fn push(&mut self, s: Step) {
        self.steps.push(s);
    }

    pub fn is_empty(&self) -> bool {
        self.steps.is_empty()
    }

    pub fn say(&mut self, line: impl Into<String>) {
        self.summary.push(line.into());
    }

    /// The plain-words summary, or the step names when there isn't one.
    pub fn summary_lines(&self) -> Vec<String> {
        if self.summary.is_empty() {
            self.steps.iter().map(|s| s.label.clone()).collect()
        } else {
            self.summary.clone()
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Status {
    Pending,
    Running,
    Ok(String),
    /// An optional step failed; the plan went on.
    Noted(String),
    Failed(String),
    Skipped(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    Done {
        noted: usize,
    },
    Stopped {
        step: usize,
        reason: String,
    },
    /// `pm install` refused: a copy signed with another key is installed.
    Incompatible,
    DryRun,
}

pub enum EngineEvent {
    Step(usize, Status),
    Progress(u64, u64),
    Log(LogEntry),
}

fn judge(j: Judge, out: &Output) -> Result<String, String> {
    let text = out.text();
    let first = text
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .unwrap_or("")
        .to_string();
    let bad_text = text.contains("Exception") || text.contains("Error:") || text.contains("error:");
    match j {
        Judge::Exit0 if out.ok() && !bad_text => Ok(first),
        Judge::Exit0 => Err(if first.is_empty() {
            format!("exit code {:?}", out.code)
        } else {
            first
        }),
        Judge::Install => match device::parse_install(&text) {
            InstallResult::Success => Ok("Success".into()),
            InstallResult::Incompatible => Err("INCOMPATIBLE".into()),
            InstallResult::Failed(m) => Err(if m.is_empty() {
                "install failed".into()
            } else {
                m
            }),
        },
        Judge::Disabled if text.contains("disabled-user") => Ok(first),
        Judge::Enabled if text.contains("enabled") && !bad_text => Ok(first),
        Judge::Success if text.lines().any(|l| l.trim() == "Success") => Ok("Success".into()),
        Judge::Any => Ok(first),
        _ => Err(if first.is_empty() {
            format!("exit code {:?}", out.code)
        } else {
            first
        }),
    }
}

fn log_command(argv: &[String], out: &Output) -> Vec<LogEntry> {
    let mut v = vec![LogEntry::now(
        Level::Cmd,
        format!(
            "$ {}  ({} ms, exit {})",
            adb::display(argv),
            out.millis,
            code_text(out.code)
        ),
    )];
    for l in out
        .stdout
        .replace('\r', "")
        .lines()
        .filter(|l| !l.trim().is_empty())
    {
        v.push(LogEntry::now(Level::Out, l.to_string()));
    }
    for l in out
        .stderr
        .replace('\r', "")
        .lines()
        .filter(|l| !l.trim().is_empty())
    {
        v.push(LogEntry::now(stderr_level(out, l), l.to_string()));
    }
    v
}

/// adb prints normal progress on stderr ("1 file pushed, 0 skipped"): a stderr line is an error
/// only when the command failed or the line reads like one.
fn stderr_level(out: &Output, line: &str) -> Level {
    let l = line.to_lowercase();
    let looks_bad = [
        "error",
        "fail",
        "exception",
        "denied",
        "not found",
        "no such",
        "cannot",
        "unable",
    ]
    .iter()
    .any(|w| l.contains(w));
    if !out.ok() || looks_bad {
        Level::Error
    } else {
        Level::Out
    }
}

/// The health check: Ok when Home is fine, else runs the rollback and explains.
fn verify_home(
    v: &Verify,
    runner: &dyn Runner,
    emit: &mut dyn FnMut(EngineEvent),
) -> Result<String, String> {
    let mut problem = String::new();
    for attempt in 0..v.attempts.max(1) {
        if attempt > 0 || v.expect_launcher {
            std::thread::sleep(Duration::from_millis(v.delay_ms));
        }
        if v.expect_launcher {
            let mut focused = device::parse_focused_package(
                &run_logged(runner, &v.window, Duration::from_secs(20), emit).text(),
            );
            if focused.is_none() {
                focused = device::parse_focused_package(
                    &run_logged(runner, &v.activities, Duration::from_secs(20), emit).text(),
                );
            }
            match focused.as_deref() {
                Some(device::PKG) => return Ok("Home opens the launcher".into()),
                // Google TV's setup app took Home (its recovery screen): stop waiting, put things back.
                Some(p) if p == device::GTV_SETUP => {
                    problem = "Home opens Google TV's setup screen".into();
                    break;
                }
                Some(p) => problem = format!("Home opens {}", device::friendly_app_name(p)),
                None => problem = "the TV didn't say what's on screen".into(),
            }
        } else {
            let resolved = device::parse_resolved_component(
                &run_logged(runner, &v.resolve, Duration::from_secs(20), emit).text(),
            );
            match resolved.as_deref() {
                Some(c)
                    if !c.contains("FallbackHome")
                        && device::package_of(c) != device::GTV_SETUP =>
                {
                    return Ok("Home still works".into());
                }
                Some(c) => problem = format!("Home now goes to {}", device::package_of(c)),
                None => problem = "Home has nowhere to go".into(),
            }
        }
    }
    if v.rollback.is_empty() {
        return Err(format!("{HOME_FAILED}: {problem}"));
    }
    emit(EngineEvent::Log(LogEntry::now(
        Level::Warn,
        format!("{problem}: putting things back"),
    )));
    for argv in &v.rollback {
        run_logged(runner, argv, Duration::from_secs(30), emit);
    }
    Err(format!("{HOME_FAILED}: {problem}, so {}", v.rollback_note))
}

pub fn code_text(code: Option<i32>) -> String {
    code.map(|c| c.to_string()).unwrap_or_else(|| "-".into())
}

/// Runs one command and logs it (read-only helpers use this too).
pub fn run_logged(
    runner: &dyn Runner,
    argv: &[String],
    timeout: Duration,
    emit: &mut dyn FnMut(EngineEvent),
) -> Output {
    let out = runner.run(argv, timeout);
    for e in log_command(argv, &out) {
        emit(EngineEvent::Log(e));
    }
    out
}

/// Runs the plan in order. With `dry_run`, nothing is run or written: every step is logged as it
/// would have run.
pub fn run(
    plan: &Plan,
    runner: &dyn Runner,
    store: &Store,
    dry_run: bool,
    emit: &mut dyn FnMut(EngineEvent),
) -> Outcome {
    emit(EngineEvent::Log(LogEntry::now(
        Level::Info,
        format!("▶ {}", plan.title),
    )));
    if dry_run {
        for (i, s) in plan.steps.iter().enumerate() {
            emit(EngineEvent::Log(LogEntry::now(
                Level::Info,
                format!("(dry run) {}", s.command_line()),
            )));
            emit(EngineEvent::Step(i, Status::Skipped("dry run".into())));
        }
        return Outcome::DryRun;
    }
    let mut noted = 0;
    for (i, step) in plan.steps.iter().enumerate() {
        emit(EngineEvent::Step(i, Status::Running));
        let result: Result<String, String> = match &step.action {
            Action::Adb(argv) => {
                let out = run_logged(runner, argv, step.timeout, emit);
                judge(step.judge, &out)
            }
            Action::Record(r) => match r.apply(store) {
                Ok(true) => Ok("recorded".into()),
                Ok(false) => Ok("already recorded".into()),
                Err(e) => Err(e.to_string()),
            },
            Action::Download { url, dest, size } => {
                emit(EngineEvent::Log(LogEntry::now(
                    Level::Info,
                    format!("downloading {url}"),
                )));
                let mut progress = |done: u64, total: u64| emit(EngineEvent::Progress(done, total));
                match github::download(url, dest, *size, &mut progress) {
                    Ok(sha) => {
                        emit(EngineEvent::Log(LogEntry::now(
                            Level::Info,
                            format!("saved {} (sha256 {sha})", dest.display()),
                        )));
                        Ok(format!("sha256 {}", &sha[..12.min(sha.len())]))
                    }
                    Err(e) => Err(e.to_string()),
                }
            }
            Action::VerifyHome(v) => verify_home(v, runner, emit),
        };
        match result {
            Ok(msg) => {
                if let Some(r) = &step.on_success
                    && let Err(e) = r.apply(store)
                {
                    emit(EngineEvent::Log(LogEntry::now(
                        Level::Warn,
                        format!("couldn't record {}: {e}", r.describe()),
                    )));
                }
                emit(EngineEvent::Step(i, Status::Ok(msg)));
            }
            Err(msg) if msg == "INCOMPATIBLE" => {
                emit(EngineEvent::Step(
                    i,
                    Status::Failed("signed with a different key".into()),
                ));
                for j in i + 1..plan.steps.len() {
                    emit(EngineEvent::Step(j, Status::Skipped("stopped".into())));
                }
                return Outcome::Incompatible;
            }
            Err(msg) if step.kind == Kind::Optional => {
                noted += 1;
                emit(EngineEvent::Log(LogEntry::now(
                    Level::Warn,
                    format!("{}: {msg}", step.label),
                )));
                emit(EngineEvent::Step(i, Status::Noted(msg)));
            }
            Err(msg) => {
                emit(EngineEvent::Log(LogEntry::now(
                    Level::Error,
                    format!("{}: {msg}", step.label),
                )));
                emit(EngineEvent::Step(i, Status::Failed(msg.clone())));
                for j in i + 1..plan.steps.len() {
                    emit(EngineEvent::Step(j, Status::Skipped("stopped".into())));
                }
                return Outcome::Stopped {
                    step: i,
                    reason: msg,
                };
            }
        }
    }
    Outcome::Done { noted }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::adb::FakeRunner;

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| x.to_string()).collect()
    }

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("tvhome-plan-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn collect(plan: &Plan, runner: &FakeRunner, store: &Store) -> (Outcome, Vec<Status>) {
        let mut statuses = vec![Status::Pending; plan.steps.len()];
        let o = run(plan, runner, store, false, &mut |e| {
            if let EngineEvent::Step(i, st) = e {
                statuses[i] = st;
            }
        });
        (o, statuses)
    }

    #[test]
    fn disable_logs_only_on_success() {
        let d = tmp("disable");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on(
                "disable-user --user 0 com.ok",
                "Package com.ok new state: disabled-user",
            )
            .fail(
                "disable-user --user 0 com.bad",
                255,
                "java.lang.SecurityException: no",
            );
        let mut p = Plan::new("t");
        for pkg in ["com.ok", "com.bad"] {
            p.push(
                Step::adb(
                    pkg,
                    s(&["adb", "shell", "pm", "disable-user", "--user", "0", pkg]),
                )
                .optional()
                .judge(Judge::Disabled)
                .then_record(Record::Disabled(pkg.into())),
            );
        }
        let (o, st) = collect(&p, &r, &store);
        assert_eq!(o, Outcome::Done { noted: 1 });
        assert!(matches!(st[0], Status::Ok(_)));
        assert!(matches!(st[1], Status::Noted(_)));
        assert_eq!(store.lines(backup::DISABLED), vec!["com.ok"]);
    }

    #[test]
    fn required_failure_stops() {
        let d = tmp("stop");
        let r = FakeRunner::new().fail("push", 1, "adb: error: no devices");
        let mut p = Plan::new("t");
        p.push(Step::adb(
            "push",
            s(&["adb", "push", "a.apk", "/data/local/tmp/tvhome.apk"]),
        ));
        p.push(Step::adb("install", s(&["adb", "shell", "pm", "install"])).judge(Judge::Install));
        let (o, st) = collect(&p, &r, &Store::at(&d));
        assert!(matches!(o, Outcome::Stopped { step: 0, .. }));
        assert_eq!(st[1], Status::Skipped("stopped".into()));
        assert_eq!(r.calls().len(), 1);
    }

    #[test]
    fn incompatible_signature_is_its_own_outcome() {
        let d = tmp("incompat");
        let r = FakeRunner::new().on(
            "pm install",
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package my.rafhan.app.tvhome signatures do not match]",
        );
        let mut p = Plan::new("t");
        p.push(
            Step::adb("install", s(&["adb", "shell", "pm", "install", "-r", "x"]))
                .judge(Judge::Install),
        );
        p.push(Step::adb("grant", s(&["adb", "shell", "pm", "grant"])));
        let (o, _) = collect(&p, &r, &Store::at(&d));
        assert_eq!(o, Outcome::Incompatible);
    }

    #[test]
    fn exception_text_fails_exit0() {
        let out = Output {
            code: Some(0),
            stdout: "Exception occurred while executing 'grant':\njava.lang.SecurityException"
                .into(),
            ..Default::default()
        };
        assert!(judge(Judge::Exit0, &out).is_err());
        let ok = Output {
            code: Some(0),
            ..Default::default()
        };
        assert!(judge(Judge::Exit0, &ok).is_ok());
    }

    #[test]
    fn adb_progress_on_stderr_is_not_an_error() {
        let ok = Output {
            code: Some(0),
            stderr: "x.apk: 1 file pushed, 0 skipped. 9.6 MB/s".into(),
            ..Default::default()
        };
        assert_eq!(stderr_level(&ok, &ok.stderr), Level::Out);
        assert_eq!(
            stderr_level(&ok, "adb: error: failed to copy"),
            Level::Error
        );
        let failed = Output {
            code: Some(1),
            ..Default::default()
        };
        assert_eq!(stderr_level(&failed, "anything"), Level::Error);
    }

    #[test]
    fn dry_run_runs_and_writes_nothing() {
        let d = tmp("dry");
        let store = Store::at(&d);
        let r = FakeRunner::new();
        let mut p = Plan::new("t");
        p.push(Step::record("stock", Record::StockHome("a/.B".into())));
        p.push(Step::adb("x", s(&["adb", "shell", "reboot"])));
        let o = run(&p, &r, &store, true, &mut |_| {});
        assert_eq!(o, Outcome::DryRun);
        assert!(r.calls().is_empty());
        assert_eq!(store.stock_home(), None);
    }

    fn verify(expect_launcher: bool, rollback: Vec<Vec<String>>) -> Step {
        Step {
            label: "home".into(),
            action: Action::VerifyHome(Box::new(Verify {
                window: s(&["adb", "shell", "dumpsys", "window"]),
                activities: s(&["adb", "shell", "dumpsys", "activity", "activities"]),
                resolve: s(&["adb", "shell", "cmd", "package", "resolve-activity"]),
                expect_launcher,
                attempts: 3,
                delay_ms: 1,
                rollback,
                rollback_note: "the TV's own home screen was switched back on".into(),
            })),
            kind: Kind::Required,
            judge: Judge::Exit0,
            on_success: None,
            timeout: Duration::from_secs(1),
        }
    }

    #[test]
    fn verify_home_polls_until_the_launcher_is_in_front() {
        let d = tmp("wait");
        let r = FakeRunner::new().on(
            "dumpsys window",
            "  mFocusedApp=ActivityRecord{1 u0 my.rafhan.app.tvhome/.MainActivity t1}\n",
        );
        let mut p = Plan::new("t");
        p.push(verify(true, vec![]));
        let (o, st) = collect(&p, &r, &Store::at(&d));
        assert_eq!(o, Outcome::Done { noted: 0 });
        assert!(matches!(&st[0], Status::Ok(m) if m.contains("launcher")));
    }

    #[test]
    fn google_tv_recovery_screen_triggers_the_rollback() {
        let d = tmp("recover");
        let r = FakeRunner::new().on(
            "dumpsys window",
            "  mFocusedApp=ActivityRecord{1 u0 com.google.android.tungsten.setupwraith/.RecoveryActivity t1}\n",
        );
        let mut p = Plan::new("t");
        p.push(verify(
            true,
            vec![s(&[
                "adb",
                "shell",
                "pm",
                "enable",
                "--user",
                "0",
                "com.google.android.apps.tv.launcherx",
            ])],
        ));
        let (o, _) = collect(&p, &r, &Store::at(&d));
        assert!(
            matches!(&o, Outcome::Stopped { reason, .. } if reason.starts_with(HOME_FAILED) && reason.contains("switched back on"))
        );
        // Gave up at once (no waiting through every attempt), then ran the rollback.
        let calls = r.calls();
        assert_eq!(
            calls
                .iter()
                .filter(|c| c.contains("dumpsys window"))
                .count(),
            1
        );
        assert!(
            calls
                .last()
                .unwrap()
                .ends_with("pm enable --user 0 com.google.android.apps.tv.launcherx")
        );
    }

    #[test]
    fn cleanup_check_passes_while_home_resolves() {
        let d = tmp("resolve");
        let r = FakeRunner::new().on("resolve-activity", "my.rafhan.app.tvhome/.MainActivity\n");
        let mut p = Plan::new("t");
        p.push(verify(
            false,
            vec![s(&["adb", "shell", "pm", "enable", "com.x"])],
        ));
        assert_eq!(
            collect(&p, &r, &Store::at(&d)).0,
            Outcome::Done { noted: 0 }
        );
        let r = FakeRunner::new().on(
            "resolve-activity",
            "com.android.tv.settings/.system.FallbackHome\n",
        );
        let (o, _) = collect(&p, &r, &Store::at(&d));
        assert!(matches!(o, Outcome::Stopped { .. }));
        assert!(r.calls().last().unwrap().ends_with("pm enable com.x"));
    }
}
