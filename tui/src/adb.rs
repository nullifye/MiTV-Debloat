//! Running `adb`: finding the executable, running one command with a timeout, and a fake runner
//! for tests. Commands are run directly, never through a shell, so Windows shells can't rewrite
//! paths (the MSYS problem the bash scripts had) and nothing needs quoting.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

/// What a finished command printed.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Output {
    /// Exit code; `None` when it was killed (timeout) or couldn't start.
    pub code: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub millis: u128,
}

impl Output {
    pub fn ok(&self) -> bool {
        self.code == Some(0)
    }

    /// stdout and stderr together, with Android's `\r` line endings removed.
    pub fn text(&self) -> String {
        let mut s = self.stdout.replace('\r', "");
        let err = self.stderr.replace('\r', "");
        if !err.trim().is_empty() {
            if !s.is_empty() && !s.ends_with('\n') {
                s.push('\n');
            }
            s.push_str(&err);
        }
        s
    }
}

/// Runs a full command line (`argv[0]` is the program). The real one spawns processes; tests use
/// [`FakeRunner`].
pub trait Runner: Send + Sync {
    fn run(&self, argv: &[String], timeout: Duration) -> Output;
}

/// The adb executable plus the TV it talks to.
#[derive(Debug, Clone)]
pub struct Adb {
    pub exe: PathBuf,
    /// `ip:port`; `None` until a TV is chosen.
    pub serial: Option<String>,
}

impl Adb {
    /// `adb -s <serial> <args…>`: every call is pinned to the chosen TV.
    pub fn cmd(&self, args: &[&str]) -> Vec<String> {
        let mut v = vec![self.exe.display().to_string()];
        if let Some(s) = &self.serial {
            v.push("-s".into());
            v.push(s.clone());
        }
        v.extend(args.iter().map(|a| a.to_string()));
        v
    }

    /// `adb <args…>` without a device (connect, devices, kill-server).
    pub fn global(&self, args: &[&str]) -> Vec<String> {
        let mut v = vec![self.exe.display().to_string()];
        v.extend(args.iter().map(|a| a.to_string()));
        v
    }

    /// `adb -s <serial> shell <args…>`.
    pub fn shell(&self, args: &[&str]) -> Vec<String> {
        let mut a = vec!["shell"];
        a.extend_from_slice(args);
        self.cmd(&a)
    }
}

/// The command line as shown to people: the program's file name, not its full path.
pub fn display(argv: &[String]) -> String {
    let mut parts = Vec::with_capacity(argv.len());
    for (i, a) in argv.iter().enumerate() {
        let a = if i == 0 {
            // Both separators: a Windows path shown (or tested) on another OS too.
            let name = a.rsplit(['/', '\\']).next().unwrap_or(a);
            let lower = name.to_ascii_lowercase();
            lower
                .strip_suffix(".exe")
                .map(|_| name[..name.len() - 4].to_string())
                .unwrap_or_else(|| name.to_string())
        } else {
            a.clone()
        };
        if a.is_empty() || a.contains(char::is_whitespace) || a.contains('"') {
            parts.push(format!("\"{}\"", a.replace('"', "\\\"")));
        } else {
            parts.push(a);
        }
    }
    parts.join(" ")
}

/// Finds adb: an explicit path, then PATH, then the usual SDK and package-manager locations, and
/// last the copy this app downloaded into `own_copy` (an installed adb wins: two different adb
/// versions keep restarting each other's server).
pub fn find_adb(explicit: Option<&Path>, own_copy: &Path) -> Option<PathBuf> {
    if let Some(p) = explicit {
        return p.is_file().then(|| p.to_path_buf());
    }
    let exe = if cfg!(windows) { "adb.exe" } else { "adb" };
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let c = dir.join(exe);
            if c.is_file() {
                return Some(c);
            }
        }
    }
    let mut candidates: Vec<PathBuf> = Vec::new();
    for var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(v) = std::env::var_os(var) {
            candidates.push(PathBuf::from(v).join("platform-tools").join(exe));
        }
    }
    if let Some(home) = dirs::home_dir() {
        candidates.push(home.join("Library/Android/sdk/platform-tools").join(exe));
        candidates.push(home.join("Android/Sdk/platform-tools").join(exe));
    }
    if let Some(local) = dirs::data_local_dir() {
        candidates.push(local.join("Android/Sdk/platform-tools").join(exe));
        // winget's Google.PlatformTools
        candidates.push(local.join("Microsoft/WinGet/Links").join(exe));
    }
    candidates.push(PathBuf::from("/opt/homebrew/bin").join(exe));
    candidates.push(PathBuf::from("/usr/local/bin").join(exe));
    candidates.push(PathBuf::from("/usr/bin").join(exe));
    candidates.push(own_copy.to_path_buf());
    candidates.into_iter().find(|c| c.is_file())
}

/// Spawns real processes.
pub struct RealRunner;

impl Runner for RealRunner {
    fn run(&self, argv: &[String], timeout: Duration) -> Output {
        let start = Instant::now();
        let Some((prog, args)) = argv.split_first() else {
            return Output::default();
        };
        let mut child = match Command::new(prog)
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
        {
            Ok(c) => c,
            Err(e) => {
                return Output {
                    code: None,
                    stdout: String::new(),
                    stderr: format!("couldn't start {prog}: {e}"),
                    millis: start.elapsed().as_millis(),
                };
            }
        };
        // Read both pipes on their own threads so a chatty command can't fill a pipe and stall.
        let mut out = child.stdout.take().expect("piped stdout");
        let mut err = child.stderr.take().expect("piped stderr");
        let t_out = thread::spawn(move || {
            let mut b = Vec::new();
            let _ = out.read_to_end(&mut b);
            b
        });
        let t_err = thread::spawn(move || {
            let mut b = Vec::new();
            let _ = err.read_to_end(&mut b);
            b
        });
        let mut code = None;
        let mut timed_out = false;
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    code = status.code();
                    break;
                }
                Ok(None) if start.elapsed() >= timeout => {
                    let _ = child.kill();
                    let _ = child.wait();
                    timed_out = true;
                    break;
                }
                Ok(None) => thread::sleep(Duration::from_millis(15)),
                Err(_) => break,
            }
        }
        let stdout = String::from_utf8_lossy(&t_out.join().unwrap_or_default()).to_string();
        let mut stderr = String::from_utf8_lossy(&t_err.join().unwrap_or_default()).to_string();
        if timed_out {
            stderr.push_str(&format!(
                "\n(stopped after {} s with no result)",
                timeout.as_secs()
            ));
        }
        Output {
            code,
            stdout,
            stderr,
            millis: start.elapsed().as_millis(),
        }
    }
}

/// Replays canned outputs for tests: the first rule whose needle appears in the command line
/// answers. Records every command it was asked to run.
#[cfg(test)]
#[derive(Default)]
pub struct FakeRunner {
    pub rules: Vec<(String, Output)>,
    pub calls: std::sync::Mutex<Vec<String>>,
}

#[cfg(test)]
impl FakeRunner {
    pub fn new() -> Self {
        Self::default()
    }

    /// Commands containing `needle` print `stdout` and exit 0.
    pub fn on(mut self, needle: &str, stdout: &str) -> Self {
        self.rules.push((
            needle.to_string(),
            Output {
                code: Some(0),
                stdout: stdout.to_string(),
                ..Default::default()
            },
        ));
        self
    }

    /// Commands containing `needle` exit with `code` and print `text` on stderr.
    pub fn fail(mut self, needle: &str, code: i32, text: &str) -> Self {
        self.rules.push((
            needle.to_string(),
            Output {
                code: Some(code),
                stderr: text.to_string(),
                ..Default::default()
            },
        ));
        self
    }

    pub fn calls(&self) -> Vec<String> {
        self.calls.lock().unwrap().clone()
    }
}

#[cfg(test)]
impl Runner for FakeRunner {
    fn run(&self, argv: &[String], _timeout: Duration) -> Output {
        let line = display(argv);
        self.calls.lock().unwrap().push(line.clone());
        for (needle, out) in &self.rules {
            if line.contains(needle.as_str()) {
                return out.clone();
            }
        }
        Output {
            code: Some(0),
            ..Default::default()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn adb() -> Adb {
        Adb {
            exe: PathBuf::from("/opt/homebrew/bin/adb"),
            serial: Some("192.168.0.44:5555".into()),
        }
    }

    #[test]
    fn commands_are_pinned_to_the_tv() {
        let a = adb();
        assert_eq!(
            display(&a.shell(&["pm", "disable-user", "--user", "0", "com.x"])),
            "adb -s 192.168.0.44:5555 shell pm disable-user --user 0 com.x"
        );
        assert_eq!(
            display(&a.global(&["connect", "1.2.3.4:5555"])),
            "adb connect 1.2.3.4:5555"
        );
    }

    #[test]
    fn display_quotes_arguments_with_spaces() {
        let a = adb();
        assert_eq!(
            display(&a.shell(&["dumpsys wifi | grep -o 'cname=[^ ]*'"])),
            "adb -s 192.168.0.44:5555 shell \"dumpsys wifi | grep -o 'cname=[^ ]*'\""
        );
    }

    #[test]
    fn windows_exe_name_is_shortened() {
        assert_eq!(
            display(&["C:\\tools\\adb.exe".to_string(), "devices".into()]),
            "adb devices"
        );
    }

    #[test]
    fn output_text_joins_streams_and_drops_carriage_returns() {
        let o = Output {
            code: Some(1),
            stdout: "Failure [X]\r\n".into(),
            stderr: "oops\r\n".into(),
            millis: 1,
        };
        assert_eq!(o.text(), "Failure [X]\noops\n");
        assert!(!o.ok());
    }

    #[test]
    fn real_runner_reports_a_missing_program() {
        let o = RealRunner.run(&["/nonexistent/adb".into()], Duration::from_secs(1));
        assert_eq!(o.code, None);
        assert!(o.stderr.contains("couldn't start"));
    }

    #[cfg(unix)]
    #[test]
    fn real_runner_times_out() {
        let o = RealRunner.run(&["sleep".into(), "5".into()], Duration::from_millis(200));
        assert_eq!(o.code, None);
        assert!(o.stderr.contains("stopped after"));
    }
}
