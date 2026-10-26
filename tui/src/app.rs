//! The app's state and what every choice does. Screens are described in screens.rs, drawn by
//! ui.rs; everything slow runs on the worker.

use crate::adb::{self, Adb};
use crate::backup::{Config, Store};
use crate::catalog::{self, Catalog, Guard};
use crate::device::{self, Found, FoundKind};
use crate::flows::{self, ApkSource};
use crate::github::Release;
use crate::log::{Level, Log, LogEntry};
use crate::plan::{self, Outcome, Plan};
use crate::screens::{Act, Kind};
use crate::setup;
use crate::worker::{Event, Job, PkgRow, Request, Status};
use ratatui::crossterm::event::{
    KeyCode, KeyEvent, KeyModifiers, MouseButton, MouseEvent, MouseEventKind,
};
use ratatui::layout::Rect;
use std::collections::{BTreeSet, VecDeque};
use std::path::PathBuf;
use std::sync::mpsc::Sender;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq)]
pub enum Screen {
    NeedAdb,
    GettingAdb,
    ManualAdb,
    FindTv,
    Connecting,
    Allow,
    HowTo,
    Home,
    /// "Set up my TV": install, clean up and block ads in one go.
    Setup,
    Install,
    Cleanup,
    CleanupAll,
    Tweaks,
    Undo,
    Tools,
    Logcat,
    Confirm,
    Running,
    Help,
    /// Quitting while connected: offer to turn USB debugging off first.
    Leave,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Conn {
    None,
    Connecting,
    /// Connected, waiting for "Allow USB debugging?" on the TV.
    Waiting,
    Ready,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Input {
    Address,
    ApkPath,
    PairAddress,
    PairCode(String),
    PairConnect,
}

pub enum Modal {
    Input {
        purpose: Input,
        title: String,
        hint: String,
        value: String,
    },
    Notice(String),
}

pub struct App {
    pub screen: Screen,
    back: Vec<Screen>,
    /// Selected item; `None`: the screen's default.
    pub focus: Option<usize>,
    /// Where each item was drawn (for clicks), filled by ui.rs.
    pub item_rects: Vec<(usize, Rect)>,
    /// Lines scrolled in the area under the choices (commands, logcat).
    pub after_scroll: u16,

    pub store: Store,
    pub catalog: Catalog,
    pub adb: Adb,
    pub adb_found: bool,
    explicit_adb: Option<PathBuf>,
    pub dry_run: bool,
    /// Plain symbols for old consoles (see ui::plain).
    pub ascii: bool,
    /// NO_COLOR: no colours, highlights as reversed text.
    pub no_color: bool,
    pub config: Config,
    jobs: Sender<Job>,
    pub quit: bool,
    pub busy: Option<String>,
    pub modal: Option<Modal>,

    pub log: Log,
    pub show_log: bool,
    pub problems_only: bool,
    pub log_scroll: usize,

    pub adb_progress: (u64, u64),
    pub conn: Conn,
    pub target: Option<String>,
    pub scan: Option<(usize, usize)>,
    pub scan_done: bool,
    pub found: Vec<Found>,
    pub status: Option<Status>,
    last_poll: Instant,

    pub releases: Vec<Release>,
    pub releases_error: Option<String>,
    pub releases_loaded: bool,
    pub handover: bool,
    handover_touched: bool,
    /// Set up my TV: also block ads on the whole TV.
    pub block_ads: bool,

    pub pkgs: Vec<PkgRow>,
    pub guard: Guard,
    pub pkgs_loaded: bool,
    pub marks: BTreeSet<String>,
    marks_ready: bool,
    pub filter: String,
    pub searching: bool,
    pub remove_launcher: bool,
    /// Clean up: show the "only if you don't use them" apps.
    pub show_optional: bool,

    pub logcat: VecDeque<String>,
    pub logcat_on: bool,
    pub logcat_paused: bool,

    pub plan: Option<Plan>,
    pub statuses: Vec<plan::Status>,
    pub progress: Option<(u64, u64)>,
    pub outcome: Option<Outcome>,
    pub show_commands: bool,
    plan_origin: Screen,
    /// Quit once this plan (turning USB debugging off) has finished.
    pub quit_after_plan: bool,
    /// The pairing address last used (Wireless debugging).
    pair_addr: Option<String>,
}

impl App {
    pub fn new(
        adb: Adb,
        adb_found: bool,
        explicit_adb: Option<PathBuf>,
        store: Store,
        dry_run: bool,
        jobs: Sender<Job>,
    ) -> Self {
        App {
            screen: Screen::FindTv,
            back: Vec::new(),
            focus: None,
            item_rects: Vec::new(),
            after_scroll: 0,
            catalog: Catalog::builtin(),
            config: Config::load(&store),
            log: Log::new(Some(store.log_file())),
            store,
            adb,
            adb_found,
            explicit_adb,
            dry_run,
            ascii: false,
            no_color: false,
            jobs,
            quit: false,
            busy: None,
            modal: None,
            show_log: false,
            problems_only: false,
            log_scroll: 0,
            adb_progress: (0, 0),
            conn: Conn::None,
            target: None,
            scan: None,
            scan_done: false,
            found: Vec::new(),
            status: None,
            last_poll: Instant::now(),
            releases: Vec::new(),
            releases_error: None,
            releases_loaded: false,
            handover: false,
            handover_touched: false,
            block_ads: true,
            pkgs: Vec::new(),
            guard: Guard::default(),
            pkgs_loaded: false,
            marks: BTreeSet::new(),
            marks_ready: false,
            filter: String::new(),
            searching: false,
            remove_launcher: false,
            show_optional: false,
            logcat: VecDeque::new(),
            logcat_on: false,
            logcat_paused: false,
            plan: None,
            statuses: Vec::new(),
            progress: None,
            outcome: None,
            show_commands: false,
            plan_origin: Screen::Home,
            quit_after_plan: false,
            pair_addr: None,
        }
    }

    pub fn send(&self, job: Job) {
        let _ = self.jobs.send(job);
    }

    pub fn tv(&self) -> Option<&device::TvInfo> {
        self.status.as_ref()?.tv.as_ref()
    }

    pub fn refusal(&self, pkg: &str) -> Option<String> {
        catalog::refusal(&self.catalog, &self.guard, pkg)
    }

    fn notice(&mut self, text: impl Into<String>) {
        self.modal = Some(Modal::Notice(text.into()));
    }

    fn input(&mut self, purpose: Input, title: &str, hint: &str, value: String) {
        self.modal = Some(Modal::Input {
            purpose,
            title: title.into(),
            hint: hint.into(),
            value,
        });
    }

    // ---------------------------------------------------------------- moving between screens

    fn go(&mut self, s: Screen) {
        if self.screen != s {
            self.back.push(self.screen.clone());
        }
        self.enter(s);
    }

    fn enter(&mut self, s: Screen) {
        self.screen = s;
        self.focus = None;
        self.after_scroll = 0;
        self.searching = false;
        match self.screen {
            Screen::Install if !self.releases_loaded || self.releases_error.is_some() => {
                self.releases_error = None;
                self.releases_loaded = false;
                self.send(Job::FetchReleases);
            }
            Screen::Cleanup | Screen::CleanupAll if !self.pkgs_loaded => {
                self.send(Job::LoadPackages)
            }
            Screen::Setup => {
                if !self.releases_loaded || self.releases_error.is_some() {
                    self.releases_error = None;
                    self.releases_loaded = false;
                    self.send(Job::FetchReleases);
                }
                // For "turn off N apps".
                if !self.pkgs_loaded {
                    self.send(Job::LoadPackages);
                }
            }
            _ => {}
        }
    }

    fn go_back(&mut self) {
        let prev = self.back.pop().unwrap_or(if self.conn == Conn::Ready {
            Screen::Home
        } else {
            Screen::FindTv
        });
        self.enter(prev);
    }

    fn go_home(&mut self) {
        self.back.clear();
        self.enter(Screen::Home);
    }

    /// First screen: get adb, then reconnect to the last TV or look for one.
    pub fn start(&mut self, tv: Option<String>) {
        if !self.adb_found {
            self.enter(Screen::NeedAdb);
            return;
        }
        match tv {
            Some(addr) => self.connect(addr),
            None => self.find_tvs(),
        }
    }

    fn find_tvs(&mut self) {
        self.back.clear();
        self.enter(Screen::FindTv);
        self.found.clear();
        self.scan_done = false;
        self.scan = Some((0, 254));
        self.send(Job::Scan);
    }

    fn connect(&mut self, addr: String) {
        self.target = Some(addr.clone());
        self.conn = Conn::Connecting;
        self.back = vec![Screen::FindTv];
        self.enter(Screen::Connecting);
        self.send(Job::Connect(addr));
    }

    /// Asks the worker to build a plan; the confirmation page opens when it's ready.
    fn request(&mut self, r: Request) {
        self.try_request(r);
    }

    /// `request`, false when it can't right now (a notice says why).
    fn try_request(&mut self, r: Request) -> bool {
        if self.busy.is_some() {
            self.notice("Still busy with the last thing: try again in a moment.");
            return false;
        }
        if self.conn != Conn::Ready {
            self.notice("Connect to a TV first.");
            return false;
        }
        self.plan_origin = self.screen.clone();
        self.send(Job::Build(r));
        true
    }

    // ---------------------------------------------------------------- what choices do

    pub fn act(&mut self, a: Act) {
        match a {
            Act::Nothing => {}
            Act::Back => match self.screen {
                Screen::Connecting | Screen::Allow => {
                    self.conn = Conn::None;
                    self.find_tvs();
                }
                _ => self.go_back(),
            },
            // Connected: offer to turn USB debugging off on the way out.
            Act::Quit if self.conn == Conn::Ready && self.screen != Screen::Leave => {
                self.go(Screen::Leave)
            }
            Act::Quit | Act::QuitNow => self.quit = true,
            Act::DebuggingOffAndQuit => {
                self.quit_after_plan = self.try_request(Request::DebuggingOff);
            }
            Act::SetupAll => match flows::latest_with_apk(&self.releases).cloned() {
                Some(release) => self.request(Request::SetupAll {
                    release,
                    handover: self.handover,
                    block_ads: self.block_ads,
                }),
                None => self.notice("Couldn't get TV Home from GitHub yet. Is this computer online?"),
            },
            Act::ToggleBlockAds => self.block_ads = !self.block_ads,
            Act::PairAt(addr) => self.input(
                Input::PairCode(addr),
                "Pairing code",
                "The 6-digit code shown on the TV under \"Pair device with pairing code\".",
                String::new(),
            ),
            Act::SupportReport => match crate::report::save(self) {
                Ok(path) => self.notice(format!(
                    "Saved a support report:\n\n    {}\n\nIt lists this computer, the TV and what the app did (no passwords or \
                     personal files). Attach it when you ask for help.",
                    path.display()
                )),
                Err(e) => self.notice(format!("Couldn't save the report: {e:#}")),
            },
            Act::Go(s) => self.go(s),
            Act::DownloadAdb => {
                self.adb_progress = (0, 0);
                self.enter(Screen::GettingAdb);
                self.send(Job::InstallAdb);
            }
            Act::CheckAdbAgain => match adb::find_adb(self.explicit_adb.as_deref(), &setup::local_adb(&self.store.app_dir)) {
                Some(p) => self.use_adb(p),
                None => self.notice("Still can't find adb. Did you open a new terminal after installing it?"),
            },
            Act::Scan => self.find_tvs(),
            Act::ConnectTo(addr) => self.connect(addr),
            Act::TypeAddress => {
                let v = self.target.clone().or_else(|| self.store.read_tv()).unwrap_or_default();
                self.input(Input::Address, "The TV's address", "On the TV: Settings → Network & Internet → your Wi-Fi. For example 192.168.1.50", v);
            }
            Act::PairWithCode => self.input(
                Input::PairAddress,
                "Pairing address",
                "On the TV: Developer options → Wireless debugging → Pair device with pairing code. Type the \"IP address & Port\" shown there, e.g. 192.168.1.50:37123",
                String::new(),
            ),
            Act::Disconnect => {
                self.send(Job::Disconnect);
                self.conn = Conn::None;
                self.status = None;
                self.store.backup = self.store.backup_root.clone();
                self.pkgs_loaded = false;
                self.marks_ready = false;
                self.marks.clear();
                self.find_tvs();
            }
            Act::InstallRelease(i) => match self.releases.get(i).cloned() {
                Some(r) => self.request(Request::Install { src: ApkSource::Release(r), handover: self.handover }),
                None => self.notice("That version isn't available."),
            },
            Act::InstallFile => self.input(
                Input::ApkPath,
                "Install from a file",
                "Type or drag the APK file's path into this window, then press Enter.",
                String::new(),
            ),
            Act::ToggleHandover => {
                self.handover = !self.handover;
                self.handover_touched = true;
            }
            Act::FetchReleases => {
                self.releases_error = None;
                self.releases_loaded = false;
                self.send(Job::FetchReleases);
            }
            Act::ToggleApp(p) => {
                if !self.marks.remove(&p) {
                    match self.refusal(&p) {
                        Some(why) => self.notice(format!("This one can't be turned off.\n\n{why}")),
                        None => {
                            self.marks.insert(p);
                        }
                    }
                }
            }
            Act::TurnOffTicked | Act::TurnOnTicked => {
                let enable = a == Act::TurnOnTicked;
                if self.marks.is_empty() {
                    self.notice("Tick some apps first (Space, or click them).");
                } else {
                    let pkgs = self.marks.iter().cloned().collect();
                    self.request(Request::SetEnabled { pkgs, enable });
                }
            }
            Act::TickRecommended => {
                let (rec, _) = self.cleanup_lists();
                self.marks.extend(rec);
            }
            Act::ClearTicks => self.marks.clear(),
            Act::ToggleOptional => self.show_optional = !self.show_optional,
            Act::RestrictTicked => {
                if self.marks.is_empty() {
                    self.notice("Tick some apps first (Space, or click them).");
                } else {
                    let pkgs = self.marks.iter().cloned().collect();
                    self.request(Request::RestrictBackground(pkgs));
                }
            }
            Act::RestartAdb => {
                self.send(Job::RestartAdb);
                self.find_tvs();
            }
            Act::Tweak(0) => self.request(Request::WifiScanOff),
            Act::Tweak(_) => self.request(Request::PrivateDns),
            Act::UndoAll => self.request(Request::Restore { remove_launcher: self.remove_launcher }),
            Act::ToggleRemoveLauncher => self.remove_launcher = !self.remove_launcher,
            Act::Screenshot => self.send(Job::Screenshot),
            Act::LogcatToggle => {
                if self.logcat_on {
                    self.send(Job::LogcatStop);
                } else {
                    self.logcat_on = true;
                    self.send(Job::LogcatStart);
                }
            }
            Act::LogcatPause => self.logcat_paused = !self.logcat_paused,
            Act::PressHome => self.send(Job::Key("KEYCODE_HOME")),
            Act::ShowRemoteAddress => match self.tv().and_then(|t| t.ip.clone()) {
                Some(ip) => self.notice(format!(
                    "On your phone (same Wi-Fi), open this in the browser:\n\n    http://{ip}:{}\n\nThen type the PIN the TV shows.",
                    device::REMOTE_PORT
                )),
                None => self.notice("Connect to the TV first."),
            },
            Act::RemoveLauncher => self.request(Request::Uninstall),
            Act::Reboot => self.request(Request::Reboot),
            Act::Start => {
                if let Some(p) = self.plan.clone() {
                    self.statuses = vec![plan::Status::Pending; p.steps.len()];
                    self.outcome = None;
                    self.progress = None;
                    self.enter(Screen::Running);
                    self.send(Job::Run(p));
                }
            }
            Act::ToggleCommands => self.show_commands = !self.show_commands,
            Act::CancelPlan => {
                self.quit_after_plan = false;
                if let Some(p) = &self.plan {
                    self.log.info(format!("Cancelled: {}", p.title));
                }
                self.plan = None;
                self.return_from_plan();
            }
            Act::RetryWithUninstall => {
                if let Some(retry) = self.plan.as_ref().and_then(|p| p.retry_after_uninstall.clone()) {
                    self.plan = Some(*retry);
                    self.show_commands = false;
                    self.enter(Screen::Confirm);
                }
            }
            Act::ShowLog => {
                self.show_log = true;
                self.log_scroll = 0;
            }
            Act::Done if self.quit_after_plan => self.quit = true,
            Act::Done => {
                self.plan = None;
                self.outcome = None;
                self.return_from_plan();
            }
        }
    }

    /// Back to the screen the plan was started from. Opening the confirmation page put that screen
    /// on the back stack; take it off again, so Esc there goes where it went before.
    fn return_from_plan(&mut self) {
        let o = self.plan_origin.clone();
        if self.back.last() == Some(&o) {
            self.back.pop();
        }
        self.enter(o);
    }

    fn use_adb(&mut self, path: PathBuf) {
        self.adb.exe = path.clone();
        self.adb_found = true;
        self.send(Job::SetAdb(path));
        let tv = self.store.read_tv();
        self.start(tv);
    }

    fn submit_input(&mut self, purpose: Input, value: String) {
        let v = value.trim().to_string();
        match purpose {
            Input::Address => match device::normalize_address(&v) {
                Some(a) => self.connect(a),
                None => self.notice(format!(
                    "\"{v}\" doesn't look like an address. It should look like 192.168.1.50"
                )),
            },
            Input::ApkPath => {
                let p = PathBuf::from(
                    v.trim_matches(|c| c == '"' || c == '\'')
                        .replace("\\ ", " "),
                );
                if p.is_file() {
                    self.request(Request::Install {
                        src: ApkSource::Local(p),
                        handover: self.handover,
                    });
                } else {
                    self.notice(format!("There's no file at {}", p.display()));
                }
            }
            Input::PairAddress => match device::normalize_address(&v) {
                Some(a) if v.contains(':') => self.input(
                    Input::PairCode(a),
                    "Pairing code",
                    "The 6-digit code shown on the TV under \"Pair device with pairing code\".",
                    String::new(),
                ),
                _ => self.notice(
                    "Type the address with its port, as the TV shows it, e.g. 192.168.1.50:37123",
                ),
            },
            Input::PairCode(addr) => {
                if v.len() < 6 || !v.chars().all(|c| c.is_ascii_digit()) {
                    self.notice("The pairing code is the 6 digits shown on the TV.");
                } else {
                    self.pair_addr = Some(addr.clone());
                    self.send(Job::Pair(addr, v));
                }
            }
            Input::PairConnect => match device::normalize_address(&v) {
                Some(a) => self.connect(a),
                None => self.notice("Type the address with its port, e.g. 192.168.1.50:41235"),
            },
        }
    }

    // ---------------------------------------------------------------- events from the worker

    pub fn on_event(&mut self, e: Event) {
        match e {
            Event::Log(l) => self.push_log(l),
            Event::Busy(b) => self.busy = b,
            Event::AdbProgress(d, t) => self.adb_progress = (d, t),
            Event::AdbInstalled(Ok(p)) => {
                self.log
                    .info(format!("Platform Tools installed: {}", p.display()));
                self.use_adb(p);
            }
            Event::AdbInstalled(Err(e)) => {
                self.enter(Screen::NeedAdb);
                self.notice(format!(
                    "The download didn't work: {e}\n\nCheck this computer is online and try again."
                ));
            }
            Event::ScanProgress(n, t) => self.scan = Some((n, t)),
            Event::BackupDir(dir) => self.store.backup = dir,
            Event::ScanDone(list) => {
                self.found = list;
                self.scan = None;
                self.scan_done = true;
            }
            Event::Status(s) => self.on_status(*s),
            Event::Connected(addr) => {
                self.adb.serial = Some(addr.clone());
                self.config.remember(&addr);
                if let Err(e) = self.config.save(&self.store) {
                    self.log.error(format!("couldn't save settings: {e}"));
                }
            }
            Event::ConnectFailed(msg) => {
                self.conn = Conn::None;
                self.back.clear();
                self.enter(Screen::FindTv);
                self.notice(format!(
                    "Couldn't reach the TV.\n\n{msg}\n\nCheck the TV is on, on the same Wi-Fi, and that USB debugging is on."
                ));
            }
            Event::Paired(Ok(_)) => {
                // The TV may have announced its connection address already (mDNS).
                let host = self
                    .pair_addr
                    .as_deref()
                    .and_then(|a| a.rsplit_once(':'))
                    .map(|(h, _)| h.to_string())
                    .unwrap_or_default();
                let guess = self
                    .found
                    .iter()
                    .find(|f| {
                        f.kind == FoundKind::Wireless && f.addr.starts_with(&format!("{host}:"))
                    })
                    .map(|f| f.addr.clone())
                    .unwrap_or_default();
                self.input(
                    Input::PairConnect,
                    "Paired! Now the connection address",
                    "On the same Wireless debugging screen, the \"IP address & Port\" at the top, e.g. 192.168.1.50:41235",
                    guess,
                )
            }
            Event::Paired(Err(e)) => self.notice(format!(
                "Pairing didn't work: {e}\n\nCheck the code and address, and try again."
            )),
            Event::Packages(rows, guard) => {
                self.pkgs = rows;
                self.guard = guard;
                self.pkgs_loaded = true;
                if !self.marks_ready {
                    let (rec, _) = self.cleanup_lists();
                    self.marks = rec.into_iter().collect();
                    self.marks_ready = true;
                }
            }
            Event::Releases(r) => {
                self.releases_loaded = true;
                match r {
                    Ok(list) => {
                        self.releases = list;
                        self.releases_error = None;
                    }
                    Err(e) => self.releases_error = Some(e),
                }
            }
            Event::PlanReady(p) => {
                self.plan = Some(p);
                self.show_commands = false;
                if self.screen != Screen::Confirm {
                    self.back.push(self.screen.clone());
                }
                self.enter(Screen::Confirm);
            }
            Event::Step(i, st) => {
                if i < self.statuses.len() {
                    if !matches!(st, plan::Status::Running) {
                        self.progress = None;
                    }
                    self.statuses[i] = st;
                }
            }
            Event::Progress(d, t) => self.progress = Some((d, t)),
            Event::Finished(o) if self.quit_after_plan => {
                // USB debugging is off: the TV won't answer any more, so don't ask it anything.
                self.outcome = Some(o);
                self.focus = None;
            }
            Event::Finished(o) => {
                // The ticks were for what just ran.
                let was_apps = self
                    .plan
                    .as_ref()
                    .map(|p| p.title.contains(" app"))
                    .unwrap_or(false);
                if was_apps && matches!(o, Outcome::Done { .. }) {
                    self.marks.clear();
                }
                self.outcome = Some(o);
                self.focus = None;
                self.send(Job::Refresh);
                if self.pkgs_loaded {
                    self.send(Job::LoadPackages);
                }
            }
            Event::Notice(n) => self.notice(n),
            Event::Logcat(line) => {
                if !self.logcat_paused {
                    if line.contains(" E/") || line.contains(" F/") {
                        self.push_log(LogEntry::now(Level::Error, format!("logcat: {line}")));
                    }
                    self.logcat.push_back(line);
                    while self.logcat.len() > 2000 {
                        self.logcat.pop_front();
                    }
                }
            }
            Event::LogcatStopped => self.logcat_on = false,
        }
    }

    fn on_status(&mut self, s: Status) {
        let state = s.tv.as_ref().map(|t| t.state.clone());
        if !self.handover_touched
            && let Some(t) = &s.tv
            && t.state == "device"
        {
            self.handover = t.google_tv;
        }
        self.status = Some(s);
        let waiting_for_tv = matches!(self.screen, Screen::Connecting | Screen::Allow);
        match state.as_deref() {
            Some("device") => {
                let was_ready = self.conn == Conn::Ready;
                self.conn = Conn::Ready;
                if !was_ready {
                    if !self.releases_loaded {
                        self.send(Job::FetchReleases);
                    }
                    if waiting_for_tv || self.screen == Screen::FindTv {
                        self.go_home();
                    }
                }
            }
            Some("unauthorized") if waiting_for_tv => {
                self.conn = Conn::Waiting;
                if self.screen != Screen::Allow {
                    self.enter(Screen::Allow);
                }
            }
            Some(_) if self.conn == Conn::Ready && !waiting_for_tv => {
                // The TV went away (switched off, Wi-Fi dropped).
                self.conn = Conn::None;
                self.notice("The TV stopped answering. Check it's on, then choose \"Use a different TV\" or connect again.");
            }
            _ => {}
        }
    }

    /// Called every frame: while waiting for "Allow USB debugging?", keep asking the TV.
    pub fn tick(&mut self) {
        if self.screen == Screen::Allow
            && self.busy.is_none()
            && self.last_poll.elapsed() > Duration::from_secs(2)
        {
            self.last_poll = Instant::now();
            self.send(Job::Refresh);
        }
    }

    fn push_log(&mut self, l: LogEntry) {
        let shown = !self.problems_only || l.is_problem();
        self.log.push(l);
        if self.log_scroll > 0 && shown {
            self.log_scroll += 1;
        }
    }

    // ---------------------------------------------------------------- keys and mouse

    /// The selected item, resolving "the screen's default".
    pub fn focused(&self, items: &[crate::screens::Item]) -> Option<usize> {
        match self.focus {
            Some(f) if f < items.len() && items[f].selectable() => Some(f),
            _ => items
                .iter()
                .position(|i| i.default && i.selectable())
                .or_else(|| items.iter().position(|i| i.selectable())),
        }
    }

    fn move_focus(&mut self, d: isize) {
        let items = self.view().items;
        let sel: Vec<usize> = items
            .iter()
            .enumerate()
            .filter(|(_, i)| i.selectable())
            .map(|(n, _)| n)
            .collect();
        if sel.is_empty() {
            return;
        }
        let cur = self
            .focused(&items)
            .and_then(|f| sel.iter().position(|&s| s == f))
            .unwrap_or(0) as isize;
        let next = (cur + d).clamp(0, sel.len() as isize - 1) as usize;
        self.focus = Some(sel[next]);
    }

    fn activate_focused(&mut self, space: bool) {
        let items = self.view().items;
        let Some(f) = self.focused(&items) else {
            return;
        };
        let item = &items[f];
        // Space only ticks boxes; Enter does anything.
        if space && !matches!(item.kind, Kind::Check { .. }) {
            return;
        }
        self.focus = Some(f);
        self.act(item.act.clone());
    }

    pub fn on_key(&mut self, k: KeyEvent) {
        if k.modifiers.contains(KeyModifiers::CONTROL) && k.code == KeyCode::Char('c') {
            self.quit = true;
            return;
        }
        // Ctrl+J / Ctrl+M are Enter in terminals (some send them for the Enter key).
        let k = match k.code {
            KeyCode::Char('j') | KeyCode::Char('m')
                if k.modifiers.contains(KeyModifiers::CONTROL) =>
            {
                KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)
            }
            _ => k,
        };
        if self.show_log {
            match k.code {
                KeyCode::Esc
                | KeyCode::Char('l')
                | KeyCode::Char('L')
                | KeyCode::Char('q')
                | KeyCode::Enter => self.show_log = false,
                KeyCode::Char('e') | KeyCode::Char('E') => {
                    self.problems_only = !self.problems_only;
                    self.log_scroll = 0;
                }
                KeyCode::Up | KeyCode::Char('k') => self.log_scroll += 1,
                KeyCode::Down | KeyCode::Char('j') => {
                    self.log_scroll = self.log_scroll.saturating_sub(1)
                }
                KeyCode::PageUp => self.log_scroll += 15,
                KeyCode::PageDown => self.log_scroll = self.log_scroll.saturating_sub(15),
                KeyCode::End => self.log_scroll = 0,
                _ => {}
            }
            return;
        }
        if let Some(m) = self.modal.take() {
            match m {
                Modal::Notice(_) => {}
                Modal::Input {
                    purpose,
                    title,
                    hint,
                    mut value,
                } => match k.code {
                    KeyCode::Esc => {}
                    KeyCode::Enter => self.submit_input(purpose, value),
                    KeyCode::Backspace => {
                        value.pop();
                        self.modal = Some(Modal::Input {
                            purpose,
                            title,
                            hint,
                            value,
                        });
                    }
                    KeyCode::Char(c) => {
                        value.push(c);
                        self.modal = Some(Modal::Input {
                            purpose,
                            title,
                            hint,
                            value,
                        });
                    }
                    _ => {
                        self.modal = Some(Modal::Input {
                            purpose,
                            title,
                            hint,
                            value,
                        })
                    }
                },
            }
            return;
        }
        if self.searching {
            match k.code {
                KeyCode::Enter | KeyCode::Esc => self.searching = false,
                KeyCode::Backspace => {
                    self.filter.pop();
                }
                KeyCode::Char(c) => self.filter.push(c),
                _ => {}
            }
            self.focus = None;
            return;
        }
        let running = self.screen == Screen::Running && self.outcome.is_none();
        match k.code {
            KeyCode::Char('q') | KeyCode::Char('Q') if !running => self.act(Act::Quit),
            KeyCode::Char('l') | KeyCode::Char('L') => self.act(Act::ShowLog),
            KeyCode::Char('?') if !running => self.go(Screen::Help),
            KeyCode::Char('/') if self.screen == Screen::CleanupAll => {
                self.searching = true;
            }
            KeyCode::Esc if !running => match self.screen {
                Screen::Confirm => self.act(Act::CancelPlan),
                Screen::Running => self.act(Act::Done),
                Screen::Home | Screen::FindTv | Screen::NeedAdb => {}
                Screen::CleanupAll if !self.filter.is_empty() => self.filter.clear(),
                _ => self.act(Act::Back),
            },
            KeyCode::Up | KeyCode::Char('k') | KeyCode::BackTab => self.move_focus(-1),
            KeyCode::Down | KeyCode::Char('j') | KeyCode::Tab => self.move_focus(1),
            KeyCode::Home => self.move_focus(-10_000),
            KeyCode::End => self.move_focus(10_000),
            KeyCode::PageUp => {
                self.move_focus(-8);
                self.after_scroll = self.after_scroll.saturating_sub(8);
            }
            KeyCode::PageDown => {
                self.move_focus(8);
                self.after_scroll += 8;
            }
            KeyCode::Enter if !running => self.activate_focused(false),
            KeyCode::Char(' ') if !running => self.activate_focused(true),
            _ => {}
        }
    }

    pub fn on_mouse(&mut self, m: MouseEvent) {
        if self.show_log {
            match m.kind {
                MouseEventKind::ScrollUp => self.log_scroll += 3,
                MouseEventKind::ScrollDown => self.log_scroll = self.log_scroll.saturating_sub(3),
                MouseEventKind::Down(_) => self.show_log = false,
                _ => {}
            }
            return;
        }
        if self.modal.is_some() {
            if let (MouseEventKind::Down(MouseButton::Left), Some(Modal::Notice(_))) =
                (m.kind, &self.modal)
            {
                self.modal = None;
            }
            return;
        }
        match m.kind {
            MouseEventKind::ScrollUp => self.move_focus(-1),
            MouseEventKind::ScrollDown => self.move_focus(1),
            MouseEventKind::Down(MouseButton::Left) => {
                let hit = self
                    .item_rects
                    .iter()
                    .find(|(_, r)| {
                        m.column >= r.x
                            && m.column < r.x + r.width
                            && m.row >= r.y
                            && m.row < r.y + r.height
                    })
                    .map(|(i, _)| *i);
                if let Some(i) = hit {
                    let items = self.view().items;
                    if let Some(item) = items.get(i)
                        && item.selectable()
                        && !(self.screen == Screen::Running && self.outcome.is_none())
                    {
                        self.focus = Some(i);
                        self.act(item.act.clone());
                    }
                }
            }
            _ => {}
        }
    }
}
