//! Builders for every action that changes the TV. Each does its read-only checks first (logged),
//! then returns the full plan for the preview. They mirror the bash scripts:
//! install ≈ build-and-install.sh without the build, disable ≈ disable.sh, restore ≈ restore.sh.

use crate::adb::{Adb, Output, Runner};
use crate::backup::{self, Store};
use crate::device::{self, GTV_LAUNCHER, GTV_SETUP, HOME_ACTIVITY, PKG};
use crate::github::{self, Release};
use crate::plan::{self, Action, EngineEvent, Judge, Kind, Plan, Record, Step, Verify};
use anyhow::{Result, bail};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::time::Duration;

/// The launcher's own log of apps disabled from the phone remote (restore.sh reads it too).
pub const REMOTE_LOG: &str =
    "/sdcard/Android/data/my.rafhan.app.tvhome/files/disabled-by-remote.txt";
const TMP_APK: &str = "/data/local/tmp/tvhome.apk";
pub const ADGUARD_DNS: &str = "dns.adguard-dns.com";

pub struct Env<'a> {
    pub adb: &'a Adb,
    pub runner: &'a dyn Runner,
    pub store: &'a Store,
    pub emit: &'a mut dyn FnMut(EngineEvent),
}

impl Env<'_> {
    /// A read-only `adb shell …`, logged.
    pub fn shell(&mut self, args: &[&str]) -> Output {
        let argv = self.adb.shell(args);
        plan::run_logged(self.runner, &argv, Duration::from_secs(30), self.emit)
    }

    fn text(&mut self, args: &[&str]) -> String {
        self.shell(args).text()
    }

    fn sdk(&mut self) -> u32 {
        self.text(&["getprop", "ro.build.version.sdk"])
            .trim()
            .parse()
            .unwrap_or(0)
    }

    fn setting(&mut self, ns: &str, key: &str) -> Option<String> {
        device::setting_value(&self.text(&["settings", "get", ns, key]))
    }

    fn installed(&mut self, flags: &[&str]) -> BTreeSet<String> {
        let mut args = vec!["pm", "list", "packages"];
        args.extend_from_slice(flags);
        device::parse_packages(&self.text(&args))
    }

    fn focused(&mut self) -> Option<String> {
        let w = self.text(&["dumpsys", "window"]);
        device::parse_focused_package(&w)
    }

    fn resolved_home(&mut self) -> Option<String> {
        device::parse_resolved_component(&self.text(&[
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME",
        ]))
    }
}

/// Where the APK comes from.
#[derive(Debug, Clone)]
pub enum ApkSource {
    Release(Release),
    Local(PathBuf),
}

fn grants(sdk: u32) -> Vec<(&'static str, Vec<&'static str>)> {
    let mut g = vec![
        (
            "Storage (Files tab, wallpapers) – read",
            vec![
                "pm",
                "grant",
                PKG,
                "android.permission.READ_EXTERNAL_STORAGE",
            ],
        ),
        (
            "Storage – write",
            vec![
                "pm",
                "grant",
                PKG,
                "android.permission.WRITE_EXTERNAL_STORAGE",
            ],
        ),
    ];
    if sdk >= 30 {
        g.push((
            "All files access (Android 11+)",
            vec!["appops", "set", PKG, "MANAGE_EXTERNAL_STORAGE", "allow"],
        ));
    }
    g.extend([
        (
            "Wi-Fi network name (location)",
            vec![
                "pm",
                "grant",
                PKG,
                "android.permission.ACCESS_FINE_LOCATION",
            ],
        ),
        (
            "Usage access (Most used)",
            vec!["appops", "set", PKG, "GET_USAGE_STATS", "allow"],
        ),
        (
            "Install its own updates from GitHub",
            vec!["appops", "set", PKG, "REQUEST_INSTALL_PACKAGES", "allow"],
        ),
        (
            "Draw over apps (PIN, banners, overlay)",
            vec!["appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow"],
        ),
        (
            "DUMP (Now Playing, bell)",
            vec!["pm", "grant", PKG, "android.permission.DUMP"],
        ),
        (
            "Pick the screen saver from TV Home's Settings",
            vec![
                "pm",
                "grant",
                PKG,
                "android.permission.WRITE_SECURE_SETTINGS",
            ],
        ),
        (
            "Usage stats (bell)",
            vec!["pm", "grant", PKG, "android.permission.PACKAGE_USAGE_STATS"],
        ),
    ]);
    if sdk >= 31 {
        g.push((
            "Bluetooth alerts (Android 12+)",
            vec!["pm", "grant", PKG, "android.permission.BLUETOOTH_CONNECT"],
        ));
    }
    // Bell: apps with an update waiting, from the Play Store's log. Android 13+ asks on screen
    // whenever an app reads the log, so the launcher only uses it below 13.
    if sdk < 33 {
        g.push((
            "Read the Play Store's update list (bell)",
            vec!["pm", "grant", PKG, "android.permission.READ_LOGS"],
        ));
    }
    g
}

fn set_home(adb: &Adb) -> Step {
    Step::adb(
        "Make it the home screen",
        adb.shell(&["cmd", "package", "set-home-activity", HOME_ACTIVITY]),
    )
}

fn disable_step(adb: &Adb, pkg: &str, label: String) -> Step {
    Step::adb(
        label,
        adb.shell(&["pm", "disable-user", "--user", "0", pkg]),
    )
    .judge(Judge::Disabled)
    .then_record(Record::Disabled(pkg.to_string()))
}

/// Install (or update) the launcher, grant its permissions, make it Home; optionally hand Home
/// over from Google TV.
pub fn install(env: &mut Env, src: &ApkSource, handover: bool) -> Result<Plan> {
    let adb = env.adb.clone();
    let (apk, name) = match src {
        ApkSource::Local(p) => {
            if !p.is_file() {
                bail!("{} doesn't exist", p.display());
            }
            (
                p.clone(),
                p.file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_default(),
            )
        }
        ApkSource::Release(r) => {
            let Some(a) = &r.apk else {
                bail!("release {} has no APK", r.tag)
            };
            (env.store.downloads().join(&a.name), a.name.clone())
        }
    };
    let sdk = env.sdk();
    if sdk == 0 {
        bail!("couldn't read the TV's Android version: is it connected?");
    }
    if sdk < 26 {
        bail!("the TV runs Android SDK {sdk}; the launcher needs Android 8.0 (SDK 26) or newer");
    }
    let mut p = Plan::new(format!("Install {name}"));
    p.notes.push(format!(
        "Android SDK {sdk}. Installs, grants the permissions, sets Home."
    ));
    if let Some(app) = env.focused()
        && app != PKG
        && !app.contains("launcher")
        && app != "com.android.systemui"
    {
        p.warnings.push(format!(
            "{} is on the TV screen: someone may be watching. The last step presses Home on the TV.",
            device::friendly_app_name(&app)
        ));
    }

    if let ApkSource::Release(r) = src
        && let Some(a) = &r.apk
    {
        p.push(Step {
            label: format!("Download {}", a.name),
            action: Action::Download {
                url: a.url.clone(),
                dest: apk.clone(),
                size: a.size,
            },
            kind: Kind::Required,
            judge: Judge::Exit0,
            on_success: None,
            timeout: Duration::from_secs(600),
        });
    }

    // The TV's own launcher, recorded once for Restore (build-and-install.sh does the same).
    let mut stock = env.store.stock_home();
    if stock.is_none()
        && let Some(c) = env.resolved_home()
        && device::is_recordable_stock_home(&c)
    {
        p.push(Step::record(
            "Remember the TV's own launcher (for Restore)",
            Record::StockHome(c.clone()),
        ));
        stock = Some(c);
    }

    let apk_s = apk.display().to_string();
    p.push(
        Step::adb(
            "Copy the APK to the TV",
            adb.cmd(&["push", &apk_s, TMP_APK]),
        )
        .timeout(300),
    );
    p.push(
        Step::adb("Install it", adb.shell(&["pm", "install", "-r", TMP_APK]))
            .judge(Judge::Install)
            .timeout(300),
    );
    p.push(Step::adb("Delete the copy", adb.shell(&["rm", TMP_APK])).optional());
    for (label, args) in grants(sdk) {
        p.push(Step::adb(label, adb.shell(&args)).optional());
    }

    // Accessibility (phone remote Back/Home): next to what's already on, never replacing it.
    if !env.store.exists(backup::A11Y_BEFORE) {
        let before = env
            .setting("secure", "accessibility_enabled")
            .unwrap_or_else(|| "null".into());
        p.push(Step::record(
            "Remember accessibility_enabled (for Restore)",
            Record::A11yBefore(before),
        ));
    }
    let current = env.setting("secure", "enabled_accessibility_services");
    if let Some(v) = device::a11y_with_ours(current.as_deref()) {
        p.push(
            Step::adb(
                "Phone remote Back/Home (accessibility, others kept)",
                adb.shell(&[
                    "settings",
                    "put",
                    "secure",
                    "enabled_accessibility_services",
                    &v,
                ]),
            )
            .optional(),
        );
    } else if let (Some(all), Some(others)) = (
        current.as_deref(),
        device::a11y_without_ours(current.as_deref()),
    ) {
        // Already on (an update): Android doesn't always reconnect a service whose app was just
        // replaced, which leaves the phone remote's Back/Home and the Input button dead. Off and
        // on again reconnects it; the others stay on throughout.
        let off = if others.is_empty() {
            adb.shell(&[
                "settings",
                "delete",
                "secure",
                "enabled_accessibility_services",
            ])
        } else {
            adb.shell(&[
                "settings",
                "put",
                "secure",
                "enabled_accessibility_services",
                &others,
            ])
        };
        p.push(Step::adb("Reconnect the accessibility service (off)", off).optional());
        p.push(
            Step::adb(
                "Reconnect the accessibility service (on)",
                adb.shell(&[
                    "settings",
                    "put",
                    "secure",
                    "enabled_accessibility_services",
                    all,
                ]),
            )
            .optional(),
        );
    }
    p.push(
        Step::adb(
            "Accessibility on",
            adb.shell(&["settings", "put", "secure", "accessibility_enabled", "1"]),
        )
        .optional(),
    );
    p.push(set_home(&adb));

    if handover {
        handover_steps(env, &mut p, stock.as_deref())?;
    }

    // In plain words for the confirmation page.
    if let ApkSource::Release(r) = src
        && let Some(a) = &r.apk
    {
        p.say(format!(
            "Download TV Home {} ({}) from GitHub",
            r.version,
            github::human_size(a.size)
        ));
    }
    p.say("Install it on the TV (an existing copy is updated; its settings are kept)");
    p.say("Give it the permissions it needs (storage, usage, show over apps…)");
    p.say("Let the phone remote press Back and Home");
    p.say("Make it the TV's home screen");
    if !disabled_in(&p).is_empty() {
        p.say("Switch off Google TV's own home screen (Undo all changes turns it back on)");
    }
    p.say("Press Home and check the new home screen came up (if not, put everything back)");
    p.done = "TV Home is installed. Press the Home button on your TV remote to see it.".into();
    finish_with_home_check(&mut p, &adb, stock.as_deref());
    attach_retry(&mut p, &adb, &name);
    Ok(p)
}

/// Packages this plan turns off (and logs for Restore).
fn disabled_in(p: &Plan) -> Vec<String> {
    p.steps
        .iter()
        .filter_map(|s| match &s.on_success {
            Some(Record::Disabled(pkg)) => Some(pkg.clone()),
            _ => None,
        })
        .collect()
}

/// The health check, as a step. `rollback` turns these packages back on (and, with `home`, makes
/// that the home screen again) if the check fails.
fn verify_step(adb: &Adb, expect_launcher: bool, rollback: &[String], home: Option<&str>) -> Step {
    let mut cmds: Vec<Vec<String>> = rollback
        .iter()
        .map(|pkg| adb.shell(&["pm", "enable", "--user", "0", pkg]))
        .collect();
    if let Some(h) = home
        && !rollback.is_empty()
    {
        cmds.push(adb.shell(&["cmd", "package", "set-home-activity", h]));
    }
    let note = if home.is_some() && !rollback.is_empty() {
        "the apps it switched off were switched back on and the TV's own home screen made the default again"
    } else {
        "the apps it switched off were switched back on"
    };
    Step {
        label: if expect_launcher {
            "Check Home opens TV Home"
        } else {
            "Check Home still works"
        }
        .into(),
        action: Action::VerifyHome(Box::new(Verify {
            window: adb.shell(&["dumpsys", "window"]),
            activities: adb.shell(&["dumpsys", "activity", "activities"]),
            resolve: adb.shell(&[
                "cmd",
                "package",
                "resolve-activity",
                "--brief",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.HOME",
            ]),
            expect_launcher,
            attempts: if expect_launcher { 6 } else { 1 },
            delay_ms: if expect_launcher { 2000 } else { 500 },
            rollback: cmds,
            rollback_note: note.into(),
        })),
        // Only worth stopping on when there's something to put back.
        kind: if rollback.is_empty() {
            Kind::Optional
        } else {
            Kind::Required
        },
        judge: Judge::Exit0,
        on_success: None,
        timeout: Duration::from_secs(30),
    }
}

/// Press Home, then check TV Home came up; if it didn't, turn back on whatever this plan turned
/// off and make the TV's own launcher Home again (Google TV's recovery screen otherwise).
fn finish_with_home_check(p: &mut Plan, adb: &Adb, stock: Option<&str>) {
    p.push(
        Step::adb(
            "Press Home",
            adb.shell(&["input", "keyevent", "KEYCODE_HOME"]),
        )
        .optional(),
    );
    let rollback = disabled_in(p);
    p.push(verify_step(adb, true, &rollback, stock));
}

/// After a signature mismatch: uninstall (settings lost), then the same steps again.
fn attach_retry(p: &mut Plan, adb: &Adb, name: &str) {
    let mut retry = Plan::new(format!(
        "Uninstall the old copy, then {}",
        lower_first(&p.title)
    ));
    retry.summary =
        std::iter::once("Remove the old copy of TV Home (its settings are reset)".to_string())
            .chain(
                p.summary
                    .iter()
                    .filter(|l| !l.starts_with("Download"))
                    .cloned(),
            )
            .collect();
    retry.done = p.done.clone();
    retry.warnings.push(format!(
        "The TV Home on the TV was signed with a different key than {name} (a GitHub Release vs a build from a \
         computer). Uninstalling it resets the launcher's settings: dock, wallpaper, prayer zone, paired phones."
    ));
    retry.push(
        Step::adb("Uninstall the old copy", adb.cmd(&["uninstall", PKG])).judge(Judge::Success),
    );
    retry.steps.extend(
        p.steps
            .iter()
            .filter(|s| !matches!(s.action, Action::Download { .. }))
            .cloned(),
    );
    p.retry_after_uninstall = Some(Box::new(retry));
}

fn lower_first(t: &str) -> String {
    let mut c = t.chars();
    match c.next() {
        Some(f) => f.to_lowercase().chain(c).collect(),
        None => String::new(),
    }
}

/// "Set up my TV": install TV Home, turn off the suggested apps, block ads, in one plan with one
/// health check at the end (which puts everything back if Home doesn't work).
pub fn setup_all(
    env: &mut Env,
    release: &Release,
    handover: bool,
    block_ads: bool,
) -> Result<Plan> {
    let adb = env.adb.clone();
    let mut p = install(env, &ApkSource::Release(release.clone()), handover)?;
    p.title = format!("Set up the TV with TV Home {}", release.version);
    // Take the install's ending off; it goes back on after everything else.
    p.steps
        .retain(|s| !matches!(s.action, Action::VerifyHome(_)) && s.label != "Press Home");
    let stock = env.store.stock_home();

    // The suggested apps, worked out the same way as on the Clean up screen.
    let cat = crate::catalog::Catalog::builtin();
    let enabled = env.installed(&["-e"]);
    let homes = device::parse_components(&env.text(&[
        "cmd",
        "package",
        "query-activities",
        "--brief",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.HOME",
    ]));
    let ime =
        device::ime_package(&env.text(&["settings", "get", "secure", "default_input_method"]));
    let guard = crate::catalog::Guard {
        ime,
        home_apps: crate::catalog::home_packages(&homes),
        stock_home: stock.as_deref().map(|s| device::package_of(s).to_string()),
    };
    let rec = crate::catalog::recommended(&cat, &guard, &enabled);
    let names: Vec<String> = rec
        .iter()
        .map(|p| {
            cat.get(p)
                .map(|e| e.label.clone())
                .unwrap_or_else(|| p.clone())
        })
        .collect();
    for pkg in &rec {
        p.push(disable_step(&adb, pkg, format!("Disable {pkg}")).optional());
    }
    // Kept apps that wake up by themselves while you watch (PatchWall): stopped in the background.
    let limit = crate::catalog::background_limits(&cat, &enabled);
    for pkg in &limit {
        p.push(restrict_step(&adb, pkg));
    }
    if block_ads {
        let dns = private_dns(env);
        p.steps.extend(dns.steps);
    }

    if !limit.is_empty() {
        let names: Vec<String> = limit
            .iter()
            .map(|p| {
                cat.get(p)
                    .map(|e| e.label.clone())
                    .unwrap_or_else(|| p.clone())
            })
            .collect();
        p.say(format!(
            "Stop {} running in the background, so it can't wake up while you watch (it still opens)",
            names.join(", ")
        ));
    }
    p.summary.retain(|l| !l.starts_with("Press Home"));
    if !rec.is_empty() {
        let list = if names.len() > 5 {
            format!("{} and {} more", names[..5].join(", "), names.len() - 5)
        } else {
            names.join(", ")
        };
        p.say(format!(
            "Turn off {} suggested app{}: {list}",
            rec.len(),
            if rec.len() == 1 { "" } else { "s" }
        ));
    }
    if block_ads {
        p.say("Block ads on the whole TV (AdGuard's free DNS)");
    }
    p.say("Press Home and check everything works (if not, put everything back)");
    p.done = "Your TV is set up. Press the Home button on your TV remote.\nTo undo any of it later: \"Undo all changes\".".into();
    finish_with_home_check(&mut p, &adb, stock.as_deref());
    let name = release
        .apk
        .as_ref()
        .map(|a| a.name.clone())
        .unwrap_or_default();
    attach_retry(&mut p, &adb, &name);
    Ok(p)
}

/// Turns USB / network debugging off on the TV. Both settings go in one shell command: the
/// connection drops as soon as the first one lands, so a second command would never arrive.
pub fn debugging_off(env: &mut Env) -> Plan {
    let adb = env.adb.clone();
    let sdk = env.sdk();
    let mut p = Plan::new("Turn off USB debugging on the TV");
    let cmd = if sdk >= 30 {
        "settings put global adb_wifi_enabled 0; settings put global adb_enabled 0"
    } else {
        "settings put global adb_enabled 0"
    };
    p.push(
        Step::adb("Turn off USB debugging", adb.shell(&[cmd]))
            .judge(Judge::Any)
            .timeout(10),
    );
    p.say("Turn off USB debugging on the TV, so no one else on your Wi-Fi can control it");
    p.say("Then this app closes. To use it again, turn USB debugging back on in Developer options");
    p.done = "USB debugging is off. The phone remote and everything else keep working.".into();
    p
}

/// Hands Home over to TV Home. Android sends Home to the Home app with the highest priority, and
/// only between equals to the one chosen with set-home-activity. An installed launcher gets 0;
/// Google TV's launcher has 2 and its setup app (its RecoveryActivity) 1. So every enabled Home
/// app above 0 is switched off, the setup app first, plus the TV's recorded launcher. On Sony
/// (Realtek) TVs this is also what stops `com.realtek.advancesetting` from starting Google TV's
/// launcher by name on every Home press: with the launcher off, that start fails.
fn handover_steps(env: &mut Env, p: &mut Plan, stock: Option<&str>) -> Result<()> {
    let adb = env.adb.clone();
    let enabled = env.installed(&["-e"]);
    let homes = device::parse_home_priorities(&env.text(&[
        "cmd",
        "package",
        "query-activities",
        "--brief",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.HOME",
    ]));
    let mut candidates: Vec<&str> = homes
        .iter()
        .filter(|(_, prio)| *prio > 0)
        .map(|(c, _)| device::package_of(c))
        .collect();
    candidates.extend(stock.map(device::package_of));
    if candidates.is_empty() {
        candidates.push(GTV_LAUNCHER);
    }
    let mut off: Vec<String> = Vec::new();
    for pkg in candidates {
        if pkg != PKG && enabled.contains(pkg) && !off.iter().any(|o| o == pkg) {
            off.push(pkg.to_string());
        }
    }
    // The setup app first: with the launcher gone first, Home would land on setup.
    off.sort_by_key(|p| p != GTV_SETUP);

    if off.iter().any(|p| p == GTV_SETUP || p == GTV_LAUNCHER) {
        p.notes.push(
            "While Google TV's home screen is off, casting from a phone (Chromecast built-in) doesn't \
             work. Undo all changes turns it and Google TV setup back on (do that before any factory reset)."
                .into(),
        );
    }

    let cat = crate::catalog::Catalog::builtin();
    let mut changed = false;
    for pkg in &off {
        if cat.get(pkg).map(|e| e.advice) == Some(crate::catalog::Advice::Never) {
            p.warnings.push(format!(
                "{} answers Home ahead of TV Home, but the TV needs it, so it's left on: Home may keep opening it.",
                device::friendly_app_name(pkg)
            ));
            continue;
        }
        let label = if pkg == GTV_SETUP {
            "Disable Google TV setup (it also answers Home)".to_string()
        } else if Some(pkg.as_str()) == stock.map(device::package_of) || pkg == GTV_LAUNCHER {
            format!("Disable the TV's own launcher ({pkg})")
        } else {
            format!("Disable {pkg} (it answers Home ahead of TV Home)")
        };
        p.push(disable_step(&adb, pkg, label));
        changed = true;
    }
    if changed {
        p.push(set_home(&adb).optional());
    } else if stock.is_none() {
        p.warnings
            .push("The TV's own launcher isn't known, so it's left on.".into());
    }
    Ok(())
}

/// Uninstall the launcher (only this project's own package is ever uninstalled).
pub fn uninstall(env: &mut Env) -> Plan {
    let mut p = Plan::new("Uninstall the launcher");
    p.warnings
        .push("Its settings are lost (dock, wallpaper, prayer zone, paired phones).".into());
    let stock = env.store.stock_home();
    if let Some(s) = &stock {
        p.push(
            Step::adb(
                "Home back to the TV's own launcher",
                env.adb.shell(&["cmd", "package", "set-home-activity", s]),
            )
            .optional(),
        );
    }
    p.push(Step::adb("Uninstall", env.adb.cmd(&["uninstall", PKG])).judge(Judge::Success));
    if stock.is_some() {
        p.say("Make the TV's own home screen the default again");
    }
    p.say("Remove TV Home from the TV (its settings are lost)");
    p.done = "TV Home is removed. Press Home on your TV remote.".into();
    p
}

/// Disable or enable packages, one `pm` call each. Disabled ones are logged for Restore; ones
/// already in the wanted state are skipped (and not logged, like disable.sh).
pub fn set_enabled(env: &mut Env, pkgs: &[String], enable: bool) -> Result<Plan> {
    let adb = env.adb.clone();
    let installed = env.installed(&[]);
    let disabled = env.installed(&["-d"]);
    let verb = if enable { "Enable" } else { "Disable" };
    let mut p = Plan::new(format!(
        "{verb} {} app{}",
        pkgs.len(),
        if pkgs.len() == 1 { "" } else { "s" }
    ));
    if !enable {
        p.notes.push(format!(
            "Only disabled (pm disable-user --user 0), never uninstalled. Logged in backup/{} so Restore turns them back on.",
            backup::DISABLED
        ));
    }
    for pkg in pkgs {
        if !installed.contains(pkg) {
            p.notes.push(format!("{pkg}: not installed, skipped."));
        } else if disabled.contains(pkg) != enable {
            p.notes.push(format!(
                "{pkg}: already {}, skipped.",
                if enable { "enabled" } else { "disabled" }
            ));
        } else if enable {
            p.push(
                Step::adb(
                    format!("Enable {pkg}"),
                    adb.shell(&["pm", "enable", "--user", "0", pkg]),
                )
                .judge(Judge::Enabled)
                .optional(),
            );
        } else {
            p.push(disable_step(&adb, pkg, format!("Disable {pkg}")).optional());
        }
    }
    if !enable && !p.steps.is_empty() {
        let off = disabled_in(&p);
        p.push(verify_step(&adb, false, &off, None));
    }
    let cat = crate::catalog::Catalog::builtin();
    let names: Vec<String> = p
        .steps
        .iter()
        .filter(|s| matches!(s.action, Action::Adb(_)))
        .filter_map(|s| s.label.split_once(' ').map(|(_, pkg)| pkg.to_string()))
        .map(|pkg| cat.get(&pkg).map(|e| e.label.clone()).unwrap_or(pkg))
        .collect();
    if !names.is_empty() {
        let list = if names.len() > 6 {
            format!("{} and {} more", names[..6].join(", "), names.len() - 6)
        } else {
            names.join(", ")
        };
        if enable {
            p.say(format!("Turn these back on: {list}"));
            p.done = "Done: those apps are back on.".into();
        } else {
            p.say(format!("Turn off: {list}"));
            p.say("Nothing is deleted. \"Undo all changes\" turns them back on any time.");
            p.done = "Done: those apps are off. Press Home on your TV remote.".into();
        }
    }
    Ok(p)
}

/// Undo everything recorded, in restore.sh's order.
pub fn restore(env: &mut Env, remove_launcher: bool) -> Plan {
    let adb = env.adb.clone();
    let store = env.store.clone();
    let mut p = Plan::new(if remove_launcher {
        "Restore the TV and remove the launcher"
    } else {
        "Restore the TV"
    });
    p.notes.push(format!("From {}.", store.backup.display()));

    for pkg in store.lines(backup::DISABLED) {
        p.push(
            Step::adb(
                format!("Enable {pkg}"),
                adb.shell(&["pm", "enable", "--user", "0", &pkg]),
            )
            .judge(Judge::Enabled)
            .optional(),
        );
    }
    // Missing when nothing was disabled from the phone: keep that out of the error log.
    let remote = env.text(&[&format!("cat {REMOTE_LOG} 2>/dev/null")]);
    let remote: Vec<String> = remote
        .lines()
        .map(str::trim)
        .filter(|l| {
            !l.is_empty()
                && !l.contains("No such file")
                && l.chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_')
        })
        .map(str::to_string)
        .collect();
    for pkg in &remote {
        p.push(
            Step::adb(
                format!("Enable {pkg} (disabled from the phone)"),
                adb.shell(&["pm", "enable", "--user", "0", pkg]),
            )
            .judge(Judge::Enabled)
            .optional(),
        );
    }
    if !remote.is_empty() {
        p.push(
            Step::adb(
                "Clear the phone remote's log",
                adb.shell(&["rm", "-f", REMOTE_LOG]),
            )
            .optional(),
        );
    }
    for pkg in store.lines(backup::BG_RESTRICTED) {
        for op in ["RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND"] {
            p.push(
                Step::adb(
                    format!("Let {pkg} run in the background"),
                    adb.shell(&["cmd", "appops", "set", &pkg, op, "allow"]),
                )
                .optional(),
            );
        }
    }
    if let Some(v) = store
        .wifi_scan_before()
        .filter(|v| !v.is_empty() && v != "null")
    {
        p.push(
            Step::adb(
                "Wi-Fi scanning as before",
                adb.shell(&["settings", "put", "global", "wifi_scan_always_enabled", &v]),
            )
            .optional(),
        );
    }

    // Accessibility: drop ours, keep others; put the master switch back only if nothing else
    // needs it (restore.sh's rule).
    let current = env.setting("secure", "enabled_accessibility_services");
    let mut others_left = current.is_some();
    if let Some(rest) = device::a11y_without_ours(current.as_deref()) {
        others_left = !rest.is_empty();
        if rest.is_empty() {
            p.push(
                Step::adb(
                    "Remove the phone remote's accessibility service",
                    adb.shell(&[
                        "settings",
                        "delete",
                        "secure",
                        "enabled_accessibility_services",
                    ]),
                )
                .optional(),
            );
        } else {
            p.push(
                Step::adb(
                    "Remove the phone remote's accessibility service (others kept)",
                    adb.shell(&[
                        "settings",
                        "put",
                        "secure",
                        "enabled_accessibility_services",
                        &rest,
                    ]),
                )
                .optional(),
            );
        }
    }
    if let Some(before) = store.a11y_before()
        && before != "1"
        && !others_left
    {
        let s = if before == "null" {
            Step::adb(
                "accessibility_enabled as before",
                adb.shell(&["settings", "delete", "secure", "accessibility_enabled"]),
            )
        } else {
            Step::adb(
                "accessibility_enabled as before",
                adb.shell(&[
                    "settings",
                    "put",
                    "secure",
                    "accessibility_enabled",
                    &before,
                ]),
            )
        };
        p.push(s.optional());
    }

    if let Some((mode, spec)) = store.private_dns_before() {
        let mode = if mode.is_empty() || mode == "null" {
            "opportunistic".to_string()
        } else {
            mode
        };
        p.push(
            Step::adb(
                "Private DNS mode as before",
                adb.shell(&["settings", "put", "global", "private_dns_mode", &mode]),
            )
            .optional(),
        );
        if spec.is_empty() || spec == "null" {
            p.push(
                Step::adb(
                    "Private DNS host as before",
                    adb.shell(&["settings", "delete", "global", "private_dns_specifier"]),
                )
                .optional(),
            );
        } else {
            p.push(
                Step::adb(
                    "Private DNS host as before",
                    adb.shell(&["settings", "put", "global", "private_dns_specifier", &spec]),
                )
                .optional(),
            );
        }
    }

    match store.stock_home() {
        Some(s) => p.push(
            Step::adb(
                format!("Home back to {s}"),
                adb.shell(&["cmd", "package", "set-home-activity", &s]),
            )
            .optional(),
        ),
        None => p.warnings.push(
            "The TV's own launcher wasn't recorded: press Home on the TV and pick it.".into(),
        ),
    }
    if remove_launcher {
        p.warnings
            .push("The launcher is uninstalled: its settings are lost.".into());
        p.push(
            Step::adb("Uninstall the launcher", adb.cmd(&["uninstall", PKG]))
                .judge(Judge::Success)
                .optional(),
        );
    }
    if p.is_empty() {
        p.notes
            .push("Nothing was recorded, so there's nothing to undo.".into());
    } else {
        let count = |pre: &str| p.steps.iter().filter(|s| s.label.starts_with(pre)).count();
        let apps = count("Enable ");
        let bg = count("Let ") / 2;
        if apps > 0 {
            p.summary.push(format!("Turn {apps} app(s) back on"));
        }
        if bg > 0 {
            p.summary
                .push(format!("Let {bg} app(s) run in the background again"));
        }
        if p.steps
            .iter()
            .any(|s| s.label.starts_with("Wi-Fi scanning") || s.label.starts_with("Private DNS"))
        {
            p.summary
                .push("Put Wi-Fi scanning and the DNS setting back as they were".into());
        }
        p.summary
            .push("Remove the phone remote's Back/Home helper".into());
        if p.steps.iter().any(|s| s.label.starts_with("Home back to")) {
            p.summary
                .push("Make the TV's own home screen the default again".into());
        }
        if remove_launcher {
            p.summary
                .push("Remove TV Home from the TV (its settings are lost)".into());
        }
        p.done = "Your TV is back the way it was. Press Home on your TV remote.".into();
    }
    p
}

/// Wi-Fi scanning off (saves a little CPU and radio time). The old value is recorded first.
pub fn wifi_scan_off(env: &mut Env) -> Plan {
    let before = env
        .setting("global", "wifi_scan_always_enabled")
        .unwrap_or_else(|| "null".into());
    let mut p = Plan::new("Turn Wi-Fi scanning off");
    p.notes
        .push(format!("Now: {before}. Restore puts it back."));
    p.push(Step::record(
        "Remember the current value",
        Record::WifiScanBefore(before),
    ));
    p.push(Step::adb(
        "Wi-Fi scanning off",
        env.adb
            .shell(&["settings", "put", "global", "wifi_scan_always_enabled", "0"]),
    ));
    p.say("Stop the TV scanning for Wi-Fi networks in the background");
    p.say("Remember the old setting, so \"Undo all changes\" can put it back");
    p.done = "Done: background Wi-Fi scanning is off.".into();
    p
}

/// AdGuard's ad-blocking Private DNS (DNS over TLS). The old values are recorded first.
pub fn private_dns(env: &mut Env) -> Plan {
    let mode = env
        .setting("global", "private_dns_mode")
        .unwrap_or_else(|| "null".into());
    let spec = env
        .setting("global", "private_dns_specifier")
        .unwrap_or_else(|| "null".into());
    let mut p = Plan::new("Ad-blocking Private DNS (AdGuard)");
    p.notes
        .push(format!("Now: {mode} / {spec}. Restore puts it back."));
    p.push(Step::record(
        "Remember the current values",
        Record::PrivateDnsBefore(mode, spec),
    ));
    p.push(Step::adb(
        "Private DNS: hostname mode",
        env.adb
            .shell(&["settings", "put", "global", "private_dns_mode", "hostname"]),
    ));
    p.push(Step::adb(
        format!("Private DNS: {ADGUARD_DNS}"),
        env.adb.shell(&[
            "settings",
            "put",
            "global",
            "private_dns_specifier",
            ADGUARD_DNS,
        ]),
    ));
    p.say("Block ads and trackers in every app, using AdGuard's free DNS");
    p.say("Remember the old setting, so \"Undo all changes\" can put it back");
    p.done =
        "Done: ads are blocked for the whole TV. Some apps may need a restart to notice.".into();
    p
}

/// Stop one app running in the background, recorded so "Undo all changes" lifts it.
fn restrict_step(adb: &Adb, pkg: &str) -> Step {
    Step::adb(
        format!("Restrict {pkg}"),
        adb.shell(&[
            "cmd",
            "appops",
            "set",
            pkg,
            "RUN_ANY_IN_BACKGROUND",
            "ignore",
        ]),
    )
    .optional()
    .then_record(Record::BgRestricted(pkg.to_string()))
}

/// Stop the apps the notes mark `restrict` (PatchWall) running in the background, if this TV
/// has them on.
pub fn restrict_recommended(env: &mut Env) -> Plan {
    let cat = crate::catalog::Catalog::builtin();
    let enabled = env.installed(&["-e"]);
    let pkgs = crate::catalog::background_limits(&cat, &enabled);
    if pkgs.is_empty() {
        let mut p = Plan::new("Stop apps waking up by themselves");
        p.notes
            .push("This TV has none of the apps that need it.".into());
        p.done = "Nothing to do on this TV.".into();
        return p;
    }
    restrict_background(env, &pkgs)
}

/// Stop apps running in the background (they still work when opened).
pub fn restrict_background(env: &mut Env, pkgs: &[String]) -> Plan {
    let mut p = Plan::new(format!(
        "Restrict {} app{} in the background",
        pkgs.len(),
        if pkgs.len() == 1 { "" } else { "s" }
    ));
    p.notes
        .push("They still open and work; they just can't run when they're not on screen.".into());
    let adb = env.adb.clone();
    for pkg in pkgs {
        p.push(restrict_step(&adb, pkg));
    }
    p.say(format!(
        "Stop {} app(s) running when they're not on screen (they still open and work)",
        pkgs.len()
    ));
    p.done = "Done.".into();
    p
}

pub fn reboot(env: &mut Env) -> Plan {
    let mut p = Plan::new("Restart the TV");
    p.warnings
        .push("Whatever is playing stops; the TV is back in about a minute.".into());
    p.push(Step::adb("Restart", env.adb.cmd(&["reboot"])).optional());
    p.say("Restart the TV now");
    p.done =
        "The TV is restarting. Wait about a minute, then choose \"Change TV\" to connect again."
            .into();
    p
}

/// The release to offer: the newest with an APK.
pub fn latest_with_apk(releases: &[Release]) -> Option<&Release> {
    releases
        .iter()
        .filter(|r| r.apk.is_some() && !r.prerelease)
        .max_by(|a, b| github::compare_versions(&a.version, &b.version))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::adb::FakeRunner;
    use crate::device::A11Y_SERVICE;
    use crate::plan::{Outcome, run};

    fn adb() -> Adb {
        Adb {
            exe: PathBuf::from("adb"),
            serial: Some("tv:5555".into()),
        }
    }

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("tvhome-flows-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// `query-activities` on a Sony Google TV with TV Home installed.
    const SONY_HOMES: &str = "5 activities found:\n  Activity #0:\n    priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    com.google.android.apps.tv.launcherx/.home.HomeActivity\n  Activity #1:\n    priority=1 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n    com.google.android.tungsten.setupwraith/.RecoveryActivity\n  Activity #2:\n    priority=0 preferredOrder=0\n    my.rafhan.app.tvhome/.MainActivity\n  Activity #3:\n    priority=-1000 preferredOrder=0\n    com.android.tv.settings/.system.FallbackHome\n";

    fn lines(p: &Plan) -> Vec<String> {
        p.steps.iter().map(|s| s.command_line()).collect()
    }

    fn local_apk(d: &std::path::Path) -> ApkSource {
        let f = d.join("tvhome-1.9.1.apk");
        std::fs::write(&f, b"apk").unwrap();
        ApkSource::Local(f)
    }

    fn mi_tv() -> FakeRunner {
        FakeRunner::new()
            .on("getprop ro.build.version.sdk", "29\n")
            .on(
                "dumpsys window",
                "  mFocusedApp=ActivityRecord{1 u0 my.rafhan.app.tvhome/.MainActivity t1}\n",
            )
            .on(
                "resolve-activity",
                "priority=0\ncom.google.android.tvlauncher/.MainActivity\n",
            )
            .on("accessibility_enabled", "1\n")
            .on("enabled_accessibility_services", "flar2.homebutton/a.i\n")
            .on("pm install", "Success\n")
    }

    #[test]
    fn an_update_reconnects_the_accessibility_service() {
        let d = tmp("install-update");
        let store = Store::at(&d);
        let both = format!("flar2.homebutton/a.i:{A11Y_SERVICE}");
        // mi_tv() but with ours already on next to Button Mapper.
        let r = FakeRunner::new()
            .on("getprop ro.build.version.sdk", "29\n")
            .on(
                "dumpsys window",
                "  mFocusedApp=ActivityRecord{1 u0 my.rafhan.app.tvhome/.MainActivity t1}\n",
            )
            .on(
                "resolve-activity",
                "priority=0\ncom.google.android.tvlauncher/.MainActivity\n",
            )
            .on("accessibility_enabled", "1\n")
            .on("enabled_accessibility_services", &format!("{both}\n"))
            .on("pm install", "Success\n");
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = install(&mut env, &local_apk(&d), false).unwrap();
        let l = lines(&p);
        let off = l
            .iter()
            .position(|x| {
                x.ends_with(
                    "settings put secure enabled_accessibility_services flar2.homebutton/a.i",
                )
            })
            .expect("ours taken off, Button Mapper kept");
        let on = l
            .iter()
            .position(|x| {
                x.ends_with(&format!(
                    "settings put secure enabled_accessibility_services {both}"
                ))
            })
            .expect("and put back");
        assert!(off < on);
    }

    #[test]
    fn install_mirrors_build_and_install() {
        let d = tmp("install");
        let store = Store::at(&d);
        let r = mi_tv();
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = install(&mut env, &local_apk(&d), false).unwrap();
        let l = lines(&p).join("\n");
        assert!(
            l.contains("write backup/stock-home.txt: com.google.android.tvlauncher/.MainActivity")
        );
        assert!(l.contains("adb -s tv:5555 shell pm install -r /data/local/tmp/tvhome.apk"));
        assert!(
            !l.contains("MANAGE_EXTERNAL_STORAGE"),
            "SDK 29 has no All files access"
        );
        assert!(
            !l.contains("BLUETOOTH_CONNECT"),
            "SDK 29 has no runtime Bluetooth permission"
        );
        assert!(l.contains(&format!(
            "enabled_accessibility_services flar2.homebutton/a.i:{A11Y_SERVICE}"
        )));
        assert!(l.contains("set-home-activity my.rafhan.app.tvhome/.MainActivity"));
        assert!(l.contains("appops set my.rafhan.app.tvhome REQUEST_INSTALL_PACKAGES allow"));
        assert!(
            l.contains("pm grant my.rafhan.app.tvhome android.permission.READ_LOGS"),
            "SDK 29: the bell reads Play's log"
        );
        assert!(!l.contains("disable-user"), "no handover unless asked");
        assert!(p.warnings.is_empty());
        // Runs to the end, records the stock launcher and accessibility state.
        let mut e = |_e: EngineEvent| {};
        assert_eq!(
            run(&p, &r, &store, false, &mut e),
            Outcome::Done { noted: 0 }
        );
        assert_eq!(
            store.stock_home().as_deref(),
            Some("com.google.android.tvlauncher/.MainActivity")
        );
        assert_eq!(store.a11y_before().as_deref(), Some("1"));
    }

    #[test]
    fn android_8_and_9_boxes_install_older_ones_are_refused() {
        let d = tmp("minsdk");
        let store = Store::at(&d);
        for (sdk, ok) in [("28", true), ("26", true), ("25", false)] {
            let r = FakeRunner::new()
                .on("getprop ro.build.version.sdk", &format!("{sdk}\n"))
                .on("pm install", "Success\n");
            let mut emit = |_e: EngineEvent| {};
            let mut env = Env {
                adb: &adb(),
                runner: &r,
                store: &store,
                emit: &mut emit,
            };
            match install(&mut env, &local_apk(&d), false) {
                Ok(p) => {
                    assert!(ok, "SDK {sdk} should be refused");
                    let l = lines(&p).join("\n");
                    assert!(
                        !l.contains("MANAGE_EXTERNAL_STORAGE") && !l.contains("BLUETOOTH_CONNECT")
                    );
                }
                Err(e) => {
                    assert!(!ok, "SDK {sdk} should install: {e}");
                    assert!(e.to_string().contains("Android 8.0 (SDK 26)"));
                    assert!(
                        crate::screens::friendly_error(&e.to_string())
                            .contains("Android 8.0 or newer")
                    );
                }
            }
        }
    }

    #[test]
    fn install_warns_when_someone_is_watching() {
        let d = tmp("watching");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on("getprop ro.build.version.sdk", "34\n")
            .on(
                "dumpsys window",
                "  mFocusedApp=ActivityRecord{1 u0 com.google.android.youtube.tv/.Main t1}\n",
            );
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = install(&mut env, &local_apk(&d), false).unwrap();
        assert!(p.warnings[0].contains("YouTube"));
        let l = lines(&p).join("\n");
        assert!(l.contains("MANAGE_EXTERNAL_STORAGE") && l.contains("BLUETOOTH_CONNECT"));
    }

    #[test]
    fn signature_mismatch_offers_uninstall_then_install() {
        let d = tmp("incompat");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on(
                "pm install",
                "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: x]",
            )
            .on("getprop ro.build.version.sdk", "29\n");
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = install(&mut env, &local_apk(&d), false).unwrap();
        let mut e = |_e: EngineEvent| {};
        assert_eq!(run(&p, &r, &store, false, &mut e), Outcome::Incompatible);
        let retry = p.retry_after_uninstall.as_ref().unwrap();
        assert_eq!(
            retry.steps[0].command_line(),
            "adb -s tv:5555 uninstall my.rafhan.app.tvhome"
        );
        assert!(retry.warnings[0].contains("settings"));
    }

    #[test]
    fn google_tv_handover_disables_setup_then_launcher() {
        let d = tmp("gtv");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on("getprop ro.build.version.sdk", "34\n")
            .on("query-activities", SONY_HOMES)
            .on("resolve-activity", "com.google.android.apps.tv.launcherx/.home.HomeActivity\n")
            .on("pm list packages -e", "package:com.google.android.tungsten.setupwraith\npackage:com.google.android.apps.tv.launcherx\n");
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = install(&mut env, &local_apk(&d), true).unwrap();
        let l = lines(&p);
        let setup = l
            .iter()
            .position(|x| x.ends_with(&format!("disable-user --user 0 {GTV_SETUP}")))
            .unwrap();
        let stock = l
            .iter()
            .position(|x| x.ends_with(&format!("disable-user --user 0 {GTV_LAUNCHER}")))
            .unwrap();
        assert!(setup < stock, "setup app first, then the stock launcher");
        assert!(p.notes.iter().any(|n| n.contains("casting")));
    }

    #[test]
    fn google_tv_handover_ignores_who_made_the_wifi() {
        // Verified on a Sony Bravia (Android 14): both Google apps off, Wi-Fi stays connected.
        let d = tmp("gtvwifi");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on("getprop ro.build.version.sdk", "34\n")
            .on("resolve-activity", "com.google.android.apps.tv.launcherx/.home.HomeActivity\n")
            .on("query-activities", SONY_HOMES)
            .on("pm list packages -e", "package:com.google.android.tungsten.setupwraith\npackage:com.google.android.apps.tv.launcherx\n");
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let l = lines(&install(&mut env, &local_apk(&d), true).unwrap());
        assert!(
            l.iter()
                .any(|x| x.ends_with(&format!("disable-user --user 0 {GTV_SETUP}")))
        );
        assert!(
            l.iter()
                .any(|x| x.ends_with(&format!("disable-user --user 0 {GTV_LAUNCHER}")))
        );
        assert!(
            !r.calls().iter().any(|c| c.contains("dumpsys wifi")),
            "no Wi-Fi check"
        );
    }

    #[test]
    fn handover_switches_off_every_home_app_above_tv_home() {
        let d = tmp("gtvprio");
        let store = Store::at(&d);
        let homes = format!(
            "{SONY_HOMES}  Activity #4:\n    priority=1 preferredOrder=0\n    com.sony.dtv.someotherhome/.Home\n"
        );
        let r = FakeRunner::new()
            .on("getprop ro.build.version.sdk", "34\n")
            .on("resolve-activity", "com.google.android.apps.tv.launcherx/.home.HomeActivity\n")
            .on("query-activities", &homes)
            .on("pm list packages -e", "package:com.google.android.tungsten.setupwraith\npackage:com.google.android.apps.tv.launcherx\npackage:com.sony.dtv.someotherhome\npackage:com.android.tv.settings\n");
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let l = lines(&install(&mut env, &local_apk(&d), true).unwrap());
        let off: Vec<&str> = l
            .iter()
            .filter_map(|x| x.split("disable-user --user 0 ").nth(1))
            .collect();
        assert_eq!(
            off,
            vec![GTV_SETUP, GTV_LAUNCHER, "com.sony.dtv.someotherhome"]
        );
        assert!(
            !l.iter()
                .any(|x| x.contains("disable-user --user 0 com.android.tv.settings"))
        );
    }

    #[test]
    fn disable_skips_missing_and_already_disabled() {
        let d = tmp("dis");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on("pm list packages -d", "package:com.old\n")
            .on("pm list packages", "package:com.a\npackage:com.old\n");
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = set_enabled(
            &mut env,
            &["com.a".into(), "com.old".into(), "com.gone".into()],
            false,
        )
        .unwrap();
        let l = lines(&p);
        assert_eq!(l[0], "adb -s tv:5555 shell pm disable-user --user 0 com.a");
        // Then the health check, which would turn com.a back on if Home broke.
        assert_eq!(l.len(), 2);
        assert!(
            l[1].contains("Home still has somewhere to go")
                && l[1].contains("put things back (1 command")
        );
        assert!(
            p.notes
                .iter()
                .any(|n| n.contains("com.old: already disabled"))
        );
        assert!(
            p.notes
                .iter()
                .any(|n| n.contains("com.gone: not installed"))
        );
    }

    #[test]
    fn restore_follows_restore_sh_order() {
        let d = tmp("restore");
        let store = Store::at(&d);
        store
            .append_unique(backup::DISABLED, "com.xiaomi.statistic")
            .unwrap();
        store
            .append_unique(backup::BG_RESTRICTED, "iflix.play")
            .unwrap();
        store.record_wifi_scan_before("1").unwrap();
        store.record_a11y_before("null").unwrap();
        store.record_private_dns_before("null", "null").unwrap();
        store
            .record_stock_home("com.google.android.tvlauncher/.MainActivity")
            .unwrap();
        let r = FakeRunner::new().on("cat /sdcard", "com.mixplorer\n").on(
            "enabled_accessibility_services",
            &format!("{A11Y_SERVICE}\n"),
        );
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = restore(&mut env, true);
        let l = lines(&p);
        let want = [
            "pm enable --user 0 com.xiaomi.statistic",
            "pm enable --user 0 com.mixplorer",
            &format!("rm -f {REMOTE_LOG}"),
            "cmd appops set iflix.play RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set iflix.play RUN_IN_BACKGROUND allow",
            "settings put global wifi_scan_always_enabled 1",
            "settings delete secure enabled_accessibility_services",
            "settings delete secure accessibility_enabled",
            "settings put global private_dns_mode opportunistic",
            "settings delete global private_dns_specifier",
            "set-home-activity com.google.android.tvlauncher/.MainActivity",
            "uninstall my.rafhan.app.tvhome",
        ];
        assert_eq!(l.len(), want.len(), "{l:#?}");
        for (got, w) in l.iter().zip(want) {
            assert!(got.ends_with(w), "{got} should end with {w}");
        }
    }

    #[test]
    fn restore_keeps_other_accessibility_services_and_the_switch() {
        let d = tmp("restore2");
        let store = Store::at(&d);
        store.record_a11y_before("0").unwrap();
        let r = FakeRunner::new().on(
            "enabled_accessibility_services",
            &format!("flar2.homebutton/a.i:{A11Y_SERVICE}\n"),
        );
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let l = lines(&restore(&mut env, false));
        assert!(
            l.iter()
                .any(|x| x.ends_with("enabled_accessibility_services flar2.homebutton/a.i"))
        );
        assert!(
            !l.iter().any(|x| x.contains("accessibility_enabled")),
            "another service still needs it on"
        );
    }

    #[test]
    fn tweaks_record_before_changing() {
        let d = tmp("tweaks");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on("wifi_scan_always_enabled", "1\n")
            .on("private_dns_mode", "null\n");
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let p = wifi_scan_off(&mut env);
        assert!(
            matches!(p.steps[0].action, Action::Record(Record::WifiScanBefore(ref v)) if v == "1")
        );
        let p = private_dns(&mut env);
        assert!(lines(&p)[2].ends_with(&format!("private_dns_specifier {ADGUARD_DNS}")));
        let p = restrict_background(&mut env, &["iflix.play".into()]);
        assert_eq!(
            p.steps[0].on_success,
            Some(Record::BgRestricted("iflix.play".into()))
        );
    }

    #[test]
    fn setup_all_installs_cleans_up_then_checks_once() {
        let d = tmp("setup-all");
        let store = Store::at(&d);
        let r = FakeRunner::new()
            .on(
                "pm list packages -e",
                "package:com.xiaomi.statistic\npackage:com.android.systemui\npackage:com.netflix.ninja\n",
            )
            .on("private_dns_mode", "null\n");
        let r = FakeRunner {
            rules: [r.rules, mi_tv().rules].concat(),
            ..Default::default()
        };
        let mut emit = |_e: EngineEvent| {};
        let mut env = Env {
            adb: &adb(),
            runner: &r,
            store: &store,
            emit: &mut emit,
        };
        let rel = Release {
            tag: "v1.9.1".into(),
            version: "1.9.1".into(),
            title: String::new(),
            date: String::new(),
            notes: String::new(),
            prerelease: false,
            apk: Some(github::Asset {
                name: "tvhome-1.9.1.apk".into(),
                size: 1,
                url: String::new(),
            }),
            tui: false,
        };
        let p = setup_all(&mut env, &rel, false, true).unwrap();
        let l = lines(&p);
        let at = |needle: &str| {
            l.iter()
                .position(|c| c.contains(needle))
                .unwrap_or_else(|| panic!("{needle}"))
        };
        // Install first, then the suggested (safe) apps only, then ads, then one Home check.
        assert!(at("pm install") < at("disable-user --user 0 com.xiaomi.statistic"));
        assert!(
            !l.iter().any(|c| c.contains("com.netflix.ninja")),
            "only the recommended apps"
        );
        assert!(
            !l.iter()
                .any(|c| c.contains("disable-user --user 0 com.android.systemui"))
        );
        assert!(at("com.xiaomi.statistic") < at("private_dns_specifier"));
        let checks: Vec<&Step> = p
            .steps
            .iter()
            .filter(|s| matches!(s.action, Action::VerifyHome(_)))
            .collect();
        assert_eq!(checks.len(), 1);
        assert_eq!(
            p.steps.last().map(|s| s.label.as_str()),
            Some("Check Home opens TV Home")
        );
        let Action::VerifyHome(v) = &checks[0].action else {
            unreachable!()
        };
        assert!(v.rollback.iter().any(|c| {
            c.join(" ")
                .contains("pm enable --user 0 com.xiaomi.statistic")
        }));
        assert!(
            p.summary
                .iter()
                .any(|s| s.starts_with("Turn off 1 suggested app"))
        );
        assert!(p.retry_after_uninstall.is_some());
    }

    #[test]
    fn debugging_off_is_one_command() {
        let d = tmp("debug-off");
        let store = Store::at(&d);
        for (sdk, wifi) in [("29", false), ("33", true)] {
            let r = FakeRunner::new()
                .on("getprop ro.build.version.sdk", &format!("{sdk}\n"))
                .fail("settings put", 255, "error: closed");
            let mut emit = |_e: EngineEvent| {};
            let mut env = Env {
                adb: &adb(),
                runner: &r,
                store: &store,
                emit: &mut emit,
            };
            let p = debugging_off(&mut env);
            assert_eq!(p.steps.len(), 1);
            assert_eq!(lines(&p)[0].contains("adb_wifi_enabled 0"), wifi);
            // The connection drops while it works: that's success.
            let mut e = |_e: EngineEvent| {};
            assert_eq!(
                run(&p, &r, &store, false, &mut e),
                Outcome::Done { noted: 0 }
            );
        }
    }

    #[test]
    fn latest_release_with_an_apk() {
        let rel = |v: &str, apk: bool| Release {
            tag: format!("v{v}"),
            version: v.into(),
            title: v.into(),
            date: String::new(),
            notes: String::new(),
            prerelease: false,
            apk: apk.then(|| github::Asset {
                name: format!("tvhome-{v}.apk"),
                size: 1,
                url: String::new(),
            }),
            tui: false,
        };
        let list = vec![rel("1.9", true), rel("1.10", true), rel("2.0", false)];
        assert_eq!(latest_with_apk(&list).unwrap().version, "1.10");
    }
}
