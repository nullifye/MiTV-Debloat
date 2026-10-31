//! TV Home TUI: connect to an Android TV over adb, install the launcher from GitHub Releases,
//! disable preinstalled apps, apply tweaks and restore everything, with every command shown before
//! it runs and a live log.

mod adb;
mod app;
mod backup;
mod catalog;
mod device;
mod flows;
mod github;
mod log;
mod plan;
mod power;
mod report;
mod screens;
mod setup;
mod ui;
mod worker;

use anyhow::{Result, bail};
use ratatui::crossterm::event::{
    self, DisableMouseCapture, EnableMouseCapture, Event as TermEvent, KeyEventKind,
};
use ratatui::crossterm::execute;
use std::path::PathBuf;
use std::sync::mpsc;
use std::time::Duration;

const USAGE: &str = "\
tvhome-tui: set up an Android TV with the TV Home launcher.

Usage: tvhome-tui [options]

Options:
  --tv <ip[:port]>     connect to this TV at start (else the last one used)
  --adb <path>         the adb executable (else found on PATH or in the Android SDK)
  --repo <owner/name>  GitHub repository for releases (default: nullifye/MiTV-Debloat)
  --data-dir <path>    where downloads, the log and settings go
  --dry-run            show every plan and log it, but change nothing on the TV
  --ascii              plain symbols (automatic in Windows' classic console; --unicode: never)
  --no-color           no colours (also when NO_COLOR is set)
  -h, --help           this help
  -V, --version        the version

Inside the MiTV-Debloat folder it shares backup/ and .tv with the bash scripts.";

#[derive(Default)]
struct Args {
    tv: Option<String>,
    adb: Option<PathBuf>,
    repo: Option<String>,
    data_dir: Option<PathBuf>,
    dry_run: bool,
    ascii: Option<bool>,
    no_color: bool,
}

fn parse_args() -> Result<Option<Args>> {
    let mut a = Args::default();
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        let mut value = |name: &str| {
            it.next()
                .ok_or_else(|| anyhow::anyhow!("{name} needs a value"))
        };
        match arg.as_str() {
            "--tv" => a.tv = Some(value("--tv")?),
            "--adb" => a.adb = Some(PathBuf::from(value("--adb")?)),
            "--repo" => a.repo = Some(value("--repo")?),
            "--data-dir" => a.data_dir = Some(PathBuf::from(value("--data-dir")?)),
            "--dry-run" => a.dry_run = true,
            "--ascii" => a.ascii = Some(true),
            "--unicode" => a.ascii = Some(false),
            "--no-color" | "--no-colour" => a.no_color = true,
            "-h" | "--help" => {
                println!("{USAGE}");
                return Ok(None);
            }
            "-V" | "--version" => {
                println!("tvhome-tui {}", env!("CARGO_PKG_VERSION"));
                return Ok(None);
            }
            other => bail!("unknown option {other}\n\n{USAGE}"),
        }
    }
    Ok(Some(a))
}

fn main() -> Result<()> {
    let Some(args) = parse_args()? else {
        return Ok(());
    };
    let store = backup::Store::detect(&std::env::current_dir()?, args.data_dir);
    let repo = args
        .repo
        .unwrap_or_else(|| github::DEFAULT_REPO.to_string());
    let app_repo = repo.clone();
    // No adb yet is fine: the app offers to download it into its own folder.
    let found = adb::find_adb(args.adb.as_deref(), &setup::local_adb(&store.app_dir));
    let adb = adb::Adb {
        exe: found.clone().unwrap_or_default(),
        serial: None,
    };

    let (job_tx, job_rx) = mpsc::channel();
    let (ev_tx, ev_rx) = mpsc::channel();
    worker::spawn(
        adb.clone(),
        store.clone(),
        repo,
        args.dry_run,
        ev_tx,
        job_rx,
    );

    let mut app = app::App::new(
        adb,
        found.is_some(),
        args.adb.clone(),
        store,
        args.dry_run,
        job_tx,
    );
    app.repo = app_repo;
    app.ascii = args.ascii.unwrap_or_else(old_console);
    app.no_color = args.no_color || std::env::var_os("NO_COLOR").is_some_and(|v| !v.is_empty());
    app.log.info(format!(
        "tvhome-tui {} · adb: {} · backups in {}{}",
        env!("CARGO_PKG_VERSION"),
        found
            .as_ref()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|| "not found yet".into()),
        app.store.backup.display(),
        if args.dry_run {
            " · PRACTICE MODE: nothing will be changed"
        } else {
            ""
        }
    ));
    // Reconnect to the TV given on the command line, else the last one used (.tv, shared with
    // the scripts); otherwise look for TVs on the network.
    let start_tv = args
        .tv
        .as_deref()
        .and_then(device::normalize_address)
        .or_else(|| app.store.read_tv());
    app.start(start_tv);

    // ratatui::init also restores the terminal if anything panics. Mouse clicks select choices.
    let mut terminal = ratatui::init();
    let _ = execute!(std::io::stdout(), EnableMouseCapture);
    let result = (|| -> Result<()> {
        while !app.quit {
            terminal.draw(|f| ui::draw(f, &mut app))?;
            if event::poll(Duration::from_millis(100))? {
                match event::read()? {
                    TermEvent::Key(k) if k.kind == KeyEventKind::Press => app.on_key(k),
                    TermEvent::Mouse(m) => app.on_mouse(m),
                    _ => {}
                }
            }
            while let Ok(e) = ev_rx.try_recv() {
                app.on_event(e);
            }
            app.tick();
        }
        Ok(())
    })();
    let _ = execute!(std::io::stdout(), DisableMouseCapture);
    ratatui::restore();
    result
}

/// Windows' classic console (conhost) lacks most symbols; Windows Terminal, VS Code and ConEmu
/// set one of these. `TVHOME_ASCII=1` forces plain symbols anywhere.
fn old_console() -> bool {
    let set = |k: &str| std::env::var_os(k).is_some_and(|v| !v.is_empty());
    if set("TVHOME_ASCII") {
        return std::env::var("TVHOME_ASCII")
            .map(|v| v != "0")
            .unwrap_or(true);
    }
    cfg!(windows)
        && ![
            "WT_SESSION",
            "TERM_PROGRAM",
            "ConEmuANSI",
            "ALACRITTY_WINDOW_ID",
        ]
        .iter()
        .any(|k| set(k))
}
