#!/bin/bash
# Undo everything this project changed on the TV (any brand), using what was recorded for it in
# backup/<serial>/ (see tv_backup in env.sh):
#  - re-enables every package listed in disabled-by-us.txt, and any the phone remote disabled
#  - lets background-restricted apps run in the background again and puts Wi-Fi scanning back
#    (only if those speed tweaks were made: bg-restricted.txt, speed-tweaks-before.txt)
#  - puts Private DNS back as it was (only if it was changed: private-dns-before.txt)
#  - removes this launcher's accessibility service and, if nothing else uses it, the
#    accessibility_enabled flag it turned on (accessibility-enabled-before.txt)
#  - makes the TV's own launcher the home screen again (and optionally removes this launcher)
# Usage: scripts/restore.sh [--remove-launcher]
source "$(dirname "$0")/env.sh"
PKG=my.rafhan.app.tvhome
tv_backup || exit 1
if [ -f "$B/disabled-by-us.txt" ]; then
  while read -r p; do
    [ -z "$p" ] && continue
    echo "$p -> $(A shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  done < "$B/disabled-by-us.txt"
fi
# Apps disabled from the phone remote's app manager (logged on the TV itself).
REMOTE_LOG="/sdcard/Android/data/$PKG/files/disabled-by-remote.txt"
for p in $(A shell "cat $REMOTE_LOG 2>/dev/null" | tr -d '\r'); do
  echo "$p -> $(A shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
done
A shell "rm -f $REMOTE_LOG"
# Speed tweaks: background restrictions and Wi-Fi scanning.
if [ -f "$B/bg-restricted.txt" ]; then
  while read -r p; do
    [ -z "$p" ] && continue
    A shell cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow
    A shell cmd appops set "$p" RUN_IN_BACKGROUND allow
    echo "$p -> background allowed"
  done < "$B/bg-restricted.txt"
fi
SCAN="$(sed -n 's/^wifi_scan_always_enabled=//p' "$B/speed-tweaks-before.txt" 2>/dev/null | tr -d '\r')"
if [ -n "$SCAN" ] && [ "$SCAN" != "null" ]; then
  A shell settings put global wifi_scan_always_enabled "$SCAN"
fi
# Phone remote: drop our accessibility service, keep the others (Button Mapper).
CUR="$(A shell settings get secure enabled_accessibility_services | tr -d '\r')"
NEW="$(echo "$CUR" | tr ':' '\n' | grep -v "$PKG/" | paste -sd: -)"
if [ "$NEW" != "$CUR" ]; then
  if [ -n "$NEW" ]; then A shell settings put secure enabled_accessibility_services "$NEW"
  else A shell settings delete secure enabled_accessibility_services; fi
fi
# accessibility_enabled was set to 1 for our service. Put the recorded value back, unless another
# service is still enabled (then 1 is correct).
BEFORE="$(tr -d '\r\n' < "$B/accessibility-enabled-before.txt" 2>/dev/null)"
if [ -n "$BEFORE" ] && [ "$BEFORE" != "1" ] && [ -z "$NEW" ]; then
  if [ "$BEFORE" = "null" ]; then A shell settings delete secure accessibility_enabled
  else A shell settings put secure accessibility_enabled "$BEFORE"; fi
fi
# Private DNS, as recorded before it was changed ("before: <mode> / <specifier>").
if [ -f "$B/private-dns-before.txt" ]; then
  DNS="$(sed -n 's/^before: //p' "$B/private-dns-before.txt" | tr -d '\r')"
  MODE="${DNS%% / *}"; SPEC="${DNS##* / }"
  if [ -z "$MODE" ] || [ "$MODE" = "null" ]; then
    A shell settings put global private_dns_mode opportunistic   # Android's default ("Automatic")
  else
    A shell settings put global private_dns_mode "$MODE"
  fi
  if [ -z "$SPEC" ] || [ "$SPEC" = "null" ]; then
    A shell settings delete global private_dns_specifier >/dev/null 2>&1
  else
    A shell settings put global private_dns_specifier "$SPEC"
  fi
fi
# Home screen: the TV's own launcher, as recorded by build-and-install.sh (older setups:
# home-default.txt). Unknown: let Android ask on the next Home press.
STOCK=""
if [ -s "$B/stock-home.txt" ]; then STOCK="$(tr -d '\r' < "$B/stock-home.txt")"
elif [ -s "$B/home-default.txt" ]; then STOCK="$(tail -1 "$B/home-default.txt" | tr -d '\r')"
fi
if [ -n "$STOCK" ]; then
  A shell cmd package set-home-activity "$STOCK"
else
  echo "note: the TV's own launcher wasn't recorded; press Home and pick it (or use --remove-launcher)."
fi
if [ "${1:-}" = "--remove-launcher" ]; then A shell pm uninstall "$PKG"; fi
echo "Done. Press Home on the remote (or reboot) to return to the TV's own launcher."
