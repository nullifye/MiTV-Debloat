# How the launcher works

Every feature in detail: what it shows, how to use it, and how it gets its data. For installing, see the [install guides](../README.md#install).

## Overview

- **Dock:** Netflix · YouTube · TV Manager · Gallery · Play Store by default (change it from the TV or phone, see [Change the dock](#change-the-dock)). The focused app's name appears large above the dock, with a glow in a colour taken from its banner.
- **Top bar** (round icon buttons):
  - **Bell** shows notifications (see below).
  - **Inputs** opens a dropdown with HDMI 1–3, AV and Live TV. It switches through the TV input framework.
  - **The remote's Input button** works again. The TV's hotkey dispatcher opens one named screen of the stock TV launcher for it, so with that disabled the button did nothing. TV Home's accessibility service (the phone remote's, already on) now catches that one key, letting every other key through untouched, and opens the TV's own input picker when it has one (PatchWall's on Xiaomi), otherwise this dropdown (`InputsActivity`).
  - **Wi-Fi** shows the connection state by colour and opens Wi-Fi settings.
  - **Settings** (gear) opens the launcher's own Settings (see below); its last row, **About → TV settings**, opens the TV's Settings.
- **Remote:**
  - Down from the dock slides up to all other apps, as many per row as the dock holds (5; 6 or 7 in **Settings → Home screen → Apps per row**). The tiles shrink so a row always fills the same width. Up/Down always move exactly one row.
  - Up from the dock goes to the top bar. Up from the top bar slides down the running-apps screen (see below).
  - Ends of the top bar and rows don't wrap.
  - Back returns to the dock.
  - Home while already home jumps back to the dock.
- **Focus memory:** the last focused tile is remembered, even if the TV kills the launcher.
- **Any screen size:** the layout is designed for 960 × 540 dp (1920 × 1080 at density 320). On other TVs the launcher picks the density that keeps those proportions, so a 720p box or a denser screen shows the same layout scaled to fit, not shrunk or overlapping (`UiScale.java`).
- **Resource use:** about 45–75 MB of memory (see the benchmark). There is no blur, video or network polling; the clock updates from the system's minute tick. (The Globe screen saver fetches its live data only while it's on screen.)
- **New apps:** they appear automatically; the grid lists your most used apps first, then the rest A–Z.

## Date, Hijri date and prayer times

The top left shows the big clock with the date and Hijri date stacked beside it, level with the pills. Below it, the prayer card's top lines up with the system card's. It shows today's Fajr, Dhuhr, Asr, Maghrib and Isya from JAKIM e-solat. The next prayer is in gold, with a countdown in the card's header. After Isya it shows tomorrow's Fajr.

- **Fetching:** the whole month is fetched once from `esolatApi/takwimsolat&period=month&zone=SGR03` and cached on the TV. It's fetched again only when the month changes. If there's no network, it retries when the network comes back, at most once a minute. The display updates on the clock's minute tick.
- **Azan:** at each of the five prayer times the launcher plays the azan (`res/raw/azan.mp3`, bundled in the APK) and slides in a banner like "Azan Maghrib · 19:01" over any app, like the Wi-Fi banner. Apps that support it (Netflix, YouTube) pause and resume afterwards. It only plays while the screen is on and within 2 minutes of the time, and not until the clock is set after a power cut. Switch it off in **Settings → Prayer times → Azan**; **Play azan now** plays it straight away to check the volume. A silent reminder banner ("Maghrib in 5 min · 19:01") also comes before each prayer; set how early (Off, 5, 10, 15 or 30 min) in **Settings → Prayer times → Reminder before prayer**. To use another recording, replace `azan.mp3` and rebuild.
- **Zone:** pick it in **Settings → Prayer times → Zone** (all 60 JAKIM zones, from e-solat.gov.my). The card switches straight away and fetches that zone's month. `prayer_zone` in strings.xml is only the default.

## Notifications (bell)

The top bar is four round icon buttons: **bell**, **Inputs**, **Wi-Fi**, **Settings**.
- **Wi-Fi:** the button shows the connection state by its colour (green / amber / red). The network name is on the System card's IP line.
- **Bell:** gets an amber count badge when something new arrives. **OK** shows the newest 5. OK on one opens it and clears it, and **Clear all** empties the list.

| Source | How it's detected | OK opens |
|---|---|---|
| TV Home updates | A newer release on GitHub (`releases/latest`, so never a draft or pre-release), checked a minute after the launcher starts and every 6 h. A banner shows once per version. | The update: TV Home downloads the release APK, checks its size and SHA-256, and opens Android's installer. Choose **Update**: settings are kept, because Android only accepts an update signed with the same key. It needs "install unknown apps" for TV Home: the setup app and `build-and-install.sh` allow it, otherwise TV Home asks once. |
| Play Store updates | One alert per app with an update waiting. The Play Store rarely posts its "updates available" notification on a TV, but every update check writes each pending app to the system log (`Finsky: UCtl: For package <app>, first update discovery…`). The launcher reads those lines at start, every hour and right after any app is updated, and keeps its own list, because the log only holds a day or two. An app leaves the list once it's updated, disabled or removed. Reading the log needs `READ_LOGS` (granted by the setup app and `build-and-install.sh` below Android 13; Android 13+ asks on screen whenever an app reads the log, so there it's read over the launcher's own ADB connection while USB debugging is on). Without either, it falls back to the Play Store's own notification, read from `dumpsys notification`. | That app's Play Store page |
| New HDMI device | HDMI-CEC devices (child inputs under an HDMI port) that appear after start-up. Needs a CEC-capable device. | That HDMI input |
| Bluetooth | A device connecting, or staying disconnected for 2 min. The TV remote and quick reconnects are ignored. | Bluetooth settings |
| Low memory | RAM use above 85 %, checked every minute while the screen is on. Re-arms below 80 %. | TV Manager |

Device and memory alerts, and a new TV Home version, also slide in a short banner over any app. The list is kept in memory, so it starts empty after the launcher restarts.

## Connection indicator

- **The Wi-Fi button shows the state** (icon only; the name is on the System card):

  | State | Icon | Text |
  |---|---|---|
  | Connected | green Wi-Fi | network name |
  | Connected, no internet | amber Wi-Fi with a warning | **No internet** |
  | Disconnected | red crossed-out Wi-Fi | **Disconnected** |

- **Banner:** whenever the state changes, a small banner slides in at the top of the screen for a few seconds, **over any app**. It reads "Wi-Fi connected · name", "name · no internet" or "Wi-Fi disconnected". It's shown by the always-running phone-remote service and uses the overlay permission.
- **"No internet"** uses Android's own internet check. It also appears behind a captive portal, and when Private DNS can't be reached.
- **Settling:** changes are settled for 3 s, and a fresh connection gets 10 s to pass the internet check, so a normal reconnect doesn't flash "no internet".

## Info overlay over apps

A thin bar over every other app: **network speed** (`↓ 1.2 Mb  ↑ 34 Kb`), **uptime** (`23h6m`, or `2d1h55m`), a **clock** with seconds (`15:05:36`, always 24-hour) and **elapsed time** since you left the home screen (`04:07`, or `1:04:07` past an hour; it starts again when you go back home or the screen turns off). Turn it on in **Settings → Info overlay → Show over apps**; it's off by default.

- **Never on the home screen** (which has its own clock and System card), and not while the screen is off. It comes back as soon as an app is in front.
- **Options:** what the left, centre and right show (any of the four, or nothing), top or bottom of the screen, text size (Tiny 10 / Small 12 / Medium 14 / Large 18), and a dark see-through background. The phone remote's Settings tab has the same options.
- **Speed** is all traffic in and out of the TV (`TrafficStats`), in decimal bits per second, updated every 2 s.
- **Cost:** it ticks once a second only while it's showing. On the home screen, with the screen off or switched off, nothing runs.
- It can't be focused or touched, so it never takes a key press from the app underneath.

## Screen savers: Prayer time, Live log and Globe

Three screen savers, all on black and moving so nothing burns in. Everything about them is in TV Home's **Settings → Screen saver** (under Prayer times):

- **Screen saver:** on or off. Off means the TV never starts one.
- **Show:** OK cycles Prayer time → Live log → Globe → every other screen saver on the TV (A–Z); the row shows the one in use.
- **Start now:** a preview of the chosen one; any key ends it (on the Globe, any key but the arrows). It works even with the screen saver off: it's switched on just for the preview and back off when you return.
- **With Globe chosen:** a switch for each of its layers, listed under [Globe](#globe) below.

Some TVs, like this Mi TV, can't open their screen-saver page from another app, so TV Home sets these itself. That needs `WRITE_SECURE_SETTINGS`, granted by the setup app and `build-and-install.sh`. Without it, the page offers **Choose a screen saver** (opens the TV's Settings) and **Start now**. The TV's own Settings → Device Preferences → Screen saver still works too.

- **Prayer time:** a big clock with seconds, the date, the Hijri date and today's five prayer times, each with its icon from the prayer card, the next one in amber. The Hijri date and times come from the prayer-times data, so they match the home screen. The whole block moves to a new spot every minute, fading out and in.
- **Live log:** the TV's log, live. Newest lines at the bottom, one per line in a small monospace font: time, level, tag and message. Errors are dim red, warnings dim amber, everything else grey. New lines glide in from below (faster when many arrive at once) rather than jumping. A small clock with seconds sits near an edge. Every minute the log block slides to a new offset (up to 24 dp either way) and the clock jumps to another edge.
  - **When the log can't be read** (no `READ_LOGS`, and USB debugging off), it shows the Prayer time picture instead, and tries the log again every 30 s.
  - It reads the log like the bell's Play Store updates: with `READ_LOGS` below Android 13, else over the launcher's own ADB connection while USB debugging is on.
  - Everything in the log appears on screen, which can include Wi-Fi names or account names.
- **Against burn-in:** nothing is white, the screen is dimmed, and the picture keeps moving even when nothing changes.
- Any key wakes the TV (on the Globe, any key but the arrows).

### Globe

<img src="screensaver-globe.jpg" width="100%" alt="Globe screen saver: the Earth with day and night, the prayer lines, Makkah and your zone, live map edits and earthquakes, the space stations and Hubble, a legend and the latest edit and earthquake">

The Earth as it really sits in space right now, filling 90 % of the screen's height, with what's happening on it live.

- **True to the moment:** the Earth is turned to its real rotation (sidereal time), its axis tilted 23.44° against its orbit (towards the sun in June, away in December), ecliptic north up, and lit by the sun where it really stands. The viewpoint circles it once every 2 minutes, westward, so the land passes west to east. Or, with **Turning: Real time** (Settings → Screen saver), the viewpoint stays still and the Earth turns at its own speed, once a day (about a quarter of a degree a minute), with the stars standing still behind it. There the Sun stands still too, so against burn-in the viewpoint wanders slowly (±20° north and south over about 1½ hours, ±8° sideways over 4 hours), carrying the lit half and the sun glint across about a tenth of the screen; sideways it's slower than the Earth's own turning, so the Earth never looks to turn backwards. It starts facing your prayer zone either way.
- **The sky:** instead of plain black, the Sun where it really stands (in line with the light on the globe, so it hides behind the Earth when you face the night side), the Moon where it really is and in tonight's phase (full, new, crescent or quarter as seen from the Earth, its lit side towards the Sun, its dark side faintly there; it can even cover the Sun in an eclipse), the real stars to magnitude 6.5 (the naked-eye limit) and the 109 Messier galaxies, nebulae and clusters, placed from their catalogued positions and turning with the camera's inertial view. Dim, and nothing white, for burn-in: the Sun is a warm amber disc, the Moon grey. The catalogues aren't in the app: the first time the Globe runs with Sky on, the TV downloads HYG and OpenNGC (about 17 MB, once), keeps just those 9,029 objects (a 0.3 MB file) and draws the stars from then on, offline too. The Sun and the Moon need no download: they're worked out on the TV (the Moon with the Astronomical Almanac's series, within about 0.2°).
- **The maps:** NASA's Earth by day (Blue Marble) and at night (city lights), 1920 × 960, downloaded once from Wikimedia Commons (about 0.5 MB). Until they're in (first run, or offline), the Prayer time picture shows instead and the globe takes over once they arrive.
- **Satellites:** the ISS, Tiangong and Hubble move at their calculated orbital height: in front of the Earth, briefly beyond its rim, then hidden behind it. Their fading trails follow the same paths.
- **The remote:** the arrows turn it. Left and right go round it, up and down tilt it (up to 70°) to see more of the north or south. A press moves it about 10°, and holding keeps it going. Ten seconds after the last arrow it turns on its own again and levels out. Any other key ends the screen saver; it ends on the key's release, so the key never reaches the app underneath. A hint in a top corner says so for the first 8 seconds.
- **The text:** a small clock in a top corner; under it in that bottom corner the legend (each layer's line, dot or icon, with live counts: "52 map edits a minute", "35 earthquakes today"); in the other bottom corner the latest map edit (green dot, how long ago, its comment in quotes), the latest earthquake (orange ring, how long ago, magnitude and place) and the credits. The two sides swap every minute.
- **Against burn-in:** the globe slides to a new offset (up to 24 dp) every minute (and in Real time the viewpoint also wanders slowly, see above), the text swaps sides, the screen is dimmed and nothing is white (the brightest, the sun glint, stays near 65 %).
- **What it costs:** about 12 % of one CPU core on the Mi TV (about 3 % of its four), about 19 % with Wind on. It's drawn with OpenGL ES 2.0, which every Android TV has: one square around the globe whose shader turns each pixel into a spot on the sphere, so there's no 3D model.

Each layer has its own switch (Settings → Screen saver, with Globe chosen). Nothing is fetched for a layer that's off, and everything is fetched only while the Globe runs, then kept in the app's files for offline use.

| Switch | What it shows | From | Fetched | Starts |
|---|---|---|---|---|
| Prayer lines | Where it's Maghrib right now (amber: the sun at −0.833°, on the side where it's setting) and Fajr (teal: −18°, where it's rising; JAKIM's Subuh times put the sun about 18° down) | Worked out on the TV | – | On |
| Makkah and qibla | A Kaaba icon on Makkah and a dotted great-circle arc to it from your zone | – | – | On |
| Your zone | A person icon on your prayer zone's main town (Settings → Prayer times → Zone) | – | – | On |
| City lights | The night side's lights | NASA | Once | On |
| Sun glint | The sun's reflection on the sea, where it would mirror towards you; none on land, ice or cloud | Worked out on the TV | – | On |
| Sunlit rim | The atmosphere's edge, brighter and a little warm on the sun's side | Worked out on the TV | – | On |
| Snow and sea ice | A soft white where there's snow or sea ice now (2–3 days behind) | NASA GIBS: SMAP snow, GHRSST sea ice | Every 12 h, ~100 KB | On |
| Sky | The Sun and the Moon (in its real phase) where they stand now, real stars to magnitude 6.5 and the 109 Messier objects, dimmed for burn-in | The Sun and the Moon worked out on the TV; stars from HYG v3.8 and OpenNGC | Once, ~17 MB (0.3 MB kept) | On |
| Clouds | Clouds by day; at night they hide the city lights | [Live Cloud Maps](https://clouds.matteason.co.uk), from EUMETSAT's satellites | Every 3 h, ~390 KB | Off |
| Wind | 900 drifting streaks, faster and brighter where the wind is stronger (10 m above the ground) | NOAA GFS, 1°, the forecast step nearest to now | Every 3 h, ~160 KB | Off |
| Aurora | A green glow on the night side where it's forecast | NOAA SWPC OVATION | Every 15 min, ~150 KB | On |
| Map edits | A green dot for each OpenStreetMap edit saved in the last minute, appearing through the minute and fading over 3 minutes | OpenStreetMap minutely changesets | Every minute | On |
| Earthquakes | Orange rings for the past day's M2.5+ quakes, sized by magnitude (7 dp at M2.5 to 30 dp at about M7); the last hour's pulse | USGS | Every 5 min | On |
| ISS, Tiangong, Hubble | Each with its icon and its path over the last 10 minutes | CelesTrak's orbits; positions worked out on the TV | Every 12 h | On |
| Legend | The key, the latest edit and the latest earthquake | – | – | On |

- **Satellites:** CelesTrak publishes each satellite's orbit (two-line elements); `Orbit.java` works out where it is with SGP4, the model those are made for. Tested against the reference implementation: within 10 m for the ISS and Tiangong.
- **Wind:** NOAA sends GFS as GRIB2; `Grib.java` reads it (the complex packing NOMADS uses). Its values matched Open-Meteo's GFS within 0.2 m/s. If the newest run isn't out yet, it takes the one before.
- **Credits** (shown on screen too): Earth, snow and ice: NASA · Clouds: contains modified EUMETSAT data · Aurora and wind: NOAA · Map edits © OpenStreetMap contributors (ODbL) · Earthquakes: USGS · Orbits: CelesTrak · Stars: HYG Database (CC BY-SA 4.0) · Deep sky: OpenNGC (CC BY-SA 4.0).

## Now Playing

Under the System card, in the Most used card's place (which comes back when playback stops), whenever an app has a media session (RadioStream, Spotify, music apps):
- **Shows:** cover art, title, artist, the app and its state (Playing / Paused).
- **Progress:** a progress bar with time when the track has a length; **LIVE** for streams.
- **Buttons:** **Play/Pause**, **Stop** and **Open app**. Press **Up** from the dock to reach them, Left/Right to move between them, Up again for the top bar.
- **How it reads the session:** this low-RAM TV refuses the permission apps normally use to read other apps' media sessions (notification-listener access). So:
  - **Detecting playback:** every 4 s the card asks Android whether any audio is playing, which is nearly free. Only if something is playing does it read `dumpsys media_session` (the `DUMP` permission, granted by `build-and-install.sh`). That gives the app, title, artist, state and elapsed time.
  - **USB debugging on:** once a session is found, a small helper (`MediaHelper`) starts as the shell user over the launcher's loopback ADB connection and reports the session every 2 s, with cover art, track length and position. It costs about 23 MB and stops when playback ends.
  - **USB debugging off:** no cover art or progress bar, so the app icon stands in.
  - **When it runs:** all of this only while the home screen is up and in focus. It stops under the screensaver.
- **Apps that pause when hidden** (YouTube, Netflix) don't show up while you're on the home screen, because their session ends.

## Running apps (Up from the top bar)

Press **Up** on the top bar and the home screen slides away. A screen with three borderless columns comes down from above. Column 1 lists the running apps; columns 2 and 3 are empty for now.

- **What's listed:** apps you can launch that have a process right now (from `dumpsys activity lru`, which uses the `DUMP` permission). Each row shows the icon, name, memory and state: **On screen**, **Service** (playing or running a foreground service), **Recent** or **Cached**. The most active apps come first, then the biggest.
- **Left out:** the launcher itself, and apps with an enabled accessibility service (Button Mapper). Closing Button Mapper would stop the remote's remapped buttons.
- **Remote:** Up/Down move between rows. **OK** force-closes the focused app (the round ✕ button turns amber on the focused row). Down past the last row, or **Back**, returns to the top bar.
- **Hold OK** (or Menu) on an app: **Hide from this list**, **Force close** or **Open**. Hidden apps aren't offered for force close. They're kept under a **Hidden apps (n)** row at the bottom of the list (only while one of them is running): OK on it shows them, and OK on a hidden app gives **Show in list again**. The hidden list is saved on the TV and survives restarts and reinstalls (not a clean uninstall).
- **USB debugging on:** force close runs `am force-stop` over the launcher's loopback ADB connection, so it closes anything, including apps that are playing. Memory (resident size, from `/proc/<pid>/status`) is read the same way.
- **USB debugging off:** the launcher can only use Android's background kill (`KILL_BACKGROUND_PROCESSES`). It can't stop apps that are on screen or running a service, and the screen says so. Memory isn't shown.
- **Refresh:** the list is read when the screen opens and after each close. Nothing runs while the screen is closed.

## Settings (gear)

The gear at the end of the top bar opens the launcher's Settings: categories on the left, their options on the right. Left/Right switch sides, OK changes the focused option (switches flip, choices cycle, ›-rows open a list), Back steps out (list → options → categories → home). Changes apply straight away and are kept in the launcher's `settings` prefs. Everything starts as the launcher behaved before Settings existed.

| Category | Options |
|---|---|
| Home screen | Clock Auto / 12-hour / 24-hour, apps per row (5, 6 or 7: the dock and the app list), System card, Now Playing card (the Weather card is under Weather), wallpaper dim (Off/Low/Medium/High), wallpaper (Bing daily photo, seven built-in photos, your pictures or none). A hidden card's column closes up. |
| Prayer times | Zone (picker), prayer card on/off, azan on/off, reminder before prayer (Off / 5 / 10 / 15 / 30 min), play azan now |
| Weather | Weather card on/off, location (Auto or any of MET Malaysia's 440 places), air quality station (Nearest or any of the 68 APIMS stations) |
| Most used | Most used card on/off, week starts on (Monday / Sunday), leave out (app picker) |
| Screen saver | On/off, which one (OK cycles Prayer time, Live log, Globe and the TV's others), Start now (preview); with Globe chosen, a switch for each of its 16 layers (clouds and wind start off) |
| Notifications | TV Home updates, Play Store updates, new HDMI devices, Bluetooth, connection status, low memory (each on/off), low-memory level 80/85/90 % |
| Info overlay | Show over apps on/off (off by default), what the left, centre and right show (network speed / uptime / clock / elapsed time / nothing), position (top / bottom), text size (small / medium / large), background on/off |
| Phone remote | Remote on/off (the banner and bell keep working when it's off), its address, forget all paired phones (press OK twice), and each paired phone with when it was last used and its own Forget (OK twice) |
| Running apps | Apps hidden from the running-apps screen; OK shows one again |
| About | Version, install time, APK size, TV settings |

## Weather

Under the prayer card (its title has a ring sun, apart from the prayer card's solid one): today's weather and two air-quality readings for your place.

```
☀ WEATHER · KLANG                    ⚠ Thunderstorms warning until 20:00
⛈ 24–32°C                    API 75 MODERATE        AQI 153 UNHEALT…
☀ MORNING ⛈ EVENING ☀ NIGHT  ↑ Rising · +2 in 3 h   PM2.5 39 · Haze 0.78
MET · 17:20                  APIMS · 17:00          COPERNICUS · 17:00
```

The band word sits beside the number; a long one that doesn't fit ends in "…".

| Part | What it shows | From | Fetched |
|---|---|---|---|
| Weather | The title with the place and today's outlook in words ("Thunderstorms in places, afternoon"); today's low and high from MET (°C; not a "feels like"); an icon each for the morning, evening and night (no rain, cloudy, rain, thunderstorms); when the forecast was fetched | MET Malaysia's forecast, through data.gov.my's open API (no key) | Hourly (MET puts it out daily and sometimes revises it in the day) |
| Warning | In amber on the title row, instead of the outlook, while a MET warning (thunderstorms, heavy rain…) names your place: "⚠ Thunderstorms warning until 20:00". MET lists districts ("Selangor (Sepang, Kuala Langat, Klang and Petaling)"), so with Location on Auto the prayer zone's areas count too. Sea warnings aren't shown. | MET Malaysia's warnings, through data.gov.my | Every 15 min (MET issues them whenever needed) |
| API | The Air Pollutant Index at the station, its band in the band's colour (Good ≤50 blue, Moderate ≤100 green, Unhealthy ≤200 yellow, Very unhealthy ≤300 orange, Hazardous red, as soft pastels), whether it's rising or falling (the reading shown against the one 3 hours before: "↑ Rising · +2 in 3 h" in amber, "↓ Falling" in green, "→ Steady" for a move under 2), and the reading's hour | The DOE's APIMS: every station's latest hourly reading, from the same map service the APIMS portal draws from, and the station's last 24 hours (for the trend, about 6 KB) | Once an hour, when the new reading is out (from about 10 past; tried again every 10 min until it is) |
| AQI | The US Air Quality Index for the station's spot and its band (≤50 green, ≤100 yellow, ≤150 orange, ≤200 red, ≤300 purple, above maroon, as pastels); PM2.5 (µg/m³) and haze: the aerosol optical depth, how much the air's particles dim the sunlight (about 0.1 clear, 0.5 hazy, 1 or more thick haze); the model's hour | The EU's Copernicus Atmosphere Monitoring Service (CAMS) model, through Open-Meteo (no key) | Once an hour, at the start of each hour (its values are hourly) |


- **The two numbers use different scales.** Malaysia's API and the US AQI weigh PM2.5 differently, and the US scale is stricter, so the same air often reads higher there (e.g. API 74 Moderate, US AQI 144). The API is measured at the station; Copernicus is a forecast model for the same spot, a second opinion rather than a duplicate.
- **Where:** Settings → Weather. **Location** is Auto by default: the prayer zone's town matched to MET's places (SGR03 → Klang), else its state's forecast (for islands, peaks and whole-state zones). **Air quality station** is Nearest by default: the APIMS station closest to the prayer zone's town (SGR03 → Klang). Both lists download when first opened.
- **The forecast text** is MET's own (in Malay), put into English: "Ribut petir di beberapa tempat" → Thunderstorms in places; a phrase it doesn't know yet shows as MET wrote it.
- **Offline:** everything is kept in `files/weather/` and shown from there straight away. A reading over 3 hours old turns grey, and a part that has never loaded shows "–".
- **APIMS has no published API:** the card reads the APIMS portal's own map service (`eqms.doe.gov.my/api3/publicmapproxy/…/CAQM_MCAQM_Current_Reading`). If the DOE changes it, the API part shows "–" until the launcher is updated; the rest keeps working.
- **Credits:** Weather: MET Malaysia (data.gov.my) · Air Pollutant Index: Department of Environment Malaysia (APIMS) · Air quality: Copernicus Atmosphere Monitoring Service, via Open-Meteo (CC BY 4.0).

## Most used apps

- **The card:** under the System card (Now Playing takes its spot while something plays), it shows your top 5 apps this week, each with its time and a bar, plus the total. The week starts on Monday, or Sunday if you pick it in **Settings → Most used → Week starts on**, and counts up to today; it starts again from zero on that day each week.
- **The grid:** most used first by **all-time** use (since the launcher started counting), every app included, even the ones left out of the card. Apps never used stay A–Z after them. The phone remote's app list uses the same order. The card and the grid refresh every time you come back to the home screen.
- **How time is counted, per day:**
  - **Visible time.** This counts watching HDMI or tuner through Live TV, which Android barely counts as "foreground".
  - **Background playback (e.g. RadioStream audio)**, but only on days the app was also on screen. On this TV a group of always-on services, such as an overlay app, MiLink and Live TV's service, log identical whole-day service times that aren't real use.
  - **On Android 8 and 9** (e.g. an older Mi Box), Android only reports foreground time, so that's what counts. Background playback isn't counted there.
- **Excluded apps:** Live TV (`com.mediatek.wwtv.tvcenter`) is left out of the ranking. It's only the screen for HDMI, AV and the antenna, so its time is input-watching time, not app use. It isn't on the card or in its total. Leaving an app out only affects the card: the grid still ranks it by its use. To leave out more apps, tick them in **Settings → Most used → Leave out** (`usage_excluded` in apps.xml is only the default).
- **Usage access:** granted by `scripts/build-and-install.sh` with `adb shell appops set my.rafhan.app.tvhome GET_USAGE_STATS allow`. Without it the card shows that command and the grid stays A–Z.
- **History:** the launcher keeps its own copy of each day's times for the last 7 days (`files/usage-history.json`), which always includes the week's first day whichever day it starts, because this TV has no clock battery: after a power cut it boots thinking it's 1974 until internet time fixes the clock, and that jump pushes Android's own usage history out of the week. The copy is only saved while the clock is right, and each day counts whichever is larger, the copy or Android's figure. Standby doesn't affect any of this; only a real power-off does.
- **All-time total (grid order):** when a day leaves the 7-day copy, its times are added to `files/usage-alltime.json`. The grid ranks by that total plus the days still in the copy, so it survives reboots and power cuts. It starts from zero: only use the launcher itself counted.

## System panel

The card sits under the pills, starting at the Inputs pill. It shows:

| Row | Cells |
|---|---|
| 1 | CPU clock (average of the cores), RAM used/installed (2 GB; the ~250 MB the firmware reserves for video counts as used), storage used/total for the whole 16 GB chip as in Settings (system partitions count as used), uptime |
| 2 | IP address, Wi-Fi signal (dBm and link speed), Private DNS (Android 9+), USB debugging |

- **Bars:** RAM and storage turn amber over 80% and red over 92%.
- **USB debugging:** shows amber **ON :5555** while network ADB is open, and green **OFF** once you switch it off. It updates the moment the setting changes.
- **CPU:** this firmware blocks apps from reading true CPU load, and the TV has no temperature sensor service. `/proc/stat`, `/proc/loadavg` and the thermal zone are all denied by SELinux, and the thermal HAL is absent. So the card shows the cores' current clock instead. The `ondemand` governor raises it under load. For real CPU % use `adb shell dumpsys cpuinfo`.
- **Refresh:** CPU, RAM and uptime refresh every 2 s, only while the home screen is on screen; the card stops sampling when the app grid or another app is open. Storage refreshes every 30 s. Network and USB values only change on events.
- **Code:** the card lives in `launcher/app/src/main/java/my/rafhan/app/tvhome/SystemPanel.java`.

## Phone remote

Open **http://\<TV IP\>:8486** on your phone, on the same Wi-Fi (Settings → Phone remote on the TV shows the exact address). For **full screen**: on Android, tap the TV's name at the top (it hides the address bar; tap it again to leave, and it's remembered after a reload). On iPhone, use **Share → Add to Home Screen** and open it from there: it opens full screen, like an app.

<table>
  <tr>
    <td align="center" width="33%"><img src="tvremote.jpg" alt="Remote tab: touchpad, Back, Home, Play/Pause, volume, screenshot, keyboard"></td>
    <td align="center" width="33%"><img src="tvremote-tv.jpg" alt="TV tab: Now Playing, prayer times, system, power, inputs, send to TV"></td>
    <td align="center" width="33%"><img src="tvremote-applist.jpg" alt="Apps tab: running apps with Hide and Close, then the dock"></td>
  </tr>
  <tr>
    <td align="center"><b>Remote</b>: swipe to move, double-tap for OK, plus Back, Home, media, volume, typing and screenshots</td>
    <td align="center"><b>TV</b>: what's playing, prayer times, system, power and sleep timer, inputs, and Send to TV</td>
    <td align="center"><b>Apps</b>: running apps, the dock, then every app with the most used first. Hold one for more</td>
  </tr>
  <tr>
    <td align="center"><img src="tvremote-sendfile.jpg" alt="Files tab: TV storage and the Wallpapers shortcut"></td>
    <td align="center"><img src="tvremote-settings.jpg" alt="Settings tab: clock, cards, wallpaper dim and wallpaper thumbnails"></td>
    <td></td>
  </tr>
  <tr>
    <td align="center"><b>Files</b>: browse the TV's storage, upload from the phone, open files on the TV</td>
    <td align="center"><b>Settings</b>: the TV's Settings, with wallpaper thumbnails and a searchable zone list</td>
    <td></td>
  </tr>
</table>

**Pairing (once per phone):**
1. Tap **Show PIN on TV**. A 6-digit PIN appears in the middle of the TV for 60 seconds.
2. Type it on the phone. The phone's name above it starts as a random one ("Calm Otter"); change it if you like. The phone keeps a token.
3. After 5 wrong tries the PIN is voided and you ask for a new one.

**Phone names:** the list of paired phones (Settings tab) uses the name typed when pairing. Without one, it guesses from the browser, but Chrome no longer tells plain-HTTP pages the phone's model: its browser description reads "Android 10; K" on every phone, so those show as "Android phone". **Rename** next to "(this phone)" in that list names it.

**Unpairing:** the broken-link button next to the TV's name.

| Tab | Does | Works |
|---|---|---|
| Remote | Swipe = D-pad, double-tap = OK | Home screen always. **In any app while USB debugging is on**; otherwise the pad dims in apps |
| Remote | Keyboard, Delete, Enter (only shown while USB debugging is on) | Type into any text field on the TV, e.g. YouTube or Netflix search (letters, numbers, symbols) |
| Remote | Back, Home | Everywhere, through the launcher's accessibility service |
| Remote | Screenshot (only shown while USB debugging is on) | Captures whatever is on the TV and shows it on the phone; **Save** downloads the PNG, or press and hold it to save to Photos. DRM video (Netflix, etc.) comes out black |
| Remote | Play/Pause | Whatever is playing (Netflix, YouTube, RadioStream…) |
| Remote | Vol −, Mute, Vol + | Everywhere; shows the TV's volume bar, and the new volume number (or *Muted*) on the phone for a moment |
| TV | **Now Playing**: app, title and state, with Play/Pause, Stop and Open app | Everywhere |
| TV | **Prayer times**: today's five, the next one highlighted with a countdown | Everywhere |
| TV | **System**: CPU clock, RAM, storage, IP and network, Wi-Fi signal, uptime | Everywhere |
| TV | **Notifications** (only when there are some): what the bell shows. Tap one to open it on the TV; **Clear all** | Everywhere |
| TV | **Power**: Sleep now, Restart (asks first), and a sleep timer (30, 60 or 90 min, or Off) | **USB debugging on** |
| TV | **Send to TV → Message**: up to 120 characters, in a box over any app (like the pairing PIN) that stays until someone presses **OK** or **Back**, on the TV remote or the phone. Volume still works while it shows | Everywhere |
| TV | **Send to TV → Link**: YouTube links open in the YouTube app, Netflix links in Netflix, other links in a browser if the TV has one | Everywhere |
| TV | **Inputs**: HDMI 1–3, AV, Live TV | Everywhere |
| Apps | **Running**: what's running, its memory and state; **Close** force-closes, **Hide** hides it (here and on the TV) | Everywhere |
| Apps | Dock, then all apps most-used first. Tap to open; **hold** (or right-click) for Open, Add to/Remove from dock, Force close and Disable | Disable needs **USB debugging on** |
| Apps | **Disabled from this phone**: Enable brings an app back. **Free up cache (all apps)** clears every app's cache | **USB debugging on** |
| Files | Browse internal storage and USB drives. Upload from the phone (with progress), download to the phone, open a video, photo or song on the TV | Everywhere |
| Settings | The TV's Settings from the phone: clock, cards, wallpaper dim, **wallpaper** (thumbnails, or upload a photo and set it in one go), prayer **zone** (searchable), azan, reminder, **Play azan now**, Most used week and Leave out, notifications. Changes show on the TV straight away | Everywhere |

**How it works:**
- **No ADB, so USB debugging can be switched off.**
- **Server:** the launcher runs a small foreground service (`RemoteService`) with an HTTP server on port 8486 (`RemoteServer`). It serves `assets/remote.html`.
- **What drives each action:**
  - D-pad keys go to the launcher's own focus logic.
  - Back and Home use `RemoteAccessibilityService`, which only performs those two actions and doesn't read the screen.
  - Media keys and volume go through `AudioManager`.
  - Apps and inputs are normal intents.
- **What it can't do:** without ADB it can't press D-pad keys or type inside other apps. Use the physical remote there.

**D-pad and typing in any app (USB debugging on):**
- **How it works:** Android doesn't let an app press keys in other apps, and on this TV the ADB shell can't write to the remote's `/dev/input` devices either (SELinux). It can create a virtual USB keyboard through `/dev/uhid` with Android's own `hid` command, so the launcher (`AdbKeys`):
  1. Opens an ADB connection to itself (`127.0.0.1:5555`, via the dadb library).
  2. Registers a virtual **keypad** with arrows, Enter, Esc and Delete.
  3. Sends key reports to it, about 50 ms per press.
- **Typing:** uses a second virtual **keyboard**, which is removed after a minute idle. While it exists, Android treats the TV as having a physical keyboard and hides the on-screen keyboard. The keypad has no letter keys, so it doesn't do this.
- **First use:** the TV asks **"Allow USB debugging?"** for the launcher's own key. Tick *Always allow* and accept. The fingerprint is the launcher's key in its private storage.
- **It connects** when a paired phone opens the remote, and drops when USB debugging is turned off. Then the remote falls back to home-screen-only, and the page says so.
- **After a reboot** network ADB (port 5555) may be off again. Turn it back on to get in-app D-pad.
- **Cost:** each virtual device is a small `hid` process, about 20 MB. The keypad is created on the first phone-remote key press inside an app and removed after 5 minutes without one; the keyboard only exists while typing (1 minute idle).
- **Security:** while debugging is on the launcher has ADB shell access, the same power network ADB already gives anyone on your Wi-Fi. Only paired phones can use it.

**Power and sleep timer:**
- **Sleep** presses the TV's own sleep key (`input keyevent KEYCODE_SLEEP`); **Restart** runs `reboot`. Both go through the launcher's ADB connection, so they only show while USB debugging is on.
- **Sleep timer:** a banner on the TV when it's set and one minute before, then the TV sleeps. Setting a new time replaces the old one; it's forgotten if the launcher restarts.

**App manager (USB debugging on):**
- **Disable** runs `pm disable-user --user 0`, so the app is hidden and can't run, but nothing is uninstalled and its data stays. **Enable** reverses it.
- **Never disabled:** this launcher and any other home-screen app, the TV's keyboard, Settings, the system UI, Play Store and Play Services, and the TV-input packages. Only apps with a launcher icon are offered.
- **Logged** in TV Home's private storage, with a copy in `Android/data/my.rafhan.app.tvhome/files/disabled-by-remote.txt` that `scripts/restore.sh` and the setup app read to re-enable those apps too. The copy is written again at start and every hour, in case a junk cleaner removed it, and apps that are back on drop off the log by themselves.
- **Free up cache** runs `pm trim-caches`, which empties every app's cache (Android 10 doesn't let the shell clear one app's). No "clear data": that would sign you out of apps.

**Settings tab:**
- It's drawn from a list the TV sends (`GET /api/settings`), and every change is checked against it on the TV, so the phone can only set what the TV's Settings page offers.
- **Left out on purpose:** the phone remote's own switch and "Forget all paired phones". Changing either from the phone would cut the phone off; they stay on the TV.
- **Your photo** (Wallpaper): the phone redraws the photo as an upright JPEG no bigger than 1920 px, uploads it to the TV's `Wallpapers` folder and sets it. A 12 MP photo becomes about 0.3–0.6 MB.

**Files tab:**
- **Uploads** stream straight to disk, so the size limit is the TV's free space. A 50 MB file takes about 4 s on Wi-Fi.
- **Duplicate names** get " (1)".
- **USB drives:** uploads may be refused, because Android 10 limits apps writing to removable drives. Browsing and downloading still work.
- **Open on TV** uses your "Always" choice if you've set one. Otherwise it picks MX Player for video and audio and X-plore for photos, so Android's "Open with" chooser never appears.
- **Access is limited** to internal storage and USB drives. Paths outside them (`/data`, `../`) are refused.

**Set up by `scripts/build-and-install.sh`:**
- Overlay permission (`appops … SYSTEM_ALERT_WINDOW allow`). It shows the PIN, and Android 10 needs it before the remote can open apps while another app is on screen.
- Storage permissions for the Files tab. On Android 8–10 that's `READ`/`WRITE_EXTERNAL_STORAGE` (plus `requestLegacyExternalStorage`); on Android 11+ the script also grants **All files access** (`appops … MANAGE_EXTERNAL_STORAGE allow`), since scoped storage otherwise blocks the Files tab, the `Wallpapers` folder and reading screenshots.
- The accessibility service, appended next to Button Mapper's. If the Back button says it needs the accessibility service, re-run the script or enable "Home phone remote" in Settings → Device Preferences → Accessibility.

**Security:**
- It's plain HTTP on your LAN, so treat it as trusted-network only: don't forward port 8486 through your router and turn USB debugging off when you're done. The PIN card shows which phone (its IP) asked for it.
- Pairing needs a 6-digit PIN shown on the TV; five wrong tries (across PINs) locks pairing with a wait that doubles each time, up to 15 minutes.
- Only paired phones can control it. Tokens are stored on the TV only as a SHA-256 hash, sent as an `X-Token` header, and kept in an `HttpOnly`, `SameSite=Strict` cookie for downloads and images, so a token never appears in a URL. Each phone can be forgotten on its own in the remote's Settings tab.
- Requests addressed by hostname instead of IP are refused (DNS rebinding), and cross-site requests are refused too. Responses carry a strict `Content-Security-Policy` (scripts run only from a per-load nonce), `X-Content-Type-Options: nosniff` and a no-referrer policy.
- If the TV's IP changes, the bookmark breaks. Reserve the TV's IP address in your router.

**Undo:** `scripts/restore.sh` removes the accessibility service and keeps Button Mapper's (and turns `accessibility_enabled` back off if nothing else uses it).

## Change the dock

The dock holds **one row of apps** (5 by default; **Settings → Home screen → Apps per row** sets 5, 6 or 7), in any order. Lowering the number hides the dock apps that no longer fit; they're kept and come back when you raise it again.

**On the TV**, hold **OK** (or press **Menu**) on an app:
- **On a dock app:**
  - **Move:** the app wiggles. Use ◀ ▶ to slide it, then **OK** (or Back) to place it.
  - **Remove from dock.**
  - **Open.**
- **On an app in the app list:**
  - **Add to dock**, or **Replace a dock app…** when the dock is full. That jumps to the dock, which asks "Replace which app with …?"; pick one with OK, or press Back to cancel.
  - **Open.**

**From the phone remote**, on the Apps tab tap **Edit** next to DOCK:
- ◀ ▶ reorders an app and ✕ removes it.
- **+** on any app below adds it.
- **Done** saves it to the TV, which updates at once.

**Missing apps:** if a dock app isn't installed, or is disabled, it's skipped and the dock shows the others. It comes back by itself once the app is installed or enabled again. The next time you edit the dock it's dropped, so it doesn't hold a hidden slot.

**Where it's stored:** in TV Home's private storage, so updates and junk cleaners can't touch it. Xiaomi's TV Manager deletes files in apps' shared folders when the TV wakes, which used to reset the dock to the built-in default (`res/values/dock.xml`). A copy is kept in `/sdcard/Android/data/my.rafhan.app.tvhome/files/dock.txt`, one package per line, left to right, and written again if a cleaner removes it. Push your own `dock.txt` there over ADB to replace the dock; TV Home takes it in the next time the home screen opens.

## Change the wallpaper

**Settings → Home screen → Wallpaper** offers **Bing wallpaper of the day**, seven built-in photos (lakes, landscapes, forest, night sky and a galaxy), **Your picture** (if you pushed one) and **None**.

- **Bing wallpaper of the day** changes by itself every day. It pulls Bing's daily photo from `bing.com`'s public feed (`HPImageArchive.aspx`), keeping the last 7 days cached (~0.3 MB each) and showing today's; the picker sub-line names the place. Only scenic pictures are kept — any whose caption names people, or where a face is detected (Android's built-in `FaceDetector`), is skipped, so there are no people or faces. Bing's day follows its market, which is the TV's language: for en-US the new photo appears at 07:00 UTC (15:00 in Malaysia), and the launcher checks again only when that next photo is due. Offline it keeps showing the last one.

**The seven built-in photos** aren't in the APK. The TV downloads each one once (1920 px, 0.5–1 MB) from Wikimedia Commons and keeps it in the launcher's storage:
- the default (Stargazer, the first in the list) on first start;
- the rest when you first open the Wallpaper list, where their thumbnails appear as they land.

Moving the focus through the list previews each one behind Settings; **OK** sets it, and Back without OK keeps the one you had. **Wallpaper dim** sets how dark it is behind the cards.

| Wallpaper | Photo | Credit · licence |
|---|---|---|
| Stargazer (default) | [Looking at the stars during the Perseids](https://commons.wikimedia.org/wiki/File:018_Human_looking_at_the_stars_during_Perseids_with_the_Milky_Way_in_the_background_Photo_by_Giles_Laurent.jpg) | Giles Laurent · CC BY-SA 4.0 |
| Lake Seliger | [Lake Seliger, view of Voroniy Island](https://commons.wikimedia.org/wiki/File:Lake_Seliger._Ostashkov._View_of_Voroniy_Island.jpg) | Aleksandr Baydukov · CC BY-SA 4.0 |
| Misty sunrise | [Dülmen, Umland, 2014](https://commons.wikimedia.org/wiki/File:D%C3%BClmen,_Umland_--_2014_--_7056.jpg) | Dietmar Rabich · CC BY-SA 4.0 |
| Galaxy M81 | [Messier 81 (Hubble)](https://commons.wikimedia.org/wiki/File:Messier_81_HST.jpg) | NASA, ESA and the Hubble Heritage Team · public domain |
| Dome Creek | [Lake in Dome Creek](https://commons.wikimedia.org/wiki/File:Lake_in_Dome_Creek.jpg) | Jakub Fryš · CC BY-SA 4.0 |
| Forest light | [Roruper Holz nature reserve, 2021](https://commons.wikimedia.org/wiki/File:D%C3%BClmen,_Rorup,_NSG_Roruper_Holz_--_2021_--_8187-91.jpg) | Dietmar Rabich · CC BY-SA 4.0 |
| Night clouds | [Noctilucent clouds over Kuresoo bog](https://commons.wikimedia.org/wiki/File:Helkivad_%C3%B6%C3%B6pilved_Kuresoo_kohal.jpg) | Martin Koitmäe · CC BY-SA 4.0 |

**Your own pictures** go in the **Wallpapers** folder on the TV's internal storage (`/sdcard/Wallpapers`). Any JPG, PNG or WebP there shows up at the end of the Wallpaper list under its file name, with a thumbnail; preview and set it like the built-in ones. Ideally 1920×1080 or larger; big photos are scaled down when loaded.
- **Phone remote:** Settings tab → Wallpaper → **Your photo** uploads a photo and sets it in one go. Or Files tab → **Wallpapers** (the shortcut under the storage list) → Upload, then pick it on the TV.
- **ADB:** `adb push mypicture.jpg /sdcard/Wallpapers/`

Deleting a picture that's in use switches back to the default. The folder survives a launcher uninstall; the downloaded built-in photos don't. (An older picture pushed to `Android/data/my.rafhan.app.tvhome/files/wallpaper.jpg` still appears as **Your picture**.)

## Hidden system apps

Some system apps only have a phone-style launcher entry. On this TV those are setup leftovers (a Netflix helper and a "Disclaimer Customization" screen), so the grid hides them. Clock is the exception. To show another one, add its package to `launcher/app/src/main/res/values/apps.xml` and rebuild.
