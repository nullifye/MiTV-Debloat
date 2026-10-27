# Install the launcher from a Mac

This guide builds the launcher on your Mac and installs it on your TV over Wi-Fi, using the scripts in this repository. It takes about 20 minutes the first time, most of it downloading tools.

Other guides: [Windows](install-windows.md) · [Linux](install-linux.md) · [with an AI agent](install-with-ai-agent.md)

**Prefer an app that guides you?** The [setup app](tui.md) finds the TV, installs TV Home, cleans up the TV and can undo everything. One download, nothing else to install.

**No build tools?** Skip the building and [install the ready-made APK](#install-the-ready-made-apk-instead-no-building) from GitHub Releases instead: only adb is needed.

## 1. Prepare the TV

The TV must run **Android TV or Google TV 8.0 or newer**. It was developed and tested on a Xiaomi Mi TV; other Android TV brands should work (see [TV brands](#tv-brands)).

1. **Check the Android version:** Settings → Device Preferences (or System) → About → *Android TV OS version*. It must be 10 or higher.
2. **Turn on Developer options:** on the same About screen, press OK on **Build** (Google TV: *Android TV OS build*) seven times, until it says you're a developer.
3. **Turn on debugging:** Settings → Device Preferences (or System) → Developer options → **USB debugging** on. Some TVs call it *Network debugging*.
4. **Note the TV's IP address:** Settings → Network & Internet → your Wi-Fi network (e.g. `192.168.1.50`).
5. **Keep the remote nearby.** The first time the Mac connects, the TV asks **"Allow USB debugging?"**. Tick *Always allow* and choose OK.

The Mac and the TV must be on the same network.

## 2. Install the tools

You need Git, adb, a JDK (17 or newer) and the Android SDK. With [Homebrew](https://brew.sh):

```bash
brew install git
brew install --cask android-platform-tools temurin@17 android-commandlinetools

# Tell the build where the Android SDK is (zsh is the default shell on macOS)
echo 'export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"' >> ~/.zshrc
source ~/.zshrc

# Accept the SDK licences and install the Android platform the launcher builds against
yes | sdkmanager --licenses
sdkmanager "platforms;android-36"
```

Check everything is there:

```bash
adb version && java -version && echo "$ANDROID_HOME"
```

## 3. Get the code

```bash
git clone https://github.com/nullifye/MiTV-Debloat.git
cd MiTV-Debloat
```

## 4. Connect to the TV

```bash
adb connect 192.168.1.50:5555      # use your TV's IP
```

Accept the prompt on the TV, then run `adb devices`. It should list the TV as `device` (not `unauthorized`).

> **macOS 15 or later:** if `adb connect` says *No route to host* although the TV is on, allow your terminal app in System Settings → Privacy & Security → **Local Network**, then try again.

## 5. Install the launcher

```bash
TV=192.168.1.50 scripts/build-and-install.sh
```

The script checks your tools and the TV, builds the launcher, installs it, gives it the permissions it needs and makes it the home screen. It also records the TV's own launcher so you can go back later. The IP is saved, so later runs are just `scripts/build-and-install.sh`.

Press **Home** on the remote. If the TV's own launcher still opens (Google TV does this), run:

```bash
scripts/build-and-install.sh --disable-stock
```

This disables the TV's own launcher. On Google TV it first disables Google TV's setup app too (`com.google.android.tungsten.setupwraith`), which also answers Home and would otherwise take it. Both are logged, so `restore.sh` turns them back on. The script then presses Home and tells you whether this launcher came up.

On Google TV, casting from your phone (Chromecast built-in) doesn't work while Google TV's own launcher is off. Run `scripts/restore.sh` before a factory reset.

To update the launcher later: `git pull`, then run `scripts/build-and-install.sh` again. Your settings are kept.

## 6. Undo

```bash
scripts/restore.sh                    # back to the TV's own launcher, re-enables anything disabled
scripts/restore.sh --remove-launcher  # ...and uninstalls this launcher
```

## Install the ready-made APK instead (no building)

Don't want to install Java and the Android SDK? Each version of the launcher is published as a ready-made APK on GitHub, and you can install it directly. You only need **adb** on the computer. Every step below is one command typed into **Terminal** (Spotlight: ⌘ Space → type *Terminal*).

**Before you start:** do [1. Prepare the TV](#1-prepare-the-tv) first (Developer options, USB debugging, the TV's IP address).

> **Already installed it by building it yourself?** A downloaded APK and a self-built one are signed with different keys, so the first switch needs one uninstall (Step 6), which resets the launcher's settings. After that, every newer download updates in place and keeps your settings.

### Step 1. Install adb

Install [Homebrew](https://brew.sh) if you don't have it, then:
```bash
brew install android-platform-tools
adb version
```

### Step 2. Download the APK

1. Open [github.com/nullifye/MiTV-Debloat/releases/latest](https://github.com/nullifye/MiTV-Debloat/releases/latest). No GitHub account needed.
2. Under **Assets**, click **`tvhome-<version>.apk`** (for example `tvhome-1.9.apk`). It downloads to your **Downloads** folder.

### Step 3. Go to the APK

```bash
cd ~/Downloads
ls tvhome-*.apk       # shows the file's exact name, e.g. tvhome-1.9.apk
```

In the commands below, replace `tvhome-1.9.apk` with your file's exact name.

### Step 4. Connect to the TV

Replace `192.168.1.50` with your TV's IP address:

```bash
adb connect 192.168.1.50:5555
adb devices
```

The TV asks **"Allow USB debugging?"**: tick *Always allow* and choose OK. Run `adb devices` again: it must show your TV followed by `device` (not `unauthorized`, not `offline`).

Check the TV is new enough:

```bash
adb shell getprop ro.build.version.sdk
```

The number must be **29 or higher** (29 = Android 10).

### Step 5. Write down the TV's own launcher

You need this to undo everything later. Run:

```bash
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

The **last line** is the TV's own home screen, for example `com.google.android.apps.tv.launcherx/.home.HomeActivity` (Google TV) or `com.google.android.tvlauncher/.MainActivity` (Android TV). Copy it into a note. The part before `/` is its **package**, e.g. `com.google.android.apps.tv.launcherx`.

### Step 6. Install the APK

```bash
adb install -r tvhome-1.9.apk
```

Wait for `Success`.
- **It seems stuck?** Look at the TV: some ask you to confirm the install on screen.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`?** A copy you built yourself is installed (a different signing key). Remove it first (its settings are lost), then install again:
  ```bash
  adb uninstall my.rafhan.app.tvhome
  adb install tvhome-1.9.apk
  ```

### Step 7. Give it its permissions

Run these one by one, **right after installing and before pressing Home** (Android 10 only applies storage access when the app starts). A command that prints an error just means that TV refuses it: carry on, only that feature is limited.

```bash
adb shell pm grant my.rafhan.app.tvhome android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant my.rafhan.app.tvhome android.permission.WRITE_EXTERNAL_STORAGE
adb shell appops set my.rafhan.app.tvhome MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant my.rafhan.app.tvhome android.permission.ACCESS_FINE_LOCATION
adb shell appops set my.rafhan.app.tvhome GET_USAGE_STATS allow
adb shell appops set my.rafhan.app.tvhome SYSTEM_ALERT_WINDOW allow
adb shell pm grant my.rafhan.app.tvhome android.permission.DUMP
adb shell pm grant my.rafhan.app.tvhome android.permission.PACKAGE_USAGE_STATS
adb shell pm grant my.rafhan.app.tvhome android.permission.BLUETOOTH_CONNECT
```

| Command | What it's for |
|---|---|
| `READ_` / `WRITE_EXTERNAL_STORAGE` | Phone remote **Files** tab and your own wallpapers (Android 10) |
| `MANAGE_EXTERNAL_STORAGE` | The same on Android 11 and newer ("All files access") |
| `ACCESS_FINE_LOCATION` | Showing the Wi-Fi network's **name** (Android hides it otherwise) |
| `GET_USAGE_STATS` | The **Most used** card and the app grid sorted by use |
| `SYSTEM_ALERT_WINDOW` | Anything shown over other apps: the pairing PIN, messages, the Wi-Fi banner, azan, the info overlay; also lets the phone remote open apps |
| `DUMP` | **Now Playing** card and the **bell**'s Play Store updates |
| `PACKAGE_USAGE_STATS` | The bell's Play Store updates |
| `BLUETOOTH_CONNECT` | Bluetooth alerts in the bell (Android 12 and newer; older TVs print an error, that's fine) |

### Step 8. Let the phone remote press Back and Home

This turns on the launcher's accessibility service, **next to** any that are already on (for example Button Mapper). First see what's on now:

```bash
adb shell settings get secure enabled_accessibility_services
```

- It prints **`null`** (or nothing): run
  ```bash
  adb shell settings put secure enabled_accessibility_services my.rafhan.app.tvhome/my.rafhan.app.tvhome.RemoteAccessibilityService
  ```
- It prints something else, e.g. `flar2.homebutton/flar2.homebutton.a.MyService`: **copy that text, keep it in your note**, and put it in front, joined with a `:`:
  ```bash
  adb shell settings put secure enabled_accessibility_services "flar2.homebutton/flar2.homebutton.a.MyService:my.rafhan.app.tvhome/my.rafhan.app.tvhome.RemoteAccessibilityService"
  ```

Then switch accessibility on:

```bash
adb shell settings put secure accessibility_enabled 1
```

### Step 9. Make it the home screen

```bash
adb shell cmd package set-home-activity my.rafhan.app.tvhome/.MainActivity
```

Now press **Home** on the TV remote. You should see the new launcher. Done!

**Google TV (Sony, TCL, Chromecast…): Home still opens Google TV?** Google TV keeps two apps on Home: its setup app and its own launcher. Disable both (they're only *disabled*, not removed; Step 11 turns them back on):

1. Disable the setup app, then the TV's own launcher (the package you wrote down in Step 5), and set Home again:
   ```bash
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   adb shell cmd package set-home-activity my.rafhan.app.tvhome/.MainActivity
   ```
2. Press **Home**.

While Google TV's launcher is off, casting from your phone (Chromecast built-in) doesn't work. Turn both back on (Step 11) before any factory reset.

**If Home ever shows a setup or recovery screen,** don't reboot. Bring the TV's launcher back:

```bash
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
```

### Step 10. Update to a newer version

Download the newer APK (Step 2), go to it (Step 3), connect (Step 4), then:

```bash
adb install -r tvhome-2.0.apk
```

That's all: your settings, permissions and Home choice are kept.

### Step 11. Undo

Using what you wrote down in Steps 5 and 8:

```bash
adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb uninstall my.rafhan.app.tvhome
```

- Use **your** launcher from Step 5 in the `pm enable` and `set-home-activity` lines. The first line is only for Google TV; elsewhere it prints an error, which is fine.
- Accessibility: put back what Step 8 printed (if it was `null`, run `adb shell settings delete secure enabled_accessibility_services` instead):
  ```bash
  adb shell settings put secure enabled_accessibility_services "flar2.homebutton/flar2.homebutton.a.MyService"
  ```

Then press **Home**, and turn **USB debugging** off (next section).

## 7. Turn off debugging when you're done

Network debugging on port 5555 isn't encrypted: anyone on your Wi-Fi could control the TV. When you're finished, turn **USB debugging** off in Developer options.

With debugging off, a few extras are limited: the phone remote can't move the cursor or type inside other apps, Now Playing shows no cover art, and the running-apps screen can't show memory or close apps that are playing. Everything else keeps working.

## TV brands

| TV | Notes |
|---|---|
| Xiaomi Mi TV / Mi Box | Developed and tested on a Mi TV. |
| Google TV (Chromecast with Google TV, newer Sony, TCL, Hisense) | Home keeps opening Google TV: use `--disable-stock`. Some only offer *Wireless debugging* (see below). |
| Other Android TV (Sony, Philips, TCL, Sharp, Nvidia Shield…) | Should work. If the TV refuses a permission, the script says so and only that feature is limited. |
| Samsung (Tizen), LG (webOS), Roku, Amazon Fire TV | Not supported: they don't run Android TV, or don't allow another home screen. |

**Wireless debugging** (some Android 11+ and Google TV sets): Developer options → Wireless debugging → *Pair device with pairing code*. On the Mac run `adb pair <ip>:<pairing port>` and type the code, then `adb connect <ip>:<port>` using the port shown on the Wireless debugging screen, and run the script with `TV=<ip>:<port>`. The port changes after the TV restarts.

## Troubleshooting

| Problem | Fix |
|---|---|
| `unauthorized` | Accept "Allow USB debugging?" on the TV. If it never appears, turn USB debugging off and on, run `adb kill-server`, and connect again. |
| `can't reach the TV` / *No route to host* | Check the IP and that both are on the same Wi-Fi. Run `adb kill-server` and try again. On macOS 15+, allow Local Network access (step 4). |
| `JDK 17 or newer is needed` | Install `temurin@17` (step 2), open a new terminal and try again. |
| `Android SDK not found` | Set `ANDROID_HOME` (step 2) and open a new terminal. |
| Build fails about licences or a missing platform | Run `yes \| sdkmanager --licenses` and `sdkmanager "platforms;android-36"`. |
| `needs Android 10` | The TV is too old for this launcher. |
| Home opens the old launcher | Run the script with `--disable-stock`. |
