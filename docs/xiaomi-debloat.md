# Debloating the original Mi TV

This launcher was built on a **Xiaomi Mi TV MOOQ0** ("croods", MediaTek, Android TV 10 / SDK 29, 1080p), which was also debloated, on 2026-10-20. Every step was checked with screenshots pulled from the TV. This page records what was done there. The package names are Xiaomi- and MediaTek-specific; on another TV, let an AI agent work out the equivalents with [PROMPT.md](../PROMPT.md).

## What was disabled

Everything was disabled with `pm disable-user --user 0`. Nothing was uninstalled.

| Why | Packages |
|---|---|
| Telemetry | `com.miui.tv.analytics`, `com.xiaomi.statistic`, `com.google.android.feedback`, `com.google.android.tv.bugreportsender` |
| Content / ads / recommendation rows | `com.mitv.tvhome.michannel` (Mi Channel rows), `com.xm.webcontent`, `android.autoinstalls.config.xiaomi.amelie` (pushes app installs), `fusion.android.tv.demo` (store demo), `com.google.android.tvrecommendations`, `com.google.android.leanbacklauncher.recommendations` |
| Background services | `com.android.printspooler`, `com.google.android.leanbacklauncher` (old, unused launcher), `com.mitv.dream` and `com.google.android.backdrop` (unused screensavers) |
| You don't use | `com.amazon.amazonvideo.livingroom` (Prime Video), `com.google.android.videos` (Play Movies), `com.google.android.play.games`, `com.google.android.marvin.talkback`, `com.google.android.katniss` + `com.google.android.tv.assistant` (Google Assistant), `com.xiaomi.mitv.mediaexplorer` |
| Replaced | `com.google.android.tvlauncher` (stock home screen) |

**Re-enabled later:** `com.xiaomi.mitv.tvmanager` (**TV Manager**: Optimize, Memory boost, Cleaner, Deep clean, Uninstall apps). It's in the app grid. It starts at boot and uses Firebase, which is why it was first disabled.

**Kept on purpose:**
- **PatchWall** (`com.mitv.tvhome.atv`): it hosts the Input/Source popup, which TV Home opens for the remote's Input button. It's background-restricted (see Speed tweaks), so its scheduled jobs don't wake it while you watch.
- `com.mitv.videoplayer`: it holds PatchWall's data store.
- **Live TV and inputs:** `com.mediatek.wwtv.tvcenter`, `com.mediatek.tvinput`, `com.mediatek.tv.service`, `mitv.service`.
- **Remote and Bluetooth pairing:** `com.mediatek.hotkey.dispatcher` and `com.xiaomi.android.tvsetup.partnercustomizer`.
- **Keyboard, Play Store and Play Services:** Gboard, Play Store, Play Services.
- **Apps and features you ticked:** YouTube, YouTube Music, Netflix, Mi TV+, Mi Music, Chromecast, screen mirroring (MiPlay/Miracast), Xiaomi system updates, Gallery and Clock.
- **Your own installs:** every app you installed yourself was left alone.

**Side effects:**
- The remote's **Assistant/mic button** does nothing now, because Assistant is disabled.
- If your remote has a **Prime Video button**, it won't open anything.

## Re-enabling an app

**One app**, from the computer:
```bash
adb connect 192.168.0.44:5555
adb shell pm enable --user 0 com.google.android.katniss     # e.g. bring Google Assistant back
```
**One app, on the TV:** Settings → Apps → See all apps → Show system apps → pick it → Enable.

**Everything** (all disabled packages, stock launcher, DNS back to automatic):
```bash
scripts/restore.sh                    # keeps the new launcher installed but not used
scripts/restore.sh --remove-launcher  # also uninstalls it
```
Then press Home.

## Speed tweaks

- **Background-restricted apps:** tonton, iQIYI, Viu, iflix, M3U IPTV and PatchWall can't run services or boot tasks while you're not using them. PatchWall's scheduled jobs used to wake it mid-film (40 % CPU for a few seconds); its input popup still opens. They work normally when you open them, but the first launch is a cold start. The list is in `backup/<serial>/bg-restricted.txt`, and `restore.sh` lifts the restriction.
  ```bash
  scripts/restrict.sh my.com.tonton.screen                                     # restrict one more app (and record it)
  scripts/restrict.sh --recommended                                            # PatchWall, as the setup app does
  adb shell cmd appops set my.com.tonton.screen RUN_ANY_IN_BACKGROUND allow    # undo for one app
  ```
- **Wi-Fi background scanning is off** (`wifi_scan_always_enabled 0`). Wi-Fi works normally. To undo: Settings → Network & Internet → Scanning always available.
- **Other suggestions, not applied:**
  - **Animations 0.5×:** Developer options → Window/Transition/Animator scale. Makes menus and app launches feel snappier.
  - **Weekly reboot:** after a few days the TV collects lost RAM and swap.
  - **Turn off Play Store auto-update.**
  - **Disable Mi TV+** (`com.mitv.tvhome.mitvplus`, Xiaomi's ad-funded live channels) if you don't watch it. It's now in the recommended list: ticked for you in the setup app's **Clean up the TV**, and in `scripts/disable.sh --recommended`.
  - **Disable Android TV Remote Service** (`com.google.android.tv.remote.service`) if you never control the TV from your phone.
  - **Time NetSpeed** (`visnkmr.apps.timenetspeed`) has since been uninstalled.
  - **Task killer** "Background Apps & Process List" (`io.github.visnkmr.bapl`) only runs a moment at boot and when opened. It's optional to remove. Using it to kill apps tends to make the TV slower, because killed apps restart from scratch; TV Manager's Memory boost has the same effect.

## Ad-blocking DNS

Private DNS is set to AdGuard (`dns.adguard-dns.com`). It blocks ad and tracker domains in most apps. YouTube still plays and played fine after the change, but **YouTube ads are not blocked**: they come from the same servers as the videos.

To undo it on the TV, go to Settings → Network & Internet → Private DNS → Automatic. From the computer:
```bash
adb shell settings put global private_dns_mode opportunistic
```
