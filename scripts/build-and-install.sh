#!/bin/bash
# Build the Apple TV style launcher, install it on the TV and make it the default home screen.
# Works on any Android TV / Google TV 10 or newer, from macOS, Linux or Windows (Git Bash).
# Usage: TV=<ip> scripts/build-and-install.sh [--disable-stock]
#   TV=<ip>          the TV's address, needed the first time only (saved in .tv)
#   --disable-stock  also disables the TV's own launcher, and on Google TV its setup app (both
#                    logged, so restore.sh undoes them); needed on Google TV, where Home otherwise
#                    keeps opening the stock launcher
# See docs/install-*.md for setting up a computer.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
PKG=my.rafhan.app.tvhome
DOCS="see docs/install-macos.md, docs/install-linux.md or docs/install-windows.md"
die() { echo "Error: $*" >&2; exit 1; }

# ---- the computer: adb, Java 17+, Android SDK
command -v adb >/dev/null || die "adb not found. Install Android platform-tools ($DOCS)."
# A JAVA_HOME that points somewhere stale is ignored; macOS can find a JDK by itself.
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ] && [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java.exe" ]; then
  unset JAVA_HOME
  if [ -x /usr/libexec/java_home ] && JH="$(/usr/libexec/java_home -v 17+ 2>/dev/null)"; then
    export JAVA_HOME="$JH"
  fi
fi
JAVA="java"; [ -n "${JAVA_HOME:-}" ] && JAVA="$JAVA_HOME/bin/java"
JV="$("$JAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')" || true
case "${JV:-}" in ''|*[!0-9]*) JV= ;; esac
[ "${JV:-0}" -ge 17 ] 2>/dev/null || die "JDK 17 or newer is needed to build (found: ${JV:-none}). $DOCS"
SDK_DIR="$(sed -n 's/^sdk\.dir=//p' launcher/local.properties 2>/dev/null || true)"
SDK_DIR="${SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
[ -n "$SDK_DIR" ] && [ -d "$SDK_DIR" ] || die "Android SDK not found: set ANDROID_HOME ($DOCS)."

# ---- the TV: reachable, authorised, Android 10+
adb connect "$TV" >/dev/null || true
STATE="$(adb -s "$TV" get-state 2>&1 || true)"
case "$STATE" in
  device) ;;
  *unauthorized*) die "the TV hasn't allowed this computer yet: accept 'Allow USB debugging?' on the TV, then run again." ;;
  *) die "can't reach the TV at $TV ($STATE). Check its IP, that USB/network debugging is on, and that both are on the same network." ;;
esac
echo "$TV" > .tv   # reached it: the next runs (and the other scripts) don't need TV=
SDK="$(A shell getprop ro.build.version.sdk | tr -d '\r')"
[ "${SDK:-0}" -ge 29 ] 2>/dev/null || die "the TV runs Android SDK ${SDK:-?}; this launcher needs Android 10 (SDK 29) or newer."

# ---- remember the TV's own launcher before replacing it (any brand), for --disable-stock and
# restore.sh, in this TV's backup/<serial>/. Older setups recorded it in home-default.txt.
tv_backup
stock_home() {
  if [ -s "$B/stock-home.txt" ]; then tr -d '\r' < "$B/stock-home.txt"
  elif [ -s "$B/home-default.txt" ]; then tail -1 "$B/home-default.txt" | tr -d '\r'
  fi
}
if [ -z "$(stock_home)" ]; then
  CUR="$(A shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')"
  case "$CUR" in
    "$PKG"/*|*ResolverActivity*|*FallbackHome*|"") ;;  # ours, "choose an app", or none: nothing to record
    */*) echo "$CUR" > "$B/stock-home.txt"; echo "TV's own launcher: $CUR (saved for restore.sh)" ;;
  esac
fi

# ---- build and install
(cd launcher && sh ./gradlew -q assembleRelease)
APK=launcher/app/build/outputs/apk/release/app-release.apk

# Push + pm install instead of `adb install`: on some sets adb install waits for an on-screen confirmation.
A push "$APK" /data/local/tmp/tvhome.apk >/dev/null
INSTALL="$(A shell pm install -r /data/local/tmp/tvhome.apk 2>&1 | tr -d '\r' || true)"
A shell rm /data/local/tmp/tvhome.apk
echo "$INSTALL"
case "$INSTALL" in
  *UPDATE_INCOMPATIBLE*)
    die "the launcher on the TV was signed with a different key (for example a GitHub Release, or a
build from another computer). Uninstall it once, which resets its settings, then run this again:
  adb -s $TV uninstall $PKG
Or sign your builds with the project's release key (docs/development.md)." ;;
  *Success*) ;;
  *) die "the TV refused the install (above)." ;;
esac

# Optional permissions: a TV that refuses one just loses that feature, so carry on.
opt() { A shell "$@" >/dev/null 2>&1 || echo "note: '$*' was refused by this TV (that feature may be limited)"; }

# Phone remote Files tab. Granted straight after install, before anything restarts the launcher,
# because Android 10 sets an app's storage access when its process starts.
opt pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
opt pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE

# Android 11+ replaced those with scoped storage: "All files access" keeps the Files tab, the
# Wallpapers folder and reading the screenshot file working. The app is sideloaded, so granting
# it over ADB (rather than the Settings → All files access toggle) is enough.
if [ "${SDK:-0}" -ge 30 ] 2>/dev/null; then
  opt appops set $PKG MANAGE_EXTERNAL_STORAGE allow
fi

# Android 10 only reveals the Wi-Fi network name to apps with location permission.
opt pm grant $PKG android.permission.ACCESS_FINE_LOCATION

# Usage access, for the "Most used" card and the usage-sorted grid.
opt appops set $PKG GET_USAGE_STATS allow

# Phone remote: the overlay permission shows the pairing PIN and lets the remote open apps while
# another app is on screen (Android 10 blocks background activity starts otherwise).
opt appops set $PKG SYSTEM_ALERT_WINDOW allow

# Phone remote Back/Home in any app: enable our accessibility service next to whatever is
# already enabled (Button Mapper), never replacing it. The flag itself is recorded the first time
# so restore.sh can put it back (it is 1 only because a service is enabled).
SVC="$PKG/$PKG.RemoteAccessibilityService"
if [ ! -f "$B/accessibility-enabled-before.txt" ]; then
  A shell settings get secure accessibility_enabled | tr -d '\r' > "$B/accessibility-enabled-before.txt"
fi
CUR="$(A shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$CUR" in
  *"$SVC"*) ;;
  null|"") opt settings put secure enabled_accessibility_services "$SVC" ;;
  *) opt settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
esac
opt settings put secure accessibility_enabled 1

# Now Playing card: low-RAM TVs refuse notification-listener access, so without USB debugging the
# card reads `dumpsys media_session`, which needs DUMP (a development permission).
opt pm grant $PKG android.permission.DUMP
# Bell: reading the Play Store's update notifications from `dumpsys notification` also needs the
# real PACKAGE_USAGE_STATS permission (the usage-access appop above isn't enough for that service).
opt pm grant $PKG android.permission.PACKAGE_USAGE_STATS

# Several apps can claim HOME (stock launcher, factory test, setup leftovers): pick ours explicitly.
A shell cmd package set-home-activity $PKG/.MainActivity

if [ "${1:-}" = "--disable-stock" ]; then
  # Google TV: the setup app also answers HOME, at a higher priority than any launcher, so with only
  # the stock launcher disabled Home lands on setup (or the stock launcher takes it back). Disable
  # setup first. Not when it owns the saved Wi-Fi network: disabling it deletes that network
  # (re-join Wi-Fi from Settings, or use Ethernet, then run this again). Logged, so restore.sh
  # turns it back on; re-enable it before a factory reset. Phone casting may stop after a reboot
  # while it's off (the Cast receiver reads its credentials from it).
  SETUP=com.google.android.tungsten.setupwraith
  ENABLED="$(A shell pm list packages -e "$SETUP" | tr -d '\r' || true)"
  if printf '%s\n' "$ENABLED" | grep -qx "package:$SETUP"; then
    WIFI="$(A shell dumpsys wifi | tr -d '\r' | grep -o 'cname=[^ ]*' || true)"
    if printf '%s\n' "$WIFI" | grep -q setupwraith; then
      # Leaving setup on and disabling the launcher would send Home to setup's recovery screen,
      # so stop here: TV Home is installed, Home still opens Google TV's launcher.
      echo "Google TV's setup app made this TV's Wi-Fi connection, so it can't be disabled (that would"
      echo "delete the connection), and while it's on, Home can't be handed to this launcher. Nothing"
      echo "was disabled. On the TV: Settings -> Network & Internet -> your Wi-Fi -> Forget network, then"
      echo "join it again with its password (or use Ethernet), and run this again."
      exit 1
    else
      scripts/disable.sh "$SETUP"
    fi
  fi
  STOCK="$(stock_home)"
  if [ -n "$STOCK" ]; then
    scripts/disable.sh "${STOCK%%/*}"
    A shell cmd package set-home-activity $PKG/.MainActivity >/dev/null
  else
    echo "note: the TV's own launcher isn't known, so nothing was disabled. Home apps on this TV:"
    A shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | grep / | grep -v "$PKG" || true
    echo "Disable the right one with: scripts/disable.sh <package>"
  fi
fi

A shell input keyevent KEYCODE_HOME
# What's actually in front after pressing Home: set-home-activity can say Success while Home still
# goes to the TV's own launcher, so check the focused app rather than the resolver's answer. A fresh
# install takes a few seconds to start, so give it up to 12 s.
for _ in 1 2 3 4 5 6; do
  sleep 2
  FOCUS="$(A shell dumpsys window | tr -d '\r' | grep mFocusedApp | head -1 || true)"
  [ -n "$FOCUS" ] || FOCUS="$(A shell dumpsys activity activities | tr -d '\r' | grep ResumedActivity | head -1 || true)"
  case "$FOCUS" in *"$PKG/"*) break ;; esac
done
case "$FOCUS" in
  *"$PKG/"*) echo "Installed. Home opens this launcher." ;;
  *)
    echo "Installed, but Home still opens something else:"
    echo "  $(echo "$FOCUS" | sed 's/^ *//')"
    echo "Apps on this TV that answer Home:"
    A shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | grep / | grep -v "$PKG" || true
    if [ "${1:-}" = "--disable-stock" ]; then
      echo "Disable the one that keeps taking Home with: scripts/disable.sh <package>"
    else
      echo "Run again with --disable-stock (Google TV needs it)."
    fi
    ;;
esac
