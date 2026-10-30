//! The background thread: everything that talks to the TV or the network runs here, one job at a
//! time, so the screen never freezes. Results come back to the UI as events.

use crate::adb::{self, Adb, RealRunner, Runner};
use crate::backup::Store;
use crate::catalog::{self, Guard};
use crate::device::{self, A11Y_SERVICE, DeviceLine, Found, FoundKind, GTV_LAUNCHER, PKG, TvInfo};
use crate::flows::{self, ApkSource, Env};
use crate::github::{self, Release};
use crate::log::{Level, LogEntry};
use crate::plan::{self, EngineEvent, Outcome, Plan};
use crate::setup;
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

/// What the UI asks for.
#[derive(Debug, Clone)]
pub enum Job {
    Refresh,
    Connect(String),
    Disconnect,
    RestartAdb,
    LoadPackages,
    FetchReleases,
    Build(Request),
    Run(Plan),
    Screenshot,
    Key(&'static str),
    LogcatStart,
    LogcatStop,
    /// Use this adb from now on.
    SetAdb(std::path::PathBuf),
    /// Download Google's Platform Tools into the app folder.
    InstallAdb,
    /// Look for TVs with network debugging on (port 5555) on this computer's network.
    Scan,
    /// `adb pair <addr> <code>` (Wireless debugging on newer Google TV).
    Pair(String, String),
}

/// Actions that change the TV: built into a plan for the preview first.
#[derive(Debug, Clone)]
pub enum Request {
    Install {
        src: ApkSource,
        handover: bool,
    },
    /// Install, clean up and (optionally) block ads in one go.
    SetupAll {
        release: Release,
        handover: bool,
        block_ads: bool,
    },
    /// Turn USB / Wireless debugging off (the last thing before quitting).
    DebuggingOff,
    Uninstall,
    SetEnabled {
        pkgs: Vec<String>,
        enable: bool,
    },
    Restore {
        remove_launcher: bool,
    },
    WifiScanOff,
    PrivateDns,
    RestrictBackground(Vec<String>),
    /// The apps the notes mark `restrict` (PatchWall), if the TV has them.
    RestrictRecommended,
    Reboot,
}

#[derive(Debug, Clone, Default)]
pub struct Status {
    pub adb_path: String,
    pub adb_version: String,
    pub devices: Vec<DeviceLine>,
    pub tv: Option<TvInfo>,
}

#[derive(Debug, Clone)]
pub struct PkgRow {
    pub name: String,
    pub system: bool,
    pub enabled: bool,
}

/// What the worker sends back.
pub enum Event {
    Log(LogEntry),
    Busy(Option<String>),
    Status(Box<Status>),
    Connected(String),
    Packages(Vec<PkgRow>, Guard),
    Releases(Result<Vec<Release>, String>),
    PlanReady(Plan),
    Step(usize, plan::Status),
    Progress(u64, u64),
    Finished(Outcome),
    Notice(String),
    Logcat(String),
    LogcatStopped,
    AdbProgress(u64, u64),
    AdbInstalled(Result<std::path::PathBuf, String>),
    ScanProgress(usize, usize),
    ScanDone(Vec<Found>),
    /// The connected TV's records folder (each TV has its own).
    BackupDir(std::path::PathBuf),
    ConnectFailed(String),
    Paired(Result<String, String>),
}

pub struct Worker {
    adb: Adb,
    runner: Box<dyn Runner>,
    store: Store,
    repo: String,
    dry_run: bool,
    tx: Sender<Event>,
    logcat: Arc<Mutex<Option<Child>>>,
}

pub fn spawn(
    adb: Adb,
    store: Store,
    repo: String,
    dry_run: bool,
    tx: Sender<Event>,
    rx: Receiver<Job>,
) {
    thread::spawn(move || {
        let mut w = Worker {
            adb,
            runner: Box::new(RealRunner),
            store,
            repo,
            dry_run,
            tx,
            logcat: Arc::new(Mutex::new(None)),
        };
        for job in rx {
            w.handle(job);
        }
        w.stop_logcat();
    });
}

impl Worker {
    fn send(&self, e: Event) {
        let _ = self.tx.send(e);
    }

    fn busy(&self, what: Option<&str>) {
        self.send(Event::Busy(what.map(str::to_string)));
    }

    fn log(&self, level: Level, text: impl Into<String>) {
        self.send(Event::Log(LogEntry::now(level, text)));
    }

    /// Runs a command and logs it.
    fn run(&self, argv: Vec<String>, secs: u64) -> adb::Output {
        let tx = self.tx.clone();
        plan::run_logged(
            self.runner.as_ref(),
            &argv,
            Duration::from_secs(secs),
            &mut |e| {
                if let EngineEvent::Log(l) = e {
                    let _ = tx.send(Event::Log(l));
                }
            },
        )
    }

    fn shell_text(&self, args: &[&str]) -> String {
        self.run(self.adb.shell(args), 30).text()
    }

    fn handle(&mut self, job: Job) {
        match job {
            Job::Refresh => {
                self.busy(Some("Reading the TV…"));
                let s = self.status();
                self.send(Event::Status(Box::new(s)));
            }
            Job::Connect(addr) => self.connect(&addr),
            Job::Disconnect => {
                if let Some(s) = self.adb.serial.clone() {
                    self.run(self.adb.global(&["disconnect", &s]), 15);
                }
                let s = self.status();
                self.send(Event::Status(Box::new(s)));
            }
            Job::RestartAdb => {
                self.busy(Some("Restarting adb…"));
                self.run(self.adb.global(&["kill-server"]), 15);
                self.run(self.adb.global(&["start-server"]), 30);
                if let Some(s) = self.adb.serial.clone() {
                    self.run(self.adb.global(&["connect", &s]), 20);
                }
                let s = self.status();
                self.send(Event::Status(Box::new(s)));
            }
            Job::LoadPackages => {
                self.busy(Some("Reading the TV's apps…"));
                let (rows, guard) = self.packages();
                self.send(Event::Packages(rows, guard));
            }
            Job::FetchReleases => {
                self.busy(Some("Asking GitHub for releases…"));
                self.log(
                    Level::Info,
                    format!("GET https://api.github.com/repos/{}/releases", self.repo),
                );
                let r = github::fetch_releases(&self.repo).map_err(|e| format!("{e:#}"));
                if let Err(e) = &r {
                    self.log(Level::Error, e.clone());
                }
                self.send(Event::Releases(r));
            }
            Job::Build(req) => {
                self.busy(Some("Checking the TV…"));
                match self.build(req) {
                    Ok(p) => self.send(Event::PlanReady(p)),
                    Err(e) => {
                        self.log(Level::Error, format!("{e:#}"));
                        self.send(Event::Notice(format!("{e:#}")));
                    }
                }
            }
            Job::Run(p) => {
                self.busy(Some("Running…"));
                let tx = self.tx.clone();
                let outcome = plan::run(
                    &p,
                    self.runner.as_ref(),
                    &self.store,
                    self.dry_run,
                    &mut |e| {
                        let _ = tx.send(match e {
                            EngineEvent::Log(l) => Event::Log(l),
                            EngineEvent::Step(i, s) => Event::Step(i, s),
                            EngineEvent::Progress(d, t) => Event::Progress(d, t),
                        });
                    },
                );
                self.send(Event::Finished(outcome));
            }
            Job::Screenshot => {
                self.busy(Some("Taking a screenshot…"));
                self.screenshot();
            }
            Job::Key(k) => {
                self.run(self.adb.shell(&["input", "keyevent", k]), 15);
            }
            Job::LogcatStart => self.start_logcat(),
            Job::LogcatStop => {
                self.stop_logcat();
                self.send(Event::LogcatStopped);
            }
            Job::SetAdb(p) => self.adb.exe = p,
            Job::InstallAdb => {
                self.busy(Some("Downloading Platform Tools…"));
                self.log(
                    Level::Info,
                    format!("Downloading {}", setup::platform_tools_url().unwrap_or("?")),
                );
                let tx = self.tx.clone();
                let r = setup::install_platform_tools(&self.store.app_dir, &mut |d, t| {
                    let _ = tx.send(Event::AdbProgress(d, t));
                });
                match r {
                    Ok(p) => {
                        self.adb.exe = p.clone();
                        let v = self.run(self.adb.global(&["version"]), 20);
                        if v.ok() {
                            self.send(Event::AdbInstalled(Ok(p)));
                        } else {
                            self.send(Event::AdbInstalled(Err(format!(
                                "adb doesn't start: {}",
                                v.text().trim()
                            ))));
                        }
                    }
                    Err(e) => {
                        self.log(Level::Error, format!("{e:#}"));
                        self.send(Event::AdbInstalled(Err(format!("{e:#}"))));
                    }
                }
            }
            Job::Scan => self.scan(),
            Job::Pair(addr, code) => {
                self.busy(Some("Pairing…"));
                let out = self.run(self.adb.global(&["pair", &addr, &code]), 30);
                let text = out.text();
                if text.to_lowercase().contains("successfully paired") {
                    self.send(Event::Paired(Ok(text.trim().to_string())));
                } else {
                    self.send(Event::Paired(Err(text.trim().to_string())));
                }
            }
        }
        self.busy(None);
    }

    fn connect(&mut self, addr: &str) {
        self.busy(Some("Connecting…"));
        let out = self.run(self.adb.global(&["connect", addr]), 20);
        let text = out.text();
        // Newer adb says "failed to authenticate" while the TV shows "Allow USB debugging?":
        // that's not a failure, the person just has to allow it.
        let waiting = text.contains("authenticate");
        if !device::connect_succeeded(&text) && !waiting {
            self.send(Event::ConnectFailed(text.trim().to_string()));
            return;
        }
        self.adb.serial = Some(addr.to_string());
        if let Err(e) = self.store.write_tv(addr) {
            self.log(Level::Warn, format!("couldn't save the TV's address: {e}"));
        }
        self.send(Event::Connected(addr.to_string()));
        let s = self.status();
        self.send(Event::Status(Box::new(s)));
    }

    fn scan(&mut self) {
        self.busy(Some("Looking for TVs…"));
        // Newer Google TVs with Wireless debugging announce themselves (mDNS): adb lists them.
        let mut found: Vec<Found> =
            device::parse_mdns(&self.run(self.adb.global(&["mdns", "services"]), 15).text());
        let Some(me) = setup::local_ipv4() else {
            self.log(
                Level::Warn,
                "Couldn't tell which network this computer is on.",
            );
            self.send(Event::ScanDone(found));
            return;
        };
        let hosts = setup::neighbours(me);
        self.log(
            Level::Info,
            format!("Looking for TVs with debugging on near {me} (port 5555)…"),
        );
        let tx = self.tx.clone();
        // Two passes over the network count as one bar: (pass, checked) → 0..2×hosts.
        let total = hosts.len() * 2;
        let pass = |offset: usize| {
            let tx = tx.clone();
            let last = std::sync::atomic::AtomicUsize::new(0);
            setup::scan(&hosts, 5555, Duration::from_millis(1200), &move |n, t| {
                // A dozen updates are plenty.
                if n == t || n - last.load(std::sync::atomic::Ordering::Relaxed) >= 20 {
                    last.store(n, std::sync::atomic::Ordering::Relaxed);
                    let _ = tx.send(Event::ScanProgress(offset + n, total));
                }
            })
        };
        // A TV on Wi-Fi can be slow to answer the very first time (the network first has to
        // find it), so a second pass catches what the first missed.
        let mut open = pass(0);
        if open.is_empty() && found.is_empty() {
            open = pass(hosts.len());
        }
        for ip in open {
            // mDNS may have listed it already (as ip:5555); other ports are different ways in.
            if !found.iter().any(|f| f.addr == format!("{ip}:5555")) {
                found.push(Found {
                    addr: ip.to_string(),
                    kind: FoundKind::Network,
                });
            }
        }
        let list: Vec<String> = found.iter().map(|f| f.addr.clone()).collect();
        self.log(
            Level::Info,
            format!(
                "Found: {}",
                if list.is_empty() {
                    "nothing".into()
                } else {
                    list.join(", ")
                }
            ),
        );
        self.send(Event::ScanDone(found));
        // Fill in names for TVs adb already knows.
        let s = self.status();
        self.send(Event::Status(Box::new(s)));
    }

    fn status(&mut self) -> Status {
        let ver = self.run(self.adb.global(&["version"]), 10).text();
        let devices =
            device::parse_devices(&self.run(self.adb.global(&["devices", "-l"]), 15).text());
        let mut st = Status {
            adb_path: self.adb.exe.display().to_string(),
            adb_version: ver.lines().next().unwrap_or("").trim().to_string(),
            devices,
            tv: None,
        };
        let Some(serial) = self.adb.serial.clone() else {
            return st;
        };
        let state = st
            .devices
            .iter()
            .find(|d| d.serial == serial)
            .map(|d| d.state.clone())
            .unwrap_or_else(|| "not connected".into());
        let mut tv = TvInfo {
            serial: serial.clone(),
            state: state.clone(),
            ..Default::default()
        };
        if state == "device" {
            let props = device::parse_getprop(&self.shell_text(&["getprop"]));
            let p = |k: &str| props.get(k).cloned().unwrap_or_default();
            tv.model = p("ro.product.model");
            tv.brand = p("ro.product.brand");
            tv.android = p("ro.build.version.release");
            tv.sdk = p("ro.build.version.sdk").parse().unwrap_or(0);
            tv.id = device::tv_id(&props, &serial);
            let before = self.store.backup.clone();
            let label = format!("{} {}\n{serial}", tv.brand, tv.model);
            match self.store.use_tv(&tv.id, &label) {
                Ok(dir) if dir != before => {
                    self.log(Level::Info, format!("This TV's records: {}", dir.display()));
                    self.send(Event::BackupDir(dir));
                }
                Ok(_) => {}
                Err(e) => self.log(
                    Level::Warn,
                    format!("couldn't set up this TV's records: {e:#}"),
                ),
            }
            let host = serial.rsplit_once(':').map(|(h, _)| h).unwrap_or(&serial);
            tv.ip = if host.chars().all(|c| c.is_ascii_digit() || c == '.') {
                Some(host.to_string())
            } else {
                device::parse_inet(&self.shell_text(&["ip", "-f", "inet", "addr", "show", "wlan0"]))
            };
            let pkg = self.shell_text(&["dumpsys", "package", PKG]);
            tv.launcher_version = device::parse_version_name(&pkg);
            tv.launcher_code = device::parse_version_code(&pkg);
            tv.home = device::parse_resolved_component(&self.shell_text(&[
                "cmd",
                "package",
                "resolve-activity",
                "--brief",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.HOME",
            ]));
            tv.focused = device::parse_focused_package(
                &self.shell_text(&["dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"]),
            );
            tv.a11y_on = self
                .shell_text(&[
                    "settings",
                    "get",
                    "secure",
                    "enabled_accessibility_services",
                ])
                .split(':')
                .any(|s| s.trim() == A11Y_SERVICE);
            tv.google_tv = !device::parse_packages(&self.shell_text(&[
                "pm",
                "list",
                "packages",
                GTV_LAUNCHER,
            ]))
            .is_empty();
        }
        st.tv = Some(tv);
        st
    }

    fn packages(&mut self) -> (Vec<PkgRow>, Guard) {
        let system = device::parse_packages(&self.shell_text(&["pm", "list", "packages", "-s"]));
        let user = device::parse_packages(&self.shell_text(&["pm", "list", "packages", "-3"]));
        let disabled = device::parse_packages(&self.shell_text(&["pm", "list", "packages", "-d"]));
        let ime = device::ime_package(&self.shell_text(&[
            "settings",
            "get",
            "secure",
            "default_input_method",
        ]));
        let homes = device::parse_components(&self.shell_text(&[
            "cmd",
            "package",
            "query-activities",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME",
        ]));
        let guard = Guard {
            ime,
            home_apps: catalog::home_packages(&homes),
            stock_home: self
                .store
                .stock_home()
                .map(|s| device::package_of(&s).to_string()),
        };
        let mut rows: Vec<PkgRow> = system
            .iter()
            .chain(user.iter())
            .map(|n| PkgRow {
                name: n.clone(),
                system: system.contains(n),
                enabled: !disabled.contains(n),
            })
            .collect();
        rows.sort_by(|a, b| a.name.cmp(&b.name));
        rows.dedup_by(|a, b| a.name == b.name);
        (rows, guard)
    }

    fn build(&mut self, req: Request) -> anyhow::Result<Plan> {
        let tx = self.tx.clone();
        let mut emit = |e: EngineEvent| {
            if let EngineEvent::Log(l) = e {
                let _ = tx.send(Event::Log(l));
            }
        };
        let mut env = Env {
            adb: &self.adb,
            runner: self.runner.as_ref(),
            store: &self.store,
            emit: &mut emit,
        };
        if env.adb.serial.is_none() {
            anyhow::bail!("Connect to a TV first (Status → c).");
        }
        Ok(match req {
            Request::Install { src, handover } => flows::install(&mut env, &src, handover)?,
            Request::SetupAll {
                release,
                handover,
                block_ads,
            } => flows::setup_all(&mut env, &release, handover, block_ads)?,
            Request::DebuggingOff => flows::debugging_off(&mut env),
            Request::Uninstall => flows::uninstall(&mut env),
            Request::SetEnabled { pkgs, enable } => flows::set_enabled(&mut env, &pkgs, enable)?,
            Request::Restore { remove_launcher } => flows::restore(&mut env, remove_launcher),
            Request::WifiScanOff => flows::wifi_scan_off(&mut env),
            Request::PrivateDns => flows::private_dns(&mut env),
            Request::RestrictBackground(p) => flows::restrict_background(&mut env, &p),
            Request::RestrictRecommended => flows::restrict_recommended(&mut env),
            Request::Reboot => flows::reboot(&mut env),
        })
    }

    fn screenshot(&mut self) {
        let name = format!(
            "tv-{}.png",
            crate::log::LogEntry::now(Level::Info, "")
                .time
                .replace(':', "")
        );
        let dest = self.store.shots.join(&name);
        if let Err(e) = std::fs::create_dir_all(&self.store.shots) {
            self.send(Event::Notice(format!(
                "Couldn't create {}: {e}",
                self.store.shots.display()
            )));
            return;
        }
        let shot = self.run(
            self.adb
                .shell(&["screencap", "-p", "/sdcard/_tvhome_shot.png"]),
            30,
        );
        if !shot.ok() {
            self.send(Event::Notice("The TV refused the screenshot.".into()));
            return;
        }
        let dest_s = dest.display().to_string();
        let pull = self.run(
            self.adb.cmd(&["pull", "/sdcard/_tvhome_shot.png", &dest_s]),
            60,
        );
        self.run(self.adb.shell(&["rm", "/sdcard/_tvhome_shot.png"]), 15);
        if pull.ok() && dest.is_file() {
            self.send(Event::Notice(format!(
                "Screenshot saved: {}",
                dest.display()
            )));
        } else {
            self.send(Event::Notice(
                "Couldn't copy the screenshot from the TV.".into(),
            ));
        }
    }

    fn start_logcat(&mut self) {
        self.stop_logcat();
        let pid = self
            .shell_text(&["pidof", PKG])
            .split_whitespace()
            .next()
            .map(str::to_string);
        let mut args: Vec<String> = vec!["logcat".into(), "-v".into(), "time".into()];
        match &pid {
            Some(p) => args.push(format!("--pid={p}")),
            None => {
                self.log(
                    Level::Warn,
                    "The launcher isn't running: showing warnings and errors from every app.",
                );
                args.push("*:W".into());
            }
        }
        let argv = self
            .adb
            .cmd(&args.iter().map(String::as_str).collect::<Vec<_>>());
        self.log(
            Level::Cmd,
            format!("$ {}  (streaming)", adb::display(&argv)),
        );
        let child = Command::new(&argv[0])
            .args(&argv[1..])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn();
        match child {
            Ok(mut c) => {
                let out = c.stdout.take().expect("piped");
                *self.logcat.lock().unwrap() = Some(c);
                let tx = self.tx.clone();
                thread::spawn(move || {
                    for line in BufReader::new(out).lines().map_while(Result::ok) {
                        if tx.send(Event::Logcat(line)).is_err() {
                            break;
                        }
                    }
                    let _ = tx.send(Event::LogcatStopped);
                });
            }
            Err(e) => self.send(Event::Notice(format!("Couldn't start logcat: {e}"))),
        }
    }

    fn stop_logcat(&mut self) {
        if let Some(mut c) = self.logcat.lock().unwrap().take() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}
