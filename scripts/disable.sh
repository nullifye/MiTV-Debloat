#!/bin/bash
# Usage: scripts/disable.sh pkg [pkg...]
#        scripts/disable.sh --recommended
# Disables each package for user 0 (never uninstalls) and logs it to the TV's
# backup/<serial>/disabled-by-us.txt so restore.sh can re-enable it. One pm call per adb shell
# call: chained pm commands can stop early.
# --recommended: what the setup app ticks for you: every package marked "safe" in
# tui/catalog.toml (this project's notes) or tui/catalog-cleanyourtv.toml (cleanyourtv's lists),
# ours winning where both list it, minus anything that answers Home or is the keyboard.
source "$(dirname "$0")/env.sh"
tv_backup || exit 1
LOG="$B/disabled-by-us.txt"
GUARD=""
if [ "$1" = "--recommended" ]; then
  shift
  set -- $(awk 'function flush() {
                  if (p != "") {
                    if (f == 1) { ours[p] = 1; if (a == "safe") print p }   # ours: catalog.toml
                    else if (!(p in ours) && a == "safe") print p          # cleanyourtv, unless ours lists it
                  }
                  p = ""; a = ""
                }
                FNR == 1 { flush(); f++ }
                /^\[\[app\]\]/ { flush() }
                /^package = / { gsub(/"/, "", $3); p = $3 }
                /^advice = / { gsub(/"/, "", $3); a = $3 }
                END { flush() }' "$(dirname "$0")/../tui/catalog.toml" "$(dirname "$0")/../tui/catalog-cleanyourtv.toml")
  GUARD="$(A shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | grep / | cut -d/ -f1) $(A shell settings get secure default_input_method | tr -d '\r' | cut -d/ -f1)"
fi
for p in "$@"; do
  if ! A shell pm list packages "$p" | tr -d '\r' | grep -qx "package:$p"; then echo "skip (not installed): $p"; continue; fi
  case " $(echo $GUARD) " in *" $p "*) echo "skip (the TV's Home or keyboard): $p"; continue;; esac
  # Already disabled before we touched it (the TV's own choice, or an earlier run): leave it, and
  # don't log it, so restore.sh doesn't turn on something this project never turned off.
  if A shell pm list packages -d "$p" | tr -d '\r' | grep -qx "package:$p"; then
    echo "skip (already disabled): $p"
    continue
  fi
  out=$(A shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')
  echo "$p -> $out"
  case "$out" in *disabled-user*) grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG";; esac
done
