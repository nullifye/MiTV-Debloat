//! What each screen says and offers, in plain words. Every screen is a few lines of text and a
//! list of choices (arrow keys + Enter, or a click); ui.rs draws them all the same way.

use crate::app::{App, Conn, Screen};
use crate::catalog::Advice;
use crate::device::{self, FoundKind};
use crate::flows;
use crate::github::{self, human_size};
use crate::plan::{self, Outcome};
use crate::power::{State, WakeKind};
use crate::setup;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use std::cmp::Ordering;

pub const ACCENT: Color = Color::Cyan;
pub const OK: Color = Color::Green;
pub const WARN: Color = Color::Yellow;
pub const BAD: Color = Color::Red;
pub const DIM: Color = Color::DarkGray;

/// What choosing an item does (handled in app.rs).
#[derive(Debug, Clone, PartialEq)]
pub enum Act {
    Nothing,
    Back,
    /// Quit (asks about USB debugging first when connected).
    Quit,
    QuitNow,
    DebuggingOffAndQuit,
    Go(Screen),
    SetupAll,
    ToggleBlockAds,
    SupportReport,
    // adb
    DownloadAdb,
    CheckAdbAgain,
    // finding the TV
    Scan,
    ConnectTo(String),
    TypeAddress,
    PairWithCode,
    /// A TV announced it's waiting to pair at this address.
    PairAt(String),
    Disconnect,
    // install
    InstallRelease(usize),
    InstallFile,
    FetchReleases,
    // clean up
    ToggleApp(String),
    TurnOffTicked,
    TurnOnTicked,
    TickRecommended,
    ClearTicks,
    ToggleOptional,
    RestrictTicked,
    RestartAdb,
    // other actions
    Tweak(usize),
    UndoAll,
    ToggleRemoveLauncher,
    Screenshot,
    LogcatToggle,
    LogcatPause,
    PressHome,
    ShowRemoteAddress,
    RemoveLauncher,
    Reboot,
    CheckPower,
    SetPowerMac(WakeKind),
    UseDetectedMac(WakeKind),
    Wake(WakeKind),
    SendWake,
    Standby,
    SendStandby,
    // plans
    Start,
    ToggleCommands,
    CancelPlan,
    RetryWithUninstall,
    ShowLog,
    Done,
}

#[derive(Debug, Clone)]
pub enum Kind {
    /// A choice: a title and a short explanation under it.
    Choice { title: String, desc: String },
    /// A tick box.
    Check {
        on: bool,
        title: String,
        desc: String,
    },
    /// A button.
    Button(String),
    /// A heading between items (not selectable).
    Heading(String),
}

#[derive(Debug, Clone)]
pub struct Item {
    pub kind: Kind,
    pub act: Act,
    /// The first thing selected when the screen opens.
    pub default: bool,
}

impl Item {
    pub fn selectable(&self) -> bool {
        !matches!(self.kind, Kind::Heading(_))
    }
}

pub fn choice(title: impl Into<String>, desc: impl Into<String>, act: Act) -> Item {
    Item {
        kind: Kind::Choice {
            title: title.into(),
            desc: desc.into(),
        },
        act,
        default: false,
    }
}

pub fn check(on: bool, title: impl Into<String>, desc: impl Into<String>, act: Act) -> Item {
    Item {
        kind: Kind::Check {
            on,
            title: title.into(),
            desc: desc.into(),
        },
        act,
        default: false,
    }
}

pub fn button(title: impl Into<String>, act: Act) -> Item {
    Item {
        kind: Kind::Button(title.into()),
        act,
        default: false,
    }
}

pub fn heading(title: impl Into<String>) -> Item {
    Item {
        kind: Kind::Heading(title.into()),
        act: Act::Nothing,
        default: false,
    }
}

fn first(mut i: Item) -> Item {
    i.default = true;
    i
}

pub struct View {
    pub title: String,
    pub body: Vec<Line<'static>>,
    pub items: Vec<Item>,
    /// A progress bar: (0.0–1.0, label).
    pub gauge: Option<(f64, String)>,
    /// Lines under the choices (e.g. a running plan's steps).
    pub after: Vec<Line<'static>>,
}

impl View {
    fn new(title: impl Into<String>) -> Self {
        View {
            title: title.into(),
            body: Vec::new(),
            items: Vec::new(),
            gauge: None,
            after: Vec::new(),
        }
    }
    fn say(&mut self, s: impl Into<String>) {
        self.body.push(Line::from(s.into()));
    }
    fn dim(&mut self, s: impl Into<String>) {
        self.body
            .push(Line::from(Span::styled(s.into(), Style::new().fg(DIM))));
    }
    fn color(&mut self, s: impl Into<String>, c: Color) {
        self.body
            .push(Line::from(Span::styled(s.into(), Style::new().fg(c))));
    }
    fn bold(&mut self, s: impl Into<String>) {
        self.body.push(Line::from(Span::styled(
            s.into(),
            Style::new().add_modifier(Modifier::BOLD),
        )));
    }
    fn gap(&mut self) {
        self.body.push(Line::default());
    }
    fn push(&mut self, i: Item) {
        self.items.push(i);
    }
}

impl App {
    pub fn view(&self) -> View {
        let v = match &self.screen {
            Screen::NeedAdb => self.need_adb(),
            Screen::GettingAdb => self.getting_adb(),
            Screen::ManualAdb => manual_adb(),
            Screen::FindTv => self.find_tv(),
            Screen::Connecting => self.connecting(),
            Screen::Allow => self.allow(),
            Screen::HowTo => how_to(),
            Screen::Home => self.home(),
            Screen::Setup => self.setup(),
            Screen::Leave => self.leave(),
            Screen::Install => self.install(),
            Screen::Cleanup => self.cleanup(),
            Screen::CleanupAll => self.cleanup_all(),
            Screen::Tweaks => self.tweaks(),
            Screen::Undo => self.undo(),
            Screen::Tools => self.tools(),
            Screen::Power => self.power_view(),
            Screen::ConfirmWake => self.confirm_wake(),
            Screen::ConfirmStandby => self.confirm_standby(),
            Screen::Logcat => self.logcat_view(),
            Screen::Confirm => self.confirm(),
            Screen::Running => self.running(),
            Screen::Help => self.help(),
        };
        if setup::termux() {
            phone_words_view(v)
        } else {
            v
        }
    }

    // ---------------------------------------------------------------- adb

    fn need_adb(&self) -> View {
        let mut v = View::new("One small thing first");
        v.say("To talk to the TV, this computer needs a free helper from Google called Android Platform Tools (\"adb\"). It isn't installed yet.");
        v.gap();
        if setup::termux() {
            v.say(
                "On this phone, Termux installs it. Run this in Termux, then choose Check again:",
            );
            v.gap();
            v.bold("  pkg install android-tools");
            v.push(first(choice(
                "Check again",
                "Once it's installed",
                Act::CheckAdbAgain,
            )));
            if self.power_target().is_some() {
                v.push(choice(
                    "Wake a saved TV",
                    "Sending WoWiFi/WOL does not need adb",
                    Act::Go(Screen::Power),
                ));
            }
            v.push(choice("Quit", "", Act::Quit));
            return v;
        }
        if setup::platform_tools_url().is_some() {
            v.say("I can download it for you (about 10 MB). It goes into this app's own folder:");
            v.dim(format!(
                "  {}",
                self.store.app_dir.join("platform-tools").display()
            ));
            v.say("Nothing else on your computer is changed.");
            v.gap();
            v.dim(format!(
                "By downloading, you accept Google's Android SDK terms: {}",
                setup::TERMS_URL
            ));
            v.push(first(choice(
                "Download it for me",
                "Recommended. Takes a few seconds.",
                Act::DownloadAdb,
            )));
        } else {
            v.say(
                "Google doesn't offer a download for this computer: please install adb yourself.",
            );
        }
        v.push(choice(
            "I'll install it myself",
            "Shows the command for your computer.",
            Act::Go(Screen::ManualAdb),
        ));
        if self.power_target().is_some() {
            v.push(choice(
                "Wake a saved TV",
                "Sending WoWiFi/WOL does not need adb",
                Act::Go(Screen::Power),
            ));
        }
        v.push(choice("Quit", "", Act::Quit));
        v
    }

    fn getting_adb(&self) -> View {
        let mut v = View::new("Getting Android Platform Tools");
        v.say("Downloading from Google…");
        let (d, t) = self.adb_progress;
        let ratio = if t > 0 { d as f64 / t as f64 } else { 0.0 };
        v.gauge = Some((
            ratio,
            if t > 0 {
                format!("{} of {}", human_size(d), human_size(t))
            } else {
                human_size(d)
            },
        ));
        v
    }

    // ---------------------------------------------------------------- finding the TV

    fn find_tv(&self) -> View {
        let mut v = View::new("Find your TV");
        v.say("Make sure the TV is on, on the same Wi-Fi as this computer, and has USB debugging turned on (not sure? choose \"How do I turn on USB debugging?\" below).");
        v.gap();
        match &self.scan {
            Some((n, t)) if n < t => v.color(
                format!("Looking for TVs on your Wi-Fi…  {}%", n * 100 / (*t).max(1)),
                WARN,
            ),
            _ if self.scan_done && self.found.is_empty() => v.dim(
                "No TVs found on your Wi-Fi. Is USB debugging on? You can also type the address.",
            ),
            _ if self.scan_done => v.color(
                format!(
                    "Found {} TV{} with debugging on:",
                    self.found.len(),
                    if self.found.len() == 1 { "" } else { "s" }
                ),
                OK,
            ),
            _ => {}
        }
        let known = self.known_models();
        let model = |addr: &str| {
            known
                .iter()
                .find(|(s, _)| s == addr)
                .map(|(_, m)| format!("  ({m})"))
                .unwrap_or_default()
        };
        let mut shown: Vec<String> = Vec::new();
        for f in &self.found {
            let host = f.addr.split(':').next().unwrap_or(&f.addr).to_string();
            let item = match f.kind {
                FoundKind::Network => {
                    let addr = format!("{host}:5555");
                    let i = choice(
                        format!("TV at {host}{}", model(&addr)),
                        "Found on your Wi-Fi",
                        Act::ConnectTo(addr.clone()),
                    );
                    shown.push(addr);
                    i
                }
                FoundKind::Wireless => {
                    shown.push(f.addr.clone());
                    choice(
                        format!("TV at {host}{}", model(&f.addr)),
                        "Wireless debugging is on (already paired with this computer)",
                        Act::ConnectTo(f.addr.clone()),
                    )
                }
                FoundKind::Pairing => choice(
                    format!("TV at {host} wants to pair"),
                    "Its \"Pair device with pairing code\" screen is open: choose this, then type the code",
                    Act::PairAt(f.addr.clone()),
                ),
            };
            v.push(if v.items.is_empty() {
                first(item)
            } else {
                item
            });
        }
        // TVs adb is already connected to (from earlier, or another app).
        for (serial, m) in &known {
            if !shown.contains(serial) && serial.contains(':') {
                shown.push(serial.clone());
                v.push(choice(
                    format!("TV at {serial}  ({m})"),
                    "Already connected to this computer",
                    Act::ConnectTo(serial.clone()),
                ));
            }
        }
        for r in &self.config.recent_tvs {
            if !shown.contains(r) {
                v.push(choice(r.clone(), "Used before", Act::ConnectTo(r.clone())));
            }
        }
        self.say_app_update(&mut v);
        v.push(choice(
            "Search again",
            "Look for TVs on the Wi-Fi again",
            Act::Scan,
        ));
        v.push(choice(
            "Type the TV's address",
            "Settings → Network & Internet on the TV shows it, e.g. 192.168.1.50",
            Act::TypeAddress,
        ));
        if self.power_target().is_some() {
            v.push(choice(
                "Wake a saved TV",
                "Power status and wake methods work without a current ADB connection",
                Act::Go(Screen::Power),
            ));
        }
        v.push(choice(
            "My TV shows a pairing code",
            "Newer Google TV: Developer options → Wireless debugging → Pair device with pairing code",
            Act::PairWithCode,
        ));
        v.push(choice(
            "How do I turn on USB debugging?",
            "Step-by-step for any Android TV",
            Act::Go(Screen::HowTo),
        ));
        v.push(choice(
            "Restart the connection helper",
            "Fixes most \"can't connect\" problems (restarts adb on this computer)",
            Act::RestartAdb,
        ));
        v.push(choice("Quit", "", Act::Quit));
        v
    }

    fn connecting(&self) -> View {
        let mut v = View::new("Connecting");
        v.say(format!(
            "Connecting to {}…",
            self.target.clone().unwrap_or_default()
        ));
        v.push(button("Cancel", Act::Back));
        v
    }

    fn allow(&self) -> View {
        let mut v = View::new("Look at your TV");
        v.gap();
        v.bold("A message on the TV asks \"Allow USB debugging?\"");
        v.gap();
        v.say("  1. Tick \"Always allow from this computer\".");
        v.say("  2. Choose OK (or Allow) with the TV remote.");
        v.gap();
        v.color("Waiting for the TV… this continues by itself.", WARN);
        v.gap();
        v.dim("No message? Press Home on the TV remote and look again, or choose \"Try again\".");
        v.push(first(button(
            "Try again",
            Act::ConnectTo(self.target.clone().unwrap_or_default()),
        )));
        v.push(button("Cancel", Act::Back));
        v
    }

    // ---------------------------------------------------------------- main menu

    fn home(&self) -> View {
        let mut v = View::new("What would you like to do?");
        let tv = self.tv();
        if let Some(t) = tv {
            v.body.push(Line::from(vec![
                Span::styled(t.name(), Style::new().add_modifier(Modifier::BOLD)),
                Span::styled(
                    format!("  ·  Android {}  ·  {}", t.android, t.serial),
                    Style::new().fg(DIM),
                ),
            ]));
        }
        if let Some(app) = tv.and_then(|t| t.busy_with()) {
            v.dim(format!(
                "Someone may be watching: {} is on the TV screen right now.",
                device::friendly_app_name(app)
            ));
        }
        let latest = flows::latest_with_apk(&self.releases);
        let installed = tv.and_then(|t| t.launcher_version.clone());
        self.say_app_update(&mut v);
        let setup = choice(
            "Set up my TV (recommended)",
            "All in one go: install TV Home, turn off ads and tracking, block ads.",
            Act::Go(Screen::Setup),
        );
        v.push(if installed.is_none() {
            first(setup)
        } else {
            setup
        });
        let (title, desc) = match (&installed, latest) {
            (None, _) => (
                "Install TV Home".to_string(),
                "A fast, clean home screen with prayer times, a phone remote and more.".to_string(),
            ),
            (Some(i), Some(l)) if github::compare_versions(&l.version, i) == Ordering::Greater => {
                v.color(
                    format!(
                        "TV Home {i} is installed. Version {} is available.",
                        l.version
                    ),
                    WARN,
                );
                (
                    format!("Update TV Home to {}", l.version),
                    "Your settings are kept.".to_string(),
                )
            }
            (Some(i), _) => {
                let home_ok = tv.map(|t| t.home_is_ours()).unwrap_or(false);
                if home_ok {
                    v.color(
                        format!("TV Home {i} is installed and is the home screen. ✓"),
                        OK,
                    );
                } else {
                    v.color(
                        format!("TV Home {i} is installed, but it isn't the home screen."),
                        WARN,
                    );
                }
                (
                    "Reinstall TV Home".to_string(),
                    "Also makes it the home screen again.".to_string(),
                )
            }
        };
        let install = choice(title, desc, Act::Go(Screen::Install));
        v.push(if installed.is_some() {
            first(install)
        } else {
            install
        });
        v.push(choice(
            "Clean up the TV",
            "Turn off ads, tracking and apps you don't use. Nothing is deleted.",
            Act::Go(Screen::Cleanup),
        ));
        v.push(choice(
            "Speed and privacy",
            "Block ads on the whole TV; stop background Wi-Fi scans.",
            Act::Go(Screen::Tweaks),
        ));
        v.push(choice(
            "Undo all changes",
            "Put the TV back the way it was.",
            Act::Go(Screen::Undo),
        ));
        v.push(choice(
            "More tools",
            "Screenshot, live log, restart the TV, install from a file…",
            Act::Go(Screen::Tools),
        ));
        v.push(choice(
            "Use a different TV",
            "Disconnect and find another TV",
            Act::Disconnect,
        ));
        v.push(choice(
            "Help",
            "How this works, and what to do if something goes wrong",
            Act::Go(Screen::Help),
        ));
        v.push(choice("Quit", "", Act::Quit));
        v
    }

    fn setup(&self) -> View {
        let mut v = View::new("Set up my TV");
        let latest = flows::latest_with_apk(&self.releases);
        let installed = self.tv().and_then(|t| t.launcher_version.clone());
        if !self.releases_loaded {
            v.color("Checking GitHub for the newest TV Home…", WARN);
            v.gap();
        } else if latest.is_none() {
            v.color(
                "Couldn't get TV Home from GitHub. Is this computer online?",
                BAD,
            );
            if let Some(e) = &self.releases_error {
                v.dim(e.clone());
            }
            v.gap();
        }
        v.say("In one go, this:");
        let ver = latest.map(|l| l.version.clone()).unwrap_or_default();
        v.say(match &installed {
            Some(i)
                if !ver.is_empty() && github::compare_versions(&ver, i) == Ordering::Greater =>
            {
                format!("  • updates TV Home to {ver} (your settings are kept)")
            }
            Some(_) => "  • reinstalls TV Home and makes sure it's the home screen".to_string(),
            None => format!("  • installs TV Home {ver} and makes it the home screen")
                .replace("Home  and", "Home and"),
        });
        if self.pkgs_loaded {
            let n = self.cleanup_lists().0.len();
            v.say(if n == 0 {
                "  • turns off apps that show ads or track you (none are on right now)".to_string()
            } else {
                format!(
                    "  • turns off {n} app{} that show ads, track you or nag (nothing is deleted)",
                    if n == 1 { "" } else { "s" }
                )
            });
        } else {
            v.say("  • turns off apps that show ads or track you (checking the TV…)");
        }
        if self.block_ads {
            v.say("  • blocks ads on the whole TV");
        }
        if self.handover {
            v.say("  • switches off Google TV's own home screen, so Home opens TV Home (casting from a phone stops until you undo it)");
        }
        v.say("  • checks Home works at the end; if it doesn't, it puts everything back");
        v.gap();
        v.dim("You see the full list before anything changes. \"Undo all changes\" reverses all of it.");
        v.push(check(
            self.block_ads,
            "Also block ads on the whole TV",
            "AdGuard's free ad-blocking DNS, for every app on the TV.",
            Act::ToggleBlockAds,
        ));
        if latest.is_some() {
            v.push(first(button("Continue", Act::SetupAll)));
        } else if self.releases_loaded {
            v.push(first(button("Try again", Act::FetchReleases)));
        }
        v.push(button("Back", Act::Back));
        v
    }

    fn leave(&self) -> View {
        let mut v = View::new("Before you go");
        v.say(
            "USB debugging is still on. While it's on, anyone on your Wi-Fi could control the TV.",
        );
        v.gap();
        v.say("Turning it off is safest. TV Home and the phone remote keep working.");
        v.dim("To use this app again later, turn USB debugging back on (Developer options).");
        v.push(first(choice(
            "Turn off USB debugging and quit",
            "Recommended",
            Act::DebuggingOffAndQuit,
        )));
        v.push(choice(
            "Quit and leave it on",
            "If you'll use this app again soon",
            Act::QuitNow,
        ));
        v.push(choice("Back", "", Act::Back));
        v
    }

    fn install(&self) -> View {
        let mut v = View::new("Install TV Home");
        let installed = self.tv().and_then(|t| t.launcher_version.clone());
        if !self.releases_loaded {
            v.color("Checking GitHub for the newest version…", WARN);
        } else if let Some(e) = &self.releases_error {
            v.color("Couldn't reach GitHub. Is this computer online?", BAD);
            v.dim(e.clone());
            v.push(first(choice("Try again", "", Act::FetchReleases)));
        }
        match &installed {
            Some(i) => v.say(format!("On the TV now: TV Home {i}.")),
            None => v.say("TV Home isn't on this TV yet."),
        }
        if self.handover {
            v.dim("Google TV: its own home screen is switched off too, so Home opens TV Home. Casting from a phone stops until you undo it.");
        }
        v.gap();
        let latest = flows::latest_with_apk(&self.releases);
        if let Some(l) = latest {
            let idx = self.releases.iter().position(|r| r == l).unwrap_or(0);
            let a = l
                .apk
                .as_ref()
                .map(|a| human_size(a.size))
                .unwrap_or_default();
            let what = match &installed {
                Some(i) if github::compare_versions(&l.version, i) == Ordering::Greater => {
                    format!("Update to TV Home {}", l.version)
                }
                Some(i) if i == &l.version => format!("Reinstall TV Home {}", l.version),
                _ => format!("Install TV Home {}", l.version),
            };
            v.push(first(choice(
                what,
                format!(
                    "The newest version ({a}, released {}). Downloads and installs it.",
                    l.date
                ),
                Act::InstallRelease(idx),
            )));
        }
        let older: Vec<(usize, &github::Release)> = self
            .releases
            .iter()
            .enumerate()
            .filter(|(_, r)| r.apk.is_some() && Some(*r) != latest)
            .take(4)
            .collect();
        if !older.is_empty() {
            v.push(heading("Other versions"));
            for (i, r) in older {
                v.push(choice(
                    format!("TV Home {}", r.version),
                    format!("Released {}", r.date),
                    Act::InstallRelease(i),
                ));
            }
        }
        v.push(heading(""));
        v.push(choice(
            "Install from a file on this computer",
            "An APK you downloaded or built yourself",
            Act::InstallFile,
        ));
        v.push(choice("Back", "", Act::Back));
        v
    }

    // ---------------------------------------------------------------- clean up

    fn cleanup(&self) -> View {
        let mut v = View::new("Clean up the TV");
        if !self.pkgs_loaded {
            v.color("Looking at the apps on your TV…", WARN);
            v.push(choice("Back", "", Act::Back));
            return v;
        }
        v.say("Tick the apps to turn off (Space or click). Turned-off apps aren't deleted: \"Undo all changes\" turns them back on any time.");
        let (rec, opt) = self.cleanup_lists();
        let ticked = self.marks.len();
        v.push(first(button(
            if ticked == 0 {
                "Turn off ticked apps (none ticked)".to_string()
            } else {
                format!(
                    "Turn off {ticked} ticked app{}",
                    if ticked == 1 { "" } else { "s" }
                )
            },
            Act::TurnOffTicked,
        )));
        if rec.is_empty() && opt.is_empty() {
            v.push(heading(
                "Nothing left to clean up: the usual suspects are already off. ✓",
            ));
        }
        if rec.iter().any(|p| !self.marks.contains(p)) {
            v.push(button("Tick all recommended", Act::TickRecommended));
        }
        if !rec.is_empty() {
            v.push(heading(
                "Recommended: ads, tracking and things a TV doesn't need",
            ));
            for p in rec {
                let e = self.catalog.get(&p).expect("from catalog");
                v.push(check(
                    self.marks.contains(&p),
                    e.label.clone(),
                    e.note.clone(),
                    Act::ToggleApp(p.clone()),
                ));
            }
        }
        if !opt.is_empty() {
            // Mostly obscure system parts: folded away unless asked for.
            v.push(heading(""));
            v.push(choice(
                if self.show_optional {
                    "Hide the other apps".to_string()
                } else {
                    format!("Show {} more apps you could turn off", opt.len())
                },
                "Only if you don't use them: streaming apps, screensavers and system parts other TV owners turned off",
                Act::ToggleOptional,
            ));
        }
        if self.show_optional && !opt.is_empty() {
            v.push(heading("Only if you don't use them"));
            for p in opt {
                let e = self.catalog.get(&p).expect("from catalog");
                v.push(check(
                    self.marks.contains(&p),
                    e.label.clone(),
                    e.note.clone(),
                    Act::ToggleApp(p.clone()),
                ));
            }
        }
        v.push(heading(""));
        v.push(choice(
            "Show every app on the TV",
            "For people who know what they're doing",
            Act::Go(Screen::CleanupAll),
        ));
        v.push(choice("Back", "", Act::Back));
        v
    }

    fn cleanup_all(&self) -> View {
        let mut v = View::new("Every app on the TV");
        v.say("Tick apps, then turn them off or back on. Apps marked \"needed by the TV\" can't be turned off.");
        v.body.push(Line::from(vec![
            Span::styled("Search: ", Style::new().fg(DIM)),
            Span::styled(
                if self.searching {
                    format!("{}▏", self.filter)
                } else if self.filter.is_empty() {
                    "press / to search".into()
                } else {
                    self.filter.clone()
                },
                Style::new().add_modifier(Modifier::BOLD),
            ),
        ]));
        let n = self.marks.len();
        v.push(first(button(
            format!("Turn off {n} ticked"),
            Act::TurnOffTicked,
        )));
        v.push(button(
            format!("Turn {n} ticked back on"),
            Act::TurnOnTicked,
        ));
        v.push(button(
            "Stop ticked apps running in the background",
            Act::RestrictTicked,
        ));
        v.push(button("Untick all", Act::ClearTicks));
        v.push(button("Back", Act::Back));
        let q = self.filter.to_lowercase();
        for r in &self.pkgs {
            let e = self.catalog.get(&r.name);
            let label = e.map(|e| e.label.clone()).unwrap_or_else(|| r.name.clone());
            if !q.is_empty()
                && !r.name.to_lowercase().contains(&q)
                && !label.to_lowercase().contains(&q)
            {
                continue;
            }
            let state = if r.enabled { "on" } else { "OFF" };
            let advice = e
                .map(|e| format!(" · {} · {}", e.category, advice_word(e.advice)))
                .unwrap_or_default();
            let desc = format!(
                "{} · {}{} · {}",
                state,
                if r.system {
                    "system"
                } else {
                    "installed by you"
                },
                advice,
                r.name
            );
            match self.refusal(&r.name) {
                Some(why) => v.push(choice(
                    format!("{label}  (needed by the TV)"),
                    format!("{desc} — {why}"),
                    Act::Nothing,
                )),
                None => v.push(check(
                    self.marks.contains(&r.name),
                    label,
                    desc,
                    Act::ToggleApp(r.name.clone()),
                )),
            }
        }
        v
    }

    // ---------------------------------------------------------------- tweaks, undo, tools

    fn tweaks(&self) -> View {
        let mut v = View::new("Speed and privacy");
        v.say("Each change remembers the old setting, so \"Undo all changes\" can put it back.");
        let dns = self.store.private_dns_before().is_some();
        let scan = self.store.wifi_scan_before().is_some();
        v.push(first(choice(
            format!(
                "Block ads on the whole TV{}",
                if dns { "  (done before)" } else { "" }
            ),
            "Uses AdGuard's free ad-blocking DNS for every app (Private DNS).",
            Act::Tweak(1),
        )));
        v.push(choice(
            format!(
                "Stop background Wi-Fi scanning{}",
                if scan { "  (done before)" } else { "" }
            ),
            "The TV stops scanning for networks it isn't using. A little less work for the TV.",
            Act::Tweak(0),
        ));
        let limited = self.store.lines(crate::backup::BG_RESTRICTED);
        let done = self
            .catalog
            .restricted()
            .iter()
            .any(|p| limited.iter().any(|l| l == p));
        v.push(choice(
            format!(
                "Stop apps waking up by themselves{}",
                if done { "  (done before)" } else { "" }
            ),
            "Xiaomi's PatchWall runs scheduled jobs while you watch, which can make 4K video stutter. It still opens (the Input button's picker).",
            Act::Tweak(2),
        ));
        v.push(choice("Back", "", Act::Back));
        v
    }

    fn undo(&self) -> View {
        let mut v = View::new("Undo all changes");
        v.say("This puts back everything this app (or the setup scripts) changed:");
        let s = &self.store;
        let apps = s.lines(crate::backup::DISABLED).len();
        v.say(format!(
            "  • {apps} app{} turned back on",
            if apps == 1 { "" } else { "s" }
        ));
        if s.private_dns_before().is_some() || s.wifi_scan_before().is_some() {
            v.say("  • speed and privacy settings put back");
        }
        match s.stock_home() {
            Some(h) => v.say(format!(
                "  • the TV's own home screen made the default again ({})",
                device::package_of(&h)
            )),
            None => v.dim(
                "  • the TV's own home screen wasn't recorded: you may need to pick it on the TV",
            ),
        }
        v.gap();
        v.dim(format!("From: {}", s.backup.display()));
        if s.repo.is_none() {
            v.dim("(This app's own record. If the TV was set up with the repository's scripts, run this app from that folder.)");
        }
        v.push(first(button("Undo all changes", Act::UndoAll)));
        v.push(check(
            self.remove_launcher,
            "Also remove TV Home from the TV",
            "Its settings (dock, wallpaper, prayer zone) are lost.",
            Act::ToggleRemoveLauncher,
        ));
        v.push(choice("Back", "", Act::Back));
        v
    }

    fn tools(&self) -> View {
        let mut v = View::new("More tools");
        v.push(first(choice(
            "Press Home on the TV",
            "Send the Home button over ADB to return to the TV's home screen",
            Act::PressHome,
        )));
        v.push(choice(
            "Take a screenshot of the TV",
            format!("Saved in {}", self.store.shots.display()),
            Act::Screenshot,
        ));
        v.push(choice(
            "Live log from TV Home",
            "For troubleshooting: what the launcher is doing right now",
            Act::Go(Screen::Logcat),
        ));
        v.push(choice(
            "TV power and wake",
            "ADB standby/wake; Wi-Fi and wired LAN wake",
            Act::Go(Screen::Power),
        ));
        v.push(choice(
            "Phone remote address",
            "Control the TV from your phone's browser",
            Act::ShowRemoteAddress,
        ));
        v.push(choice(
            "Install from a file on this computer",
            "An APK you downloaded or built yourself",
            Act::InstallFile,
        ));
        v.push(choice(
            "Every app on the TV",
            "Turn individual apps off or on",
            Act::Go(Screen::CleanupAll),
        ));
        v.push(choice(
            "This program's log",
            "Every command it ran and what the TV answered",
            Act::ShowLog,
        ));
        v.push(choice(
            "Save a support report",
            "A text file to attach when you ask for help",
            Act::SupportReport,
        ));
        v.push(choice(
            "Remove TV Home",
            "Uninstall it and make the TV's own home screen the default",
            Act::RemoveLauncher,
        ));
        v.push(choice(
            "Restart the TV",
            "Reboot over ADB; the TV may take a minute to come back",
            Act::Reboot,
        ));
        v.push(choice("Back", "", Act::Back));
        v
    }

    fn power_view(&self) -> View {
        let mut v = View::new("TV power and wake");
        let Some(addr) = self.power_target() else {
            v.say("Choose a TV first to configure its wake methods.");
            v.push(first(choice("Back", "", Act::Back)));
            return v;
        };
        v.say(format!("TV: {addr}"));
        match &self.power_state {
            Some((state, why, at)) => {
                let status = format!(
                    "{}  ·  checked {} seconds ago",
                    state.label(),
                    at.elapsed().as_secs()
                );
                match state {
                    State::Online => v.body.push(Line::from(Span::styled(
                        status,
                        Style::new().fg(OK).add_modifier(Modifier::BOLD),
                    ))),
                    State::Offline => v.body.push(Line::from(Span::styled(
                        status,
                        Style::new().fg(BAD).add_modifier(Modifier::BOLD),
                    ))),
                    _ => v.bold(status),
                }
                v.dim(why.clone());
            }
            None => v.dim("Checking the TV…"),
        }
        v.gap();
        v.push(first(choice(
            "Check again",
            "Read Android's power state over ADB",
            Act::CheckPower,
        )));
        let adb_ready = self.adb_found
            && self
                .power_state
                .as_ref()
                .is_some_and(|(state, _, _)| *state == crate::power::State::Standby);
        v.push(choice(
            "Wake via ADB",
            if adb_ready {
                "TV is reachable in standby; sends KEYCODE_WAKEUP"
            } else {
                "Only available while the TV is reachable through ADB and reports Standby"
            },
            if adb_ready {
                Act::Wake(WakeKind::Adb)
            } else {
                Act::CheckPower
            },
        ));
        let online = self.adb_found
            && self
                .power_state
                .as_ref()
                .is_some_and(|(state, _, _)| *state == crate::power::State::Online);
        v.push(choice(
            "Standby via ADB",
            if online {
                "Presses Power only after rechecking the TV is awake; ADB may disconnect"
            } else {
                "Only available while the TV is reachable and reports Online"
            },
            if online {
                Act::Standby
            } else {
                Act::CheckPower
            },
        ));
        for kind in [WakeKind::Wifi, WakeKind::Ethernet] {
            if let Some(mac) = self.detected_mac(kind) {
                v.push(choice(
                    format!(
                        "Use detected {} MAC: {mac}",
                        if kind == WakeKind::Wifi {
                            "Wi-Fi"
                        } else {
                            "Ethernet"
                        }
                    ),
                    "Save it for waking this TV when ADB is unavailable",
                    Act::UseDetectedMac(kind),
                ));
            }
        }
        let cfg = self.power_config().cloned().unwrap_or_default();
        for (kind, ready, detail) in [
            (WakeKind::Wifi, !cfg.wifi_mac.is_empty(), "Wi-Fi MAC"),
            (
                WakeKind::Ethernet,
                !cfg.ethernet_mac.is_empty(),
                "Ethernet MAC",
            ),
        ] {
            v.push(choice(
                format!("Wake via {}", kind.label()),
                if ready {
                    "Configured".into()
                } else {
                    format!("Unavailable: configure {detail} below")
                },
                if ready {
                    Act::Wake(kind)
                } else {
                    Act::SetPowerMac(kind)
                },
            ));
        }
        v.gap();
        for kind in [WakeKind::Wifi, WakeKind::Ethernet] {
            v.push(choice(
                format!("Configure {}", kind.label()),
                "Empty input clears the setting",
                Act::SetPowerMac(kind),
            ));
        }
        v.dim("Offline means ADB/network unreachable, not proof that mains power is off. WoWiFi requires TV firmware support; a sent packet does not prove it woke.");
        v.push(choice("Back", "", Act::Back));
        v
    }

    fn confirm_wake(&self) -> View {
        let mut v = View::new("Send wake request?");
        if let Some(kind) = self.pending_wake {
            v.say(format!(
                "Send {} for {}?",
                kind.label(),
                self.power_target().unwrap_or("this TV")
            ));
            v.dim("This sends one request, then checks whether Android responds. No settings are changed on the TV.");
        }
        v.push(first(button("Send wake request", Act::SendWake)));
        v.push(button("Cancel", Act::Back));
        v
    }

    fn confirm_standby(&self) -> View {
        let mut v = View::new("Put TV in standby?");
        v.say(format!(
            "Press Power on {} to enter standby?",
            self.power_target().unwrap_or("this TV")
        ));
        v.color("This is a power toggle, sent only if Android still reports Awake. A remote press at the same time could reverse it. Some TVs disconnect ADB and cannot wake over Wi-Fi; keep the physical remote nearby.", WARN);
        v.push(first(button("Put in standby", Act::SendStandby)));
        v.push(button("Cancel", Act::Back));
        v
    }

    fn logcat_view(&self) -> View {
        let mut v = View::new("Live log from TV Home");
        v.dim(if self.logcat_on {
            if self.logcat_paused {
                "Paused."
            } else {
                "Live. Errors are shown in red and also go to this program's log."
            }
        } else {
            "Stopped."
        });
        v.push(first(button(
            if self.logcat_on { "Stop" } else { "Start" },
            Act::LogcatToggle,
        )));
        v.push(button(
            if self.logcat_paused {
                "Resume"
            } else {
                "Pause"
            },
            Act::LogcatPause,
        ));
        v.push(button("Back", Act::Back));
        for l in self
            .logcat
            .iter()
            .rev()
            .take(200)
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
        {
            let c = if l.contains(" E/") || l.contains(" F/") {
                BAD
            } else if l.contains(" W/") {
                WARN
            } else {
                Color::Reset
            };
            v.after
                .push(Line::from(Span::styled(l.clone(), Style::new().fg(c))));
        }
        v
    }

    // ---------------------------------------------------------------- plans

    fn confirm(&self) -> View {
        let Some(p) = &self.plan else {
            return View::new("");
        };
        let mut v = View::new(format!(
            "Ready to {}?",
            lower_first(&friendly_title(&p.title))
        ));
        for w in &p.warnings {
            v.color(format!("⚠  {w}"), WARN);
        }
        if !p.warnings.is_empty() {
            v.gap();
        }
        if p.is_empty() {
            v.say("There's nothing to do.");
            for n in &p.notes {
                v.dim(n.clone());
            }
            v.push(first(button("OK", Act::CancelPlan)));
            return v;
        }
        v.say("This will:");
        for s in p.summary_lines() {
            v.say(format!("  • {s}"));
        }
        if self.dry_run {
            v.gap();
            v.color(
                "Practice mode (--dry-run): nothing will actually change.",
                WARN,
            );
        }
        v.push(first(button(
            if self.dry_run {
                "Start (practice)"
            } else {
                "Start"
            },
            Act::Start,
        )));
        v.push(button(
            if self.show_commands {
                "Hide the exact commands"
            } else {
                "Show the exact commands"
            },
            Act::ToggleCommands,
        ));
        v.push(button("Cancel", Act::CancelPlan));
        if self.show_commands {
            for n in &p.notes {
                v.after
                    .push(Line::from(Span::styled(n.clone(), Style::new().fg(DIM))));
            }
            for (i, s) in p.steps.iter().enumerate() {
                v.after.push(Line::from(vec![
                    Span::styled(format!("{:>3}. ", i + 1), Style::new().fg(DIM)),
                    Span::raw(s.label.clone()),
                    Span::styled(
                        if s.kind == plan::Kind::Optional && s.changes_tv() {
                            "  (optional)"
                        } else {
                            ""
                        },
                        Style::new().fg(DIM),
                    ),
                ]));
                v.after.push(Line::from(Span::styled(
                    format!("     {}", s.command_line()),
                    Style::new().fg(ACCENT),
                )));
            }
        }
        v
    }

    fn running(&self) -> View {
        let Some(p) = &self.plan else {
            return View::new("");
        };
        let total = p.steps.len().max(1);
        let finished = self
            .statuses
            .iter()
            .filter(|s| !matches!(s, plan::Status::Pending | plan::Status::Running))
            .count();
        let current = self
            .statuses
            .iter()
            .position(|s| matches!(s, plan::Status::Running));
        let mut v = View::new(friendly_title(&p.title));
        match &self.outcome {
            None => {
                v.color("Working… please leave the TV on.", WARN);
                if let Some(i) = current {
                    v.say(format!("Step {} of {}: {}", i + 1, total, p.steps[i].label));
                }
                v.gauge = Some(match self.progress {
                    Some((d, t)) if t > 0 => (
                        d as f64 / t as f64,
                        format!("Downloading {} of {}", human_size(d), human_size(t)),
                    ),
                    _ => (
                        finished as f64 / total as f64,
                        format!("{finished} of {total}"),
                    ),
                });
            }
            Some(o) => {
                let refused: Vec<&str> = p
                    .steps
                    .iter()
                    .zip(&self.statuses)
                    .filter(|(_, s)| matches!(s, plan::Status::Noted(_)))
                    .map(|(st, _)| st.label.as_str())
                    .collect();
                match o {
                    Outcome::Done { .. } => {
                        v.color("✓ Done!", OK);
                        v.gap();
                        v.bold(if p.done.is_empty() {
                            "Finished.".to_string()
                        } else {
                            p.done.clone()
                        });
                        if !refused.is_empty() {
                            v.gap();
                            v.dim(format!(
                                "The TV said no to {} small thing{} (that's normal on some TVs): {}.",
                                refused.len(),
                                if refused.len() == 1 { "" } else { "s" },
                                refused.join(", ")
                            ));
                        }
                        v.push(first(button(
                            if self.quit_after_plan {
                                "Close the app"
                            } else {
                                "OK"
                            },
                            Act::Done,
                        )));
                    }
                    Outcome::DryRun => {
                        v.color("Practice run finished: nothing was changed.", WARN);
                        v.dim("The log (L) shows every command that would have run.");
                        v.push(first(button(
                            if self.quit_after_plan {
                                "Close the app"
                            } else {
                                "OK"
                            },
                            Act::Done,
                        )));
                    }
                    Outcome::Incompatible => {
                        v.color(
                            "The TV Home already on this TV can't be updated in place.",
                            BAD,
                        );
                        v.gap();
                        v.say("It was installed from somewhere else (for example built on another computer).");
                        v.say("To install this one, the old copy has to be removed first.");
                        v.color("Removing it resets TV Home's settings (dock, wallpaper, prayer zone, paired phones).", WARN);
                        v.push(first(button(
                            "Remove the old copy and install",
                            Act::RetryWithUninstall,
                        )));
                        v.push(button("Cancel", Act::Done));
                    }
                    Outcome::Stopped { reason, .. } => {
                        v.color("Something went wrong.", BAD);
                        v.gap();
                        v.say(friendly_error(reason));
                        v.dim(format!("The TV said: {reason}"));
                        v.push(first(button("OK", Act::Done)));
                    }
                }
                v.push(button("Show details (log)", Act::ShowLog));
            }
        }
        // The steps, ticking off.
        for (i, s) in p.steps.iter().enumerate() {
            let st = self
                .statuses
                .get(i)
                .cloned()
                .unwrap_or(plan::Status::Pending);
            let (icon, c) = match &st {
                plan::Status::Pending => (" · ", DIM),
                plan::Status::Running => (" … ", WARN),
                plan::Status::Ok(_) => (" ✓ ", OK),
                plan::Status::Noted(_) => (" ! ", WARN),
                plan::Status::Failed(_) => (" ✗ ", BAD),
                plan::Status::Skipped(_) => (" – ", DIM),
            };
            v.after.push(Line::from(vec![
                Span::styled(icon, Style::new().fg(c)),
                Span::styled(
                    s.label.clone(),
                    Style::new().fg(if matches!(st, plan::Status::Pending) {
                        DIM
                    } else {
                        Color::Reset
                    }),
                ),
            ]));
        }
        v
    }

    // ---------------------------------------------------------------- helpers

    /// Recommended (safe) and optional (caution) apps that are on and may be turned off.
    pub fn cleanup_lists(&self) -> (Vec<String>, Vec<String>) {
        let mut rec = Vec::new();
        let mut opt = Vec::new();
        for r in self.pkgs.iter().filter(|r| r.enabled) {
            let Some(e) = self.catalog.get(&r.name) else {
                continue;
            };
            if self.refusal(&r.name).is_some() {
                continue;
            }
            match e.advice {
                Advice::Safe => rec.push(r.name.clone()),
                Advice::Caution => opt.push(r.name.clone()),
                _ => {}
            }
        }
        (rec, opt)
    }

    /// A newer release of this app on GitHub.
    pub fn app_update(&self) -> Option<&github::Release> {
        github::newer_app(&self.releases, env!("CARGO_PKG_VERSION"))
    }

    /// Under the text of the main screens: where to get the newer version of this app.
    fn say_app_update(&self, v: &mut View) {
        if let Some(r) = self.app_update() {
            v.gap();
            v.color(
                format!(
                    "A newer version of this app ({}) is out: https://github.com/{}/releases/latest",
                    r.version, self.repo
                ),
                WARN,
            );
        }
    }

    /// (serial, model) of TVs adb already knows.
    fn known_models(&self) -> Vec<(String, String)> {
        self.status
            .as_ref()
            .map(|s| {
                s.devices
                    .iter()
                    .filter_map(|d| d.model.clone().map(|m| (d.serial.clone(), m)))
                    .collect()
            })
            .unwrap_or_default()
    }

    /// The status line at the top of every screen.
    pub fn header(&self, width: u16) -> Vec<Span<'static>> {
        // A phone in portrait: leave out the TV's address and launcher version.
        let narrow = width < 80;
        let mut v = vec![
            Span::styled(
                if narrow {
                    " TV Home "
                } else {
                    " TV Home Setup "
                },
                Style::new()
                    .fg(Color::Black)
                    .bg(ACCENT)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
        ];
        match (&self.conn, self.tv()) {
            (Conn::Ready, Some(t)) => {
                v.push(Span::styled("● ", Style::new().fg(OK)));
                v.push(Span::raw(t.name()));
                if !narrow {
                    v.push(Span::styled(
                        format!("  {}", t.serial),
                        Style::new().fg(DIM),
                    ));
                }
                if let Some(lv) = t.launcher_version.as_ref().filter(|_| !narrow) {
                    v.push(Span::styled(
                        format!("  ·  TV Home {lv}"),
                        Style::new().fg(DIM),
                    ));
                }
            }
            (Conn::Waiting, _) => {
                v.push(Span::styled("● waiting for the TV", Style::new().fg(WARN)))
            }
            _ => v.push(Span::styled("○ no TV connected", Style::new().fg(DIM))),
        }
        if let Some(r) = self.app_update() {
            v.push(Span::styled(
                format!("  New version {}", r.version),
                Style::new().fg(WARN),
            ));
        }
        if self.dry_run {
            v.push(Span::styled(
                "  PRACTICE MODE ",
                Style::new().fg(Color::Black).bg(WARN),
            ));
        }
        if let Some(b) = &self.busy {
            v.push(Span::styled(format!("  ⟳ {b}"), Style::new().fg(WARN)));
        }
        v
    }
}

fn manual_adb() -> View {
    let mut v = View::new("Install adb yourself");
    v.say("Open a terminal and run the command for your computer:");
    v.gap();
    v.bold("  Windows (PowerShell):   winget install --id Google.PlatformTools -e");
    v.bold("  macOS:                  brew install android-platform-tools");
    v.bold("  Ubuntu / Debian:        sudo apt install adb");
    v.bold("  Fedora:                 sudo dnf install android-tools");
    v.bold("  Android phone (Termux): pkg install android-tools");
    v.gap();
    v.say("Then close this window, open a new terminal, and start this app again.");
    v.push(first(choice(
        "Check again",
        "If you've just installed it",
        Act::CheckAdbAgain,
    )));
    v.push(choice("Back", "", Act::Back));
    v
}

fn how_to() -> View {
    let mut v = View::new("Turn on USB debugging on the TV");
    v.say("With the TV remote:");
    v.gap();
    v.say("  1. Open Settings → Device Preferences (or System) → About.");
    v.say("  2. Select \"Build\" (Google TV: \"Android TV OS build\") 7 times, until it says \"You are now a developer\".");
    v.say("  3. Go back to Device Preferences (or System) → Developer options.");
    v.say("  4. Turn on \"USB debugging\". Some TVs call it \"Network debugging\" or \"ADB over network\". Newer Google TVs may only have \"Wireless debugging\": turn that on and use \"My TV shows a pairing code\" on the previous screen.");
    v.say("  5. Note the TV's IP address: Settings → Network & Internet → your Wi-Fi.");
    v.gap();
    v.dim("The TV must run Android TV or Google TV 8.0 or newer, and be on the same Wi-Fi as this computer.");
    v.push(first(choice("Back", "", Act::Back)));
    v
}

impl App {
    fn help(&self) -> View {
        let mut v = View::new("Help");
        v.bold("Using this app");
        v.say(
            "  ↑ ↓ choose · Enter select · Space tick · Esc go back · or just click with the mouse",
        );
        v.say("  L shows everything this app did (E: only problems) · Q quits");
        v.gap();
        v.bold("Is it safe?");
        v.say("  Before anything changes, you see what will happen and choose Start. Apps are only turned off, never deleted, and \"Undo all changes\" puts everything back.");
        v.gap();
        v.bold("When you're done");
        v.say("  Quit (Q): the app offers to turn USB debugging off, because while it's on, anyone on your Wi-Fi could control the TV.");
        v.gap();
        v.bold("If something goes wrong");
        v.say("  • Can't find or connect to the TV: check it's on, on the same Wi-Fi, and USB debugging is on. Restart the TV and try again.");
        v.say("  • Home still opens the old home screen: install TV Home again (on Google TV it also switches Google TV's home screen off).");
        v.say(
            "  • Everything this app did is in its log (L), also saved as tui.log in its folder.",
        );
        v.gap();
        if let Some(st) = &self.status {
            v.dim(format!("adb: {} ({})", st.adb_version, st.adb_path));
        }
        v.dim(format!(
            "This app's folder: {}",
            self.store.app_dir.display()
        ));
        v.push(first(choice("Back", "", Act::Back)));
        v
    }
}

fn advice_word(a: Advice) -> &'static str {
    match a {
        Advice::Safe => "safe to turn off",
        Advice::Caution => "turn off only if unused",
        Advice::Keep => "better kept on",
        Advice::Never => "needed by the TV",
        Advice::Handover => "Google TV home",
    }
}

fn friendly_title(t: &str) -> String {
    t.replace("Install tvhome-", "Install TV Home ")
        .replace(".apk", "")
}

fn lower_first(t: &str) -> String {
    let mut c = t.chars();
    match c.next() {
        Some(f) => f.to_lowercase().chain(c).collect(),
        None => String::new(),
    }
}

/// A plain-words reading of a failure.
pub fn friendly_error(reason: &str) -> String {
    let r = reason.to_lowercase();
    if reason.starts_with(plan::HOME_FAILED) {
        return if r.contains("switched back on") {
            "Home didn't open TV Home, so the apps this switched off were switched back on. Nothing is \
             broken: the TV works as before. You can try again."
                .into()
        } else {
            "Home didn't open TV Home. Press Home on the TV remote and look; if the old home screen \
             comes up, install TV Home again."
                .into()
        };
    }
    if r.contains("no devices")
        || r.contains("offline")
        || r.contains("not found") && r.contains("device")
        || r.contains("closed")
    {
        "The TV stopped answering. Check it's still on and on the Wi-Fi, then try again.".into()
    } else if r.contains("unauthorized") {
        "The TV hasn't allowed this computer yet: accept \"Allow USB debugging?\" on the TV.".into()
    } else if r.contains("download")
        || r.contains("github")
        || r.contains("dns")
        || r.contains("connect")
    {
        "A download failed. Check this computer is online and try again.".into()
    } else if r.contains("space") || r.contains("storage") {
        "The TV is out of storage space. Remove an app you don't need and try again.".into()
    } else if r.contains("older_sdk") || r.contains("sdk") {
        "This TV's Android version is too old for TV Home (it needs Android 8.0 or newer).".into()
    } else {
        "It didn't finish. Nothing important was left half-done; you can try again.".into()
    }
}

/// In Termux the app runs on a phone, not a computer: "this computer" → "this phone" and so on.
/// The TV's own prompt, "Always allow from this computer", keeps its wording.
pub fn phone_words(s: &str) -> String {
    const KEEP: &str = "allow from this computer";
    s.split(KEEP)
        .map(|part| {
            part.replace("this computer", "this phone")
                .replace("your computer", "your phone")
                .replace("the computer", "the phone")
        })
        .collect::<Vec<_>>()
        .join(KEEP)
}

fn phone_words_line(l: Line<'static>) -> Line<'static> {
    let spans = l
        .spans
        .into_iter()
        .map(|sp| Span::styled(phone_words(&sp.content), sp.style))
        .collect::<Vec<_>>();
    Line::from(spans).style(l.style)
}

fn phone_words_view(mut v: View) -> View {
    v.title = phone_words(&v.title);
    v.body = v.body.into_iter().map(phone_words_line).collect();
    v.after = v.after.into_iter().map(phone_words_line).collect();
    for i in &mut v.items {
        match &mut i.kind {
            Kind::Choice { title, desc } | Kind::Check { title, desc, .. } => {
                *title = phone_words(title);
                *desc = phone_words(desc);
            }
            Kind::Button(t) | Kind::Heading(t) => *t = phone_words(t),
        }
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn friendly_errors() {
        assert!(
            friendly_error("adb: error: no devices/emulators found").contains("stopped answering")
        );
        assert!(friendly_error("couldn't download https://x").contains("download"));
        assert!(friendly_error("Failure [INSTALL_FAILED_OLDER_SDK]").contains("too old"));
        assert!(friendly_error("weird").contains("try again"));
        assert!(friendly_error("Home didn't come up: Home opens X, so the apps it switched off were switched back on").contains("Nothing is broken"));
    }

    #[test]
    fn phone_wording_keeps_the_tvs_own_prompt() {
        assert_eq!(
            phone_words(
                "Is this computer online? Tick \"Always allow from this computer\" on your computer."
            ),
            "Is this phone online? Tick \"Always allow from this computer\" on your phone."
        );
    }

    #[test]
    fn titles() {
        assert_eq!(
            friendly_title("Install tvhome-1.9.1.apk"),
            "Install TV Home 1.9.1"
        );
        assert_eq!(lower_first("Restore the TV"), "restore the TV");
    }
}
