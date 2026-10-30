#!/bin/bash
# Usage: scripts/restrict.sh pkg [pkg...]
#        scripts/restrict.sh --recommended
# Stops each app running in the background (cmd appops ... RUN_ANY_IN_BACKGROUND ignore): it
# still opens and works, it just can't run services, jobs or alarms while it isn't on screen.
# Logs it to the TV's backup/<serial>/bg-restricted.txt so restore.sh lets it run again.
# --recommended: the apps tui/catalog.toml marks restrict = true (PatchWall: kept for the Input
# button's picker, but its scheduled jobs wake it while you watch), as the setup app does.
source "$(dirname "$0")/env.sh"
tv_backup || exit 1
LOG="$B/bg-restricted.txt"
if [ "$1" = "--recommended" ]; then
  shift
  set -- $(awk '/^\[\[app\]\]/ { if (p != "" && r == "true") print p; p = ""; r = "" }
                /^package = / { gsub(/"/, "", $3); p = $3 }
                /^restrict = / { r = $3 }
                END { if (p != "" && r == "true") print p }' "$(dirname "$0")/../tui/catalog.toml")
fi
for p in "$@"; do
  if ! A shell pm list packages -e "$p" | tr -d '\r' | grep -qx "package:$p"; then echo "skip (not installed or off): $p"; continue; fi
  A shell cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore
  echo "$p -> $(A shell cmd appops get "$p" RUN_ANY_IN_BACKGROUND | tr -d '\r')"
  grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG"
done
