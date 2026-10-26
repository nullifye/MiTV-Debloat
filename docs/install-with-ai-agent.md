# Install with an AI agent

An AI coding agent that can run terminal commands on your computer can do the setup for you: install the tools, connect to the TV, run the scripts and fix problems along the way. It works the same on Windows, macOS and Linux. You stay in charge: the agent asks before installing or changing anything, and you approve each step.

Manual guides: [Windows](install-windows.md) · [macOS](install-macos.md) · [Linux](install-linux.md)

## What you need

- **An AI coding agent with terminal access:** one that runs in your terminal, desktop or code editor and can execute commands on your computer. A chat assistant in a web browser can't reach your TV.
- **The TV prepared:** Developer options and USB debugging on, and its IP address noted. Follow step 1, *Prepare the TV*, in any of the manual guides. The TV must run Android TV or Google TV 8.0 or newer.
- **The computer and the TV on the same Wi-Fi**, and the TV remote nearby: the TV asks you to allow the computer the first time.

## Option A: install this launcher (quick)

Uses this repository's scripts. Takes about 20–30 minutes, mostly downloads.

1. Get the repository: clone `https://github.com/nullifye/MiTV-Debloat`, or ask the agent to clone it for you.
2. Start the agent in the `MiTV-Debloat` folder.
3. Fill in the placeholders and paste this:

```text
Install the launcher from this repository on my Android TV.

- TV: <brand and model> at <TV IP address>
- This computer: <Windows / macOS / Linux>

Steps:
1. Read README.md and the install guide for my system in docs/ (install-windows.md,
   install-macos.md or install-linux.md), and follow it.
2. Check which tools are missing: Git, adb, JDK 17 or newer, and the Android SDK with
   "platforms;android-36" and ANDROID_HOME set. Tell me what you'll install and wait for my
   OK before installing anything. On Windows, run the scripts in Git Bash.
3. Connect with `adb connect <TV IP>:5555`, and wait while I accept "Allow USB debugging?"
   on the TV.
4. Run `TV=<TV IP> scripts/build-and-install.sh` and show me the output. If it stops with an
   error, explain it and fix the cause. Don't work around the script's checks.
5. Ask me to press Home on the remote. If the TV's own launcher still opens, ask me before
   running the script again with --disable-stock.
6. Don't disable, uninstall or change anything else on the TV.
7. At the end, tell me how to undo it (scripts/restore.sh) and remind me to turn USB
   debugging off.
```

## Option B: debloat the TV and build the launcher from scratch

Paste [PROMPT.md](../PROMPT.md) into the agent instead. The agent will:
- back up the TV's settings and app list
- ask which apps you use and want in the dock
- disable unwanted preinstalled apps in small batches, checking the TV after each batch
- write and build a launcher like this one

Expect an hour or more, and several questions. Every change is logged and can be undone with the restore script it writes.

## Tips

- **Read commands before you approve them**, especially `pm disable-user`, `pm uninstall` and `settings put`. This project only ever *disables* apps (they can be re-enabled). It never uninstalls system apps.
- **If it can't connect:** check the TV is on and on the same Wi-Fi, and accept the prompt on the TV. Then ask the agent to run `adb kill-server` and try again.
- **The agent can't see the TV screen** unless it takes a screenshot (`scripts/shot.sh name`). Tell it what you see when something looks wrong.
- **Keep the `backup/` folder.** It has one folder per TV, named after its serial number, and `scripts/restore.sh` reads the TV's folder to put everything back.
- **Turn USB debugging off when you're done.** Network debugging isn't encrypted, so anyone on your Wi-Fi could control the TV.
