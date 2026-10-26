# Source me: shared settings for all scripts.
# The TV's address: TV=<ip> (or <ip>:<port>) on the command line, else the one build-and-install.sh
# saved in .tv once it reached the TV. Port 5555 is assumed when none is given.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "${TV:-}" ]; then
  case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
elif [ -f "$ROOT/.tv" ]; then
  TV="$(tr -d '\r\n ' < "$ROOT/.tv")"
fi
if [ -z "${TV:-}" ]; then
  echo "Which TV? Run it with the TV's IP address the first time, for example:" >&2
  echo "  TV=192.168.1.50 $0 $*" >&2
  exit 1
fi
# On Windows the bash shells rewrite arguments that look like Unix paths into Windows ones, so a
# path on the TV such as /data/local/tmp/x.apk would reach adb as C:/Program Files/Git/data/...
# Switch that off for adb: MSYS_NO_PATHCONV for Git Bash, MSYS2_ARG_CONV_EXCL for MSYS2 (UCRT64,
# MINGW64). Local files given to adb are relative paths, which need no rewriting.
A() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' adb -s "$TV" "$@"; }

# Each TV's records (what was disabled, settings before they were changed, the stock launcher)
# live in backup/<serial>/, so records for one TV never undo another. Sets B to that folder.
# The serial is ro.serialno (the TV's address when it has none); tv.txt inside names the TV.
# Records from before per-TV folders were loose in backup/: they move into the folder of the TV
# that backup/getprop.txt names, or of the first TV seen when there's no getprop.txt.
tv_backup() {
  adb connect "$TV" >/dev/null 2>&1 || true
  if [ "$(A get-state 2>/dev/null | tr -d '\r')" != device ]; then
    echo "Error: can't reach the TV at $TV (is it on, with USB debugging allowed?)" >&2
    return 1
  fi
  local k id owner e
  for k in ro.serialno ro.boot.serialno persist.sys.serialno; do
    id="$(A shell getprop "$k" | tr -d '\r\n')"
    [ -n "$id" ] && [ "$id" != unknown ] && [ -n "$(printf %s "$id" | tr -d 0)" ] && break
    id=""
  done
  [ -n "$id" ] || id="${TV%%:*}"
  id="$(printf %s "$id" | tr -c 'A-Za-z0-9_-' '_')"
  B="$ROOT/backup/$id"
  mkdir -p "$B"
  if [ -n "$(find "$ROOT/backup" -maxdepth 1 -type f ! -name '.*' 2>/dev/null)" ]; then
    owner="$(sed -n 's/^\[ro\.serialno\]: \[\(.*\)\].*$/\1/p' "$ROOT/backup/getprop.txt" 2>/dev/null | head -1 | tr -d '\r\n')"
    owner="$(printf %s "$owner" | tr -c 'A-Za-z0-9_-' '_')"
    if [ -z "$owner" ] || [ "$owner" = "$id" ]; then
      for e in "$ROOT/backup"/*; do
        if [ "$e" = "$B" ] || [ -f "$e/tv.txt" ]; then continue; fi
        mv -n "$e" "$B/" || true
      done
      echo "Moved the earlier records in backup/ to backup/$id/ (this TV's folder)." >&2
    fi
  fi
  printf '%s %s\n%s\n' "$(A shell getprop ro.product.brand | tr -d '\r\n')" \
    "$(A shell getprop ro.product.model | tr -d '\r\n')" "$TV" > "$B/tv.txt"
}
