//! Notes for the Debloat menu (catalog.toml, built in) and the rules for what may be disabled.

use crate::device::{self, PKG};
use serde::Deserialize;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, PartialOrd, Ord)]
#[serde(rename_all = "lowercase")]
pub enum Advice {
    Safe,
    Caution,
    Keep,
    Never,
    Handover,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Entry {
    pub package: String,
    pub label: String,
    pub category: String,
    pub advice: Advice,
    pub note: String,
}

#[derive(Deserialize)]
struct File {
    app: Vec<Entry>,
}

pub struct Catalog {
    by_package: BTreeMap<String, Entry>,
}

impl Catalog {
    /// cleanyourtv's per-device lists (catalog-cleanyourtv.toml, generated), with this project's
    /// own notes (catalog.toml) on top: ours win for any package in both.
    pub fn builtin() -> Self {
        let mut c = Self::parse(include_str!("../catalog-cleanyourtv.toml"))
            .expect("catalog-cleanyourtv.toml is valid");
        let ours = Self::parse(include_str!("../catalog.toml")).expect("catalog.toml is valid");
        c.by_package.extend(ours.by_package);
        c
    }

    pub fn parse(text: &str) -> anyhow::Result<Self> {
        let f: File = toml::from_str(text)?;
        Ok(Catalog {
            by_package: f.app.into_iter().map(|e| (e.package.clone(), e)).collect(),
        })
    }

    pub fn get(&self, package: &str) -> Option<&Entry> {
        self.by_package.get(package)
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.by_package.len()
    }
}

/// Live facts that decide what may be disabled on this TV.
#[derive(Debug, Default, Clone)]
pub struct Guard {
    /// The current keyboard.
    pub ime: Option<String>,
    /// Apps that answer Home.
    pub home_apps: BTreeSet<String>,
    /// The recorded stock launcher's package (it's also a Home app, but may be disabled once
    /// this launcher is Home: that's what the handover and --disable-stock do).
    pub stock_home: Option<String>,
}

/// Why a package may not be disabled from the menu, or `None` if it may.
pub fn refusal(cat: &Catalog, guard: &Guard, package: &str) -> Option<String> {
    if package == PKG {
        return Some("This is the launcher itself (use Restore → uninstall).".into());
    }
    if let Some(e) = cat.get(package) {
        match e.advice {
            Advice::Never => return Some(format!("The TV needs it: {}", e.note)),
            Advice::Handover => return Some("Use Install → Google TV handover.".into()),
            _ => {}
        }
    }
    if guard.ime.as_deref() == Some(package) {
        return Some("That's the TV's keyboard.".into());
    }
    let is_stock = guard.stock_home.as_deref() == Some(package);
    if guard.home_apps.contains(package) && !is_stock {
        return Some(
            "It answers the Home button: disabling it could leave Home with nowhere to go.".into(),
        );
    }
    None
}

/// The apps to suggest turning off: on, in the notes as *safe*, and not refused. Sorted.
pub fn recommended<'a>(
    cat: &Catalog,
    guard: &Guard,
    enabled: impl IntoIterator<Item = &'a String>,
) -> Vec<String> {
    let mut v: Vec<String> = enabled
        .into_iter()
        .filter(|p| {
            cat.get(p)
                .map(|e| e.advice == Advice::Safe)
                .unwrap_or(false)
        })
        .filter(|p| refusal(cat, guard, p).is_none())
        .cloned()
        .collect();
    v.sort();
    v.dedup();
    v
}

/// Every Home-answering package from `query-activities` output (ours excluded).
pub fn home_packages(components: &[String]) -> BTreeSet<String> {
    components
        .iter()
        .map(|c| device::package_of(c).to_string())
        .filter(|p| p != PKG)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_catalog_parses() {
        let c = Catalog::builtin();
        assert!(c.len() > 50);
        assert_eq!(c.get("com.xiaomi.statistic").unwrap().advice, Advice::Safe);
        assert_eq!(c.get("com.android.vending").unwrap().advice, Advice::Never);
    }

    #[test]
    fn our_notes_win_over_cleanyourtv() {
        let c = Catalog::builtin();
        assert!(c.len() > 1400, "cleanyourtv's lists are loaded");
        // Re-enabled on purpose on the Mi TV: ours says keep, cleanyourtv's lists disable it.
        assert_eq!(
            c.get("com.xiaomi.mitv.tvmanager").unwrap().advice,
            Advice::Keep
        );
        // Google TV's setup app is only for the handover, never a plain tick.
        assert_eq!(c.get(device::GTV_SETUP).unwrap().advice, Advice::Handover);
        // From cleanyourtv: telemetry seen on many devices is suggested.
        assert_eq!(c.get("tv.samba.ssm").unwrap().advice, Advice::Safe);
    }

    #[test]
    fn nothing_risky_is_suggested() {
        // Only the generated suggestions: catalog.toml is this project's own reviewed list (it
        // suggests the old Leanback launcher's background part, which isn't a Home app).
        let c = Catalog::builtin();
        let ours = Catalog::parse(include_str!("../catalog.toml")).unwrap();
        for (p, e) in &c.by_package {
            if e.advice != Advice::Safe || ours.get(p).is_some() {
                continue;
            }
            let lp = p.to_lowercase();
            for bad in [
                "webview",
                "inputmethod",
                "keyboard",
                "bluetooth",
                "tvinput",
                "launcher",
                "setupwizard",
                "systemupdate",
                "micmanager",
            ] {
                assert!(
                    !lp.contains(bad),
                    "{p} is suggested but looks like something the TV needs"
                );
            }
        }
    }

    #[test]
    fn every_documented_mi_tv_package_is_in_the_catalog() {
        // docs/xiaomi-debloat.md "What was disabled": 22 packages.
        let documented = [
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.google.android.feedback",
            "com.google.android.tv.bugreportsender",
            "com.mitv.tvhome.michannel",
            "com.xm.webcontent",
            "android.autoinstalls.config.xiaomi.amelie",
            "fusion.android.tv.demo",
            "com.google.android.tvrecommendations",
            "com.google.android.leanbacklauncher.recommendations",
            "com.android.printspooler",
            "com.google.android.leanbacklauncher",
            "com.mitv.dream",
            "com.google.android.backdrop",
            "com.amazon.amazonvideo.livingroom",
            "com.google.android.videos",
            "com.google.android.play.games",
            "com.google.android.marvin.talkback",
            "com.google.android.katniss",
            "com.google.android.tv.assistant",
            "com.xiaomi.mitv.mediaexplorer",
            "com.google.android.tvlauncher",
        ];
        let c = Catalog::builtin();
        for p in documented {
            assert!(c.get(p).is_some(), "{p} missing from catalog.toml");
        }
    }

    #[test]
    fn protected_packages_match_the_launchers_app_manager() {
        // AppManager.PROTECTED in the launcher: the phone remote refuses these too.
        let protected = [
            "android",
            "com.android.systemui",
            "com.android.tv.settings",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.mediatek.wwtv.tvcenter",
            "com.mediatek.tvinput",
            "com.mediatek.tv.service",
            "mitv.service",
        ];
        let c = Catalog::builtin();
        for p in protected {
            assert_eq!(
                c.get(p).map(|e| e.advice),
                Some(Advice::Never),
                "{p} should be 'never'"
            );
        }
    }

    #[test]
    fn refusals() {
        let c = Catalog::builtin();
        let guard = Guard {
            ime: Some("com.google.android.inputmethod.latin".into()),
            home_apps: [
                "com.google.android.tvlauncher".to_string(),
                "com.duokan.factorytest".to_string(),
            ]
            .into_iter()
            .collect(),
            stock_home: Some("com.google.android.tvlauncher".into()),
        };
        assert!(refusal(&c, &guard, PKG).is_some());
        assert!(refusal(&c, &guard, "com.google.android.gms").is_some());
        assert!(refusal(&c, &guard, device::GTV_SETUP).is_some());
        assert!(refusal(&c, &guard, "com.google.android.inputmethod.latin").is_some());
        assert!(refusal(&c, &guard, "com.duokan.factorytest").is_some());
        // The recorded stock launcher may be disabled (what --disable-stock does).
        assert!(refusal(&c, &guard, "com.google.android.tvlauncher").is_none());
        assert!(refusal(&c, &guard, "com.xiaomi.statistic").is_none());
        assert!(refusal(&c, &guard, "some.unknown.app").is_none());
    }

    #[test]
    fn recommended_are_safe_on_and_allowed() {
        let c = Catalog::builtin();
        let guard = Guard {
            ime: None,
            home_apps: ["com.xiaomi.statistic".to_string()].into_iter().collect(),
            stock_home: None,
        };
        let on: Vec<String> = [
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.android.vending",
            "some.app",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        // statistic answers Home here (refused), Play Store is "never", some.app has no notes.
        assert_eq!(
            recommended(&c, &guard, &on),
            vec!["com.miui.tv.analytics".to_string()]
        );
    }

    #[test]
    fn home_packages_skip_ours() {
        let comps = vec![
            "my.rafhan.app.tvhome/.MainActivity".to_string(),
            "com.duokan.factorytest/.FactoryLauncher".to_string(),
        ];
        let h = home_packages(&comps);
        assert_eq!(
            h.into_iter().collect::<Vec<_>>(),
            vec!["com.duokan.factorytest"]
        );
    }
}
