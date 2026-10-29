package my.rafhan.app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The phone remote's API: the routes below {@code /} and the actions behind them (keys, apps,
 * files, settings, media, pairing). {@link RemoteServer} owns the sockets and HTTP; this owns
 * what a request means. Pairing state is in {@link RemotePairing}.
 */
final class RemoteApi {
    /** The token of this request: the API header, or the pairing cookie for images and downloads. */
    static final String COOKIE = "tvtoken";

    private final Context ctx;
    private final PinOverlay overlay;
    private final MessageOverlay messages;
    private final FileApi files;
    private final AdbKeys adbKeys;
    private final RemoteDevices devices;
    private final RemoteSettings settings;
    private final SleepTimer sleepTimer;
    private final RemotePairing pairing;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> banners = new HashMap<>();
    private final Object shotLock = new Object();

    // App list cache (banner lookups), guarded by this.
    private List<AppEntry> appCache;
    private int appCacheDock;
    private long appCacheAt;

    RemoteApi(Context ctx, SleepTimer sleepTimer) {
        this.ctx = ctx;
        this.sleepTimer = sleepTimer;
        overlay = new PinOverlay(ctx);
        messages = new MessageOverlay(ctx);
        files = new FileApi(ctx);
        adbKeys = AdbKeys.get(ctx);
        devices = new RemoteDevices(ctx);
        settings = new RemoteSettings(ctx);
        pairing = new RemotePairing(random);
    }

    /** Drops cached app banners and the app list (rebuilt on the next phone request). */
    void trim() {
        synchronized (banners) {
            banners.clear();
        }
        synchronized (this) {
            appCache = null;
        }
        settings.trim();
    }

    /** Hides anything this API put over the TV. */
    void hide() {
        overlay.hide();
    }

    boolean authorised(String token) {
        return devices.find(token) != null;
    }

    /** Uploads are streamed here by the HTTP layer, which has already checked the token. */
    Object[] upload(String dir, String name, long length, InputStream in) throws JSONException {
        return files.upload(dir, name, length, in);
    }

    /** The token of this request: the API header, or the pairing cookie for images and downloads. */
    static String requestToken(Map<String, String> headers) {
        String token = headers.get("x-token");
        if (token != null && !token.isEmpty()) return token;
        String cookie = headers.get("cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String c = part.trim();
            if (c.startsWith(COOKIE + "=")) return c.substring(COOKIE.length() + 1);
        }
        return null;
    }

    /** HttpOnly (JS can't read it) and Strict (never sent from another site). */
    static String cookie(String token) {
        return COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000";
    }

    void route(String method, String path, Map<String, String> query, Map<String, String> headers,
               byte[] body, OutputStream out, String clientIp) throws IOException, JSONException {
        switch (path) {
            case "/":
            case "/index.html": {
                // A fresh nonce per page load, so only this page's own <script> can run.
                String nonce = nonce();
                String html = new String(asset("remote.html"), StandardCharsets.UTF_8)
                        .replaceFirst("<script>", "<script nonce=\"" + nonce + "\">");
                Http.send(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), "no-cache",
                        null, Http.cspWithNonce(nonce));
                return;
            }
            case "/font":
                Http.send(out, 200, "font/ttf", asset("fonts/Outfit.ttf"), "max-age=86400");
                return;
            case "/icon.png":
                Http.send(out, 200, "image/png", icon(), "max-age=86400");
                return;
            case "/manifest.json": // lets phones install the remote as a full-screen app
                Http.send(out, 200, "application/manifest+json", ("{\"name\":\"TV Remote\",\"short_name\":\"TV Remote\","
                        + "\"start_url\":\"/\",\"display\":\"fullscreen\",\"orientation\":\"portrait\","
                        + "\"background_color\":\"#000000\",\"theme_color\":\"#000000\","
                        + "\"icons\":[{\"src\":\"/icon.png\",\"sizes\":\"180x180\",\"type\":\"image/png\"}]}")
                        .getBytes(StandardCharsets.UTF_8), "max-age=86400");
                return;
            case "/api/pair":
                if (Http.post(method, out)) Http.json(out, requestPin(clientIp));
                return;
            case "/api/pair/confirm":
                if (Http.post(method, out)) {
                    Object[] r = confirmPin(body, headers.get("user-agent"));
                    if ((int) r[0] == 200) {
                        // Also hand the phone an HttpOnly cookie, so images and downloads (which
                        // can't set a header) authenticate without a token in the URL.
                        Http.json(out, 200, (JSONObject) r[1], cookie(((JSONObject) r[1]).optString("token")));
                    } else {
                        Http.json(out, r);
                    }
                }
                return;
            default:
                break;
        }

        String token = requestToken(headers);
        if (!authorised(token)) {
            Http.json(out, 401, Http.err("Not paired."));
            return;
        }
        switch (path) {
            case "/api/state": {
                // A phone using the remote is the moment to (re)connect ADB, so the TV's
                // "Allow USB debugging?" prompt shows while someone is looking at it.
                adbKeys.ensure();
                AdbKeys.State a = adbKeys.state();
                Http.json(out, 200, new Http.JSONObjectBuilder()
                        .put("ok", true)
                        .put("home", RemoteBridge.isHome())
                        .put("back", RemoteAccessibilityService.isOn())
                        .put("adb", a.name().toLowerCase(Locale.ROOT))
                        .put("adbError", a == AdbKeys.State.ERROR ? adbKeys.error() : null)
                        .put("name", android.os.Build.MODEL).build());
                return;
            }
            case "/api/text":
                if (Http.post(method, out)) Http.json(out, text(body));
                return;
            case "/api/apps":
                Http.json(out, 200, appsJson());
                return;
            case "/api/banner": {
                byte[] png = banner(query.get("pkg"));
                if (png == null) Http.json(out, 404, Http.err("No such app."));
                else Http.send(out, 200, "image/png", png, "max-age=3600");
                return;
            }
            case "/api/inputs":
                Http.json(out, 200, inputsJson());
                return;
            case "/api/open":
                if (Http.post(method, out)) Http.json(out, open(query.get("pkg")));
                return;
            case "/api/input":
                if (Http.post(method, out)) Http.json(out, input(query.get("i")));
                return;
            case "/api/dock":
                if (Http.post(method, out)) Http.json(out, saveDock(body));
                return;
            case "/api/key":
                if (Http.post(method, out)) Http.json(out, key(query.get("k"), query.get("n")));
                return;
            case "/api/files/roots":
                Http.json(out, 200, files.rootsJson());
                return;
            case "/api/files/list": {
                JSONObject list = files.list(query.get("path"));
                if (list == null) Http.json(out, 403, Http.err(FileApi.hasAllFilesAccess()
                        ? "That folder isn't available."
                        : "On Android 11+, run scripts/build-and-install.sh to grant All files access."));
                else Http.json(out, 200, list);
                return;
            }
            case "/api/files/raw": {
                java.io.File f = files.file(query.get("path"));
                if (f == null) Http.json(out, 404, Http.err("No such file."));
                else files.download(f, out, headers.get("range"));
                return;
            }
            case "/api/files/open":
                if (Http.post(method, out)) {
                    java.io.File f = files.file(query.get("path"));
                    Intent view = f == null ? null : files.openIntent(f);
                    if (f == null) Http.json(out, 404, Http.err("No such file."));
                    else if (view == null) Http.json(out, 415, Http.err("The TV doesn't know how to open this file type."));
                    else Http.json(out, start(view));
                }
                return;
            case "/api/dashboard":
                Http.json(out, 200, RemoteDashboard.build(ctx, adbKeys, sleepTimer));
                return;
            case "/api/media":
                if (Http.post(method, out)) Http.json(out, mediaAction(query.get("a")));
                return;
            case "/api/alerts/open":
                if (Http.post(method, out)) Http.json(out, openAlert(query.get("id")));
                return;
            case "/api/alerts/clear":
                if (Http.post(method, out)) {
                    Alerts.get().clear();
                    Http.json(out, 200, Http.ok());
                }
                return;
            case "/api/message":
                if (Http.post(method, out)) Http.json(out, message(body));
                return;
            case "/api/openurl":
                if (Http.post(method, out)) Http.json(out, openUrl(body));
                return;
            case "/api/running":
                Http.json(out, 200, runningJson());
                return;
            case "/api/running/close":
                if (Http.post(method, out)) Http.json(out, closeRunning(query.get("pkg")));
                return;
            case "/api/running/hide":
                if (Http.post(method, out)) {
                    String pkg = query.get("pkg");
                    if (pkg == null) Http.json(out, 400, Http.err("No app given."));
                    else {
                        RunningApps.setHidden(ctx, pkg, !"0".equals(query.get("on")));
                        Http.json(out, 200, Http.ok());
                    }
                }
                return;
            case "/api/power":
                if (Http.post(method, out)) Http.json(out, power(query.get("a")));
                return;
            case "/api/sleeptimer":
                if (Http.post(method, out)) {
                    int min = 0;
                    try {
                        min = Math.max(0, Math.min(240, Integer.parseInt(query.get("min"))));
                    } catch (Exception ignored) {
                    }
                    sleepTimer.set(min);
                    Http.json(out, 200, Http.ok());
                }
                return;
            case "/api/apps/disabled":
                Http.json(out, 200, disabledJson());
                return;
            case "/api/apps/disable":
            case "/api/apps/enable":
                if (Http.post(method, out)) {
                    String pkg = query.get("pkg");
                    String why = path.endsWith("disable") ? AppManager.disable(ctx, pkg) : AppManager.enable(ctx, pkg);
                    if (why == null) synchronized (this) {
                        appCache = null; // the app list changes
                    }
                    Http.json(out, why == null ? 200 : 409, why == null ? Http.ok() : Http.err(why));
                }
                return;
            case "/api/apps/trimcaches":
                if (Http.post(method, out)) {
                    long freed = AppManager.trimCaches(ctx);
                    if (freed < 0) Http.json(out, 409, Http.err("Turn on USB debugging on the TV first."));
                    else Http.json(out, 200, new Http.JSONObjectBuilder().put("ok", true).put("freedMb", freed / 1_000_000).build());
                }
                return;
            case "/api/screenshot":
                screenshot(out);
                return;
            case "/api/settings":
                if ("POST".equals(method)) {
                    String why;
                    try {
                        why = settings.set(new JSONObject(new String(body, StandardCharsets.UTF_8)));
                    } catch (JSONException e) {
                        why = "Bad request.";
                    }
                    if (why != null) Http.json(out, 400, Http.err(why));
                    else Http.json(out, 200, settings.schema());
                } else {
                    Http.json(out, 200, settings.schema());
                }
                return;
            case "/api/settings/zones":
                Http.json(out, 200, settings.zones());
                return;
            case "/api/wallpapers":
                Http.json(out, 200, settings.wallpapers());
                return;
            case "/api/wallpaper/thumb": {
                byte[] jpg = settings.thumb(query.get("id"));
                if (jpg == null) Http.json(out, 404, Http.err("No picture yet."));
                else Http.send(out, 200, "image/jpeg", jpg, "max-age=86400");
                return;
            }
            case "/api/session":
                // Confirms a token that was paired before cookies existed and hands it to this
                // browser as a cookie, so banners/downloads work without a token in the URL.
                if (Http.post(method, out)) Http.json(out, 200, Http.ok(), cookie(token));
                return;
            case "/api/devices":
                Http.json(out, 200, new Http.JSONObjectBuilder().put("ok", true).put("devices", devices.json(token)).build());
                return;
            case "/api/devices/revoke":
                if (Http.post(method, out)) {
                    String id;
                    try {
                        id = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("id");
                    } catch (JSONException e) {
                        Http.json(out, 400, Http.err("Bad request."));
                        return;
                    }
                    if (id.isEmpty() || !devices.remove(id)) Http.json(out, 404, Http.err("That phone isn't paired."));
                    else Http.json(out, 200, Http.ok());
                }
                return;
            case "/api/unpair":
                if (Http.post(method, out)) {
                    devices.removeByToken(token);
                    Http.json(out, 200, Http.ok());
                }
                return;
            default:
                Http.json(out, 404, Http.err("Not found."));
        }
    }

    // ---------------------------------------------------------------- pairing

    private synchronized Object[] requestPin(String clientIp) {
        long wait = pairing.blockedForMs();
        if (wait > 0) return new Object[] {429, Http.err("Too many wrong PINs. Try again in " + (wait / 1000) + "s.")};
        if (pairing.tooSoon()) return new Object[] {429, Http.err("Wait a moment and try again.")};
        if (!overlay.canShow()) {
            return new Object[] {503, Http.err("The TV can't show the PIN: run scripts/build-and-install.sh to grant the overlay permission.")};
        }
        String pin = pairing.newPin();
        overlay.show(pin, RemoteServer.lanUrl(ctx), clientIp, RemotePairing.PIN_MS);
        return new Object[] {200, Http.ok()};
    }

    private synchronized Object[] confirmPin(byte[] body, String userAgent) {
        long wait = pairing.blockedForMs();
        if (wait > 0) {
            pairing.clear();
            overlay.hide();
            return new Object[] {429, Http.err("Too many wrong PINs. Try again in " + (wait / 1000) + "s.")};
        }
        String given;
        try {
            given = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("pin");
        } catch (JSONException e) {
            return new Object[] {400, Http.err("Bad request.")};
        }
        switch (pairing.confirm(given)) {
            case OK: {
                overlay.hide();
                String token = randomToken();
                devices.add(token, RemoteDevices.name(userAgent));
                return new Object[] {200, new Http.JSONObjectBuilder().put("ok", true).put("token", token).build()};
            }
            case LOCKED:
                overlay.hide();
                return new Object[] {403, Http.err("Too many wrong PINs. Show a new PIN on the TV later.")};
            case EXPIRED:
                return new Object[] {410, Http.err("No PIN on the TV. Tap \"Show PIN on TV\" again.")};
            case TRIED_OUT:
                overlay.hide();
                return new Object[] {403, Http.err("Too many wrong tries. Show a new PIN on the TV.")};
            default:
                return new Object[] {403, Http.err("Wrong PIN (" + pairing.triesLeft() + " tries left).")};
        }
    }

    private String randomToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- actions

    /** Dock first (launcher order), then the rest most-used first, like the grid. */
    private synchronized List<AppEntry> apps(boolean fresh) {
        long now = SystemClock.elapsedRealtime();
        if (fresh || appCache == null || now - appCacheAt > 30_000) {
            List<AppEntry> all = AppRepository.load(ctx);
            Map<String, AppEntry> byPkg = new HashMap<>();
            for (AppEntry e : all) byPkg.put(e.pkg, e);
            List<AppEntry> ordered = new ArrayList<>();
            for (String pkg : DockConfig.load(ctx)) {
                AppEntry e = byPkg.get(pkg);
                if (e != null && !ordered.contains(e) && ordered.size() < DockConfig.size(ctx)) ordered.add(e);
            }
            appCacheDock = ordered.size();
            List<AppEntry> rest = new ArrayList<>(all);
            rest.removeAll(ordered);
            UsageScores.sort(rest, UsageScores.allTime(ctx)); // same order as the TV's grid
            ordered.addAll(rest);
            appCache = ordered;
            appCacheAt = now;
        }
        return appCache;
    }

    private AppEntry find(String pkg) {
        if (pkg == null) return null;
        for (AppEntry e : apps(false)) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    /**
     * {"pkgs":[...]}: the new dock, left to right (installed launchable apps only, at most "apps
     * per row"). Apps a lower "apps per row" hides are kept after them, as on the TV.
     */
    private Object[] saveDock(byte[] body) {
        try {
            JSONArray arr = new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("pkgs");
            int max = DockConfig.size(ctx);
            if (arr.length() > max) return new Object[] {400, Http.err("The dock holds up to " + max + " apps.")};
            Set<String> known = new HashSet<>();
            for (AppEntry e : AppRepository.load(ctx)) known.add(e.pkg);
            List<String> pkgs = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.getString(i);
                if (!known.contains(p)) return new Object[] {400, Http.err("Unknown app: " + p)};
                if (!pkgs.contains(p)) pkgs.add(p);
            }
            List<String> saved = DockConfig.load(ctx), shownBefore = new ArrayList<>();
            for (String p : saved) if (known.contains(p) && shownBefore.size() < max && !shownBefore.contains(p)) shownBefore.add(p);
            pkgs = DockConfig.keepHidden(pkgs, saved, shownBefore, known);
            if (!DockConfig.save(ctx, pkgs)) return new Object[] {500, Http.err("Couldn't save the dock.")};
            synchronized (this) {
                appCache = null;
            }
            return new Object[] {200, Http.ok()};
        } catch (JSONException e) {
            return new Object[] {400, Http.err("Bad request.")};
        }
    }

    private JSONObject appsJson() {
        JSONArray arr = new JSONArray();
        List<AppEntry> list = apps(true);
        int dock;
        synchronized (this) {
            dock = appCacheDock;
        }
        for (int i = 0; i < list.size(); i++) {
            AppEntry e = list.get(i);
            arr.put(new Http.JSONObjectBuilder().put("pkg", e.pkg).put("label", e.label).put("dock", i < dock).build());
        }
        return new Http.JSONObjectBuilder().put("ok", true).put("apps", arr).put("dockMax", DockConfig.size(ctx)).build();
    }

    private byte[] banner(String pkg) {
        AppEntry e = find(pkg);
        if (e == null) return null;
        synchronized (banners) {
            byte[] cached = banners.get(e.cacheKey);
            if (cached != null) return cached;
        }
        Bitmap bmp = TileRenderer.render(ctx, e, 320, 180);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
        bmp.recycle();
        byte[] bytes = png.toByteArray();
        synchronized (banners) {
            banners.put(e.cacheKey, bytes);
        }
        return bytes;
    }

    private Object[] open(String pkg) {
        AppEntry e = find(pkg);
        if (e == null) return new Object[] {404, Http.err("No such app.")};
        return start(e.launch);
    }

    private JSONObject inputsJson() {
        JSONArray arr = new JSONArray();
        List<TvInputs.Item> items = TvInputs.load(ctx);
        for (int i = 0; i < items.size(); i++) {
            arr.put(new Http.JSONObjectBuilder().put("i", i).put("label", items.get(i).label).build());
        }
        return new Http.JSONObjectBuilder().put("ok", true).put("inputs", arr).build();
    }

    private Object[] input(String index) {
        List<TvInputs.Item> items = TvInputs.load(ctx);
        try {
            int i = Integer.parseInt(index);
            if (i >= 0 && i < items.size()) return start(new Intent(items.get(i).intent));
        } catch (NumberFormatException ignored) {
        }
        return new Object[] {404, Http.err("No such input.")};
    }

    /** TV tab: Play/Pause and Stop for whatever plays, or open the app that's playing. */
    private Object[] mediaAction(String a) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if ("playpause".equals(a)) {
            media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            return new Object[] {200, Http.ok()};
        }
        if ("stop".equals(a)) {
            media(am, KeyEvent.KEYCODE_MEDIA_STOP);
            return new Object[] {200, Http.ok()};
        }
        if ("open".equals(a)) {
            NowPlayingPanel.Info i = RemoteDashboard.session(ctx);
            if (i == null) return new Object[] {404, Http.err("Nothing is playing.")};
            return open(i.pkg);
        }
        return new Object[] {400, Http.err("Unknown action.")};
    }

    /** TV tab: a bell notification opened on the TV (then cleared, as on the TV). */
    private Object[] openAlert(String id) {
        for (Alerts.Alert a : Alerts.get().list()) {
            if (!a.id.equals(id)) continue;
            Alerts.get().dismiss(a);
            return a.action != null ? start(new Intent(a.action)) : new Object[] {200, Http.ok()};
        }
        return new Object[] {404, Http.err("That notification is gone.")};
    }

    /** TV tab: a short message typed on the phone, in a box over whatever is on the TV until OK. */
    private Object[] message(byte[] body) {
        String t;
        try {
            t = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("text").trim();
        } catch (JSONException e) {
            return new Object[] {400, Http.err("Bad request.")};
        }
        if (t.isEmpty()) return new Object[] {400, Http.err("Type a message first.")};
        if (t.length() > 120) t = t.substring(0, 120);
        if (!messages.canShow()) {
            return new Object[] {503, Http.err("The TV can't show messages: run scripts/build-and-install.sh to grant the overlay permission.")};
        }
        messages.show(t);
        return new Object[] {200, Http.ok()};
    }

    /**
     * TV tab: a link pasted on the phone, opened on the TV. YouTube and Netflix links go straight to
     * their TV apps; anything else needs an app on the TV that opens web links.
     */
    private Object[] openUrl(byte[] body) {
        String url;
        try {
            url = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("url").trim();
        } catch (JSONException e) {
            return new Object[] {400, Http.err("Bad request.")};
        }
        if (url.isEmpty()) return new Object[] {400, Http.err("Paste a link first.")};
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) url = "https://" + url;
        android.net.Uri uri = android.net.Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // Only web links. Without this, intent://, file:// and the like reached startActivity.
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new Object[] {400, Http.err("Only web links (http or https) can be opened.")};
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String app = host.endsWith("youtube.com") || host.equals("youtu.be") ? "com.google.android.youtube.tv"
                : host.endsWith("netflix.com") ? "com.netflix.ninja" : null;
        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        if (app != null) {
            Object[] r = start(new Intent(view).setPackage(app));
            if ((int) r[0] == 200) return r;
        }
        return start(view);
    }

    /** Apps tab: running apps (launchable ones), as on the TV's running-apps screen. Blocking. */
    private JSONObject runningJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (RunningApps.App a : RunningApps.list(ctx)) {
            arr.put(new JSONObject().put("pkg", a.entry.pkg).put("label", a.entry.label).put("state", a.state)
                    .put("memKb", a.pssKb).put("hidden", a.hidden));
        }
        return new JSONObject().put("ok", true).put("apps", arr)
                .put("adb", adbKeys.state() == AdbKeys.State.READY);
    }

    private Object[] closeRunning(String pkg) {
        if (pkg == null) return new Object[] {400, Http.err("No app given.")};
        for (RunningApps.App a : RunningApps.list(ctx)) {
            if (!a.entry.pkg.equals(pkg)) continue;
            return new Object[] {200, new Http.JSONObjectBuilder().put("ok", true)
                    .put("message", RunningApps.forceStop(ctx, a)).build()};
        }
        return new Object[] {200, new Http.JSONObjectBuilder().put("ok", true).put("message", "It's not running any more.").build()};
    }

    /** Apps the phone remote disabled, with their names, to turn back on. */
    private JSONObject disabledJson() throws JSONException {
        JSONArray arr = new JSONArray();
        PackageManagerHelper pm = new PackageManagerHelper(ctx);
        for (String pkg : AppManager.disabled(ctx)) arr.put(new JSONObject().put("pkg", pkg).put("label", pm.label(pkg)));
        return new JSONObject().put("ok", true).put("apps", arr);
    }

    /** TV tab: Sleep now / Restart (ADB). Restart answers first: the TV drops off the network. */
    private Object[] power(String a) {
        if (adbKeys.state() != AdbKeys.State.READY) return adbReason();
        if ("sleep".equals(a)) {
            AppManager.run(ctx, "input keyevent KEYCODE_SLEEP");
            return new Object[] {200, Http.ok()};
        }
        if ("reboot".equals(a)) {
            new Thread(() -> {
                SystemClock.sleep(800);
                AppManager.run(ctx, "reboot");
            }, "remote-reboot").start();
            return new Object[] {200, Http.ok()};
        }
        return new Object[] {400, Http.err("Unknown action.")};
    }

    /**
     * The TV's screen as a PNG, for the phone's Screenshot button. Apps can't capture other apps,
     * so it runs `screencap` as the shell user over the loopback ADB connection (USB debugging on).
     * It writes to a file and reads it back: on this TV screencap's stdout carries vendor log noise.
     * DRM-protected video (Netflix, etc.) comes out black.
     */
    private void screenshot(OutputStream out) throws IOException {
        if (adbKeys.state() != AdbKeys.State.READY) {
            Http.json(out, adbReason());
            return;
        }
        byte[] png = null;
        synchronized (shotLock) {
            java.io.File f = new java.io.File(android.os.Environment.getExternalStorageDirectory(), ".tvhome-screenshot.png");
            f.delete();
            AdbKeys.Hid h = adbKeys.shell("timeout 15 screencap -p " + f.getPath());
            if (h != null) {
                try {
                    while (h.stream.getSource().readUtf8Line() != null) { /* until screencap exits */ }
                } catch (Exception ignored) {
                } finally {
                    adbKeys.stop(h);
                }
                try {
                    if (f.length() > 0) png = java.nio.file.Files.readAllBytes(f.toPath());
                } catch (IOException ignored) {
                }
                f.delete();
            }
        }
        if (png == null) Http.json(out, 500, Http.err("Couldn't take a screenshot. Try again."));
        else Http.send(out, 200, "image/png", png, "no-store");
    }

    /** Why a key that needs ADB can't be sent right now. */
    private Object[] adbReason() {
        AdbKeys.State a = adbKeys.state();
        String reason = a == AdbKeys.State.OFF ? "home-only"
                : a == AdbKeys.State.ALLOW ? "adb-allow"
                : a == AdbKeys.State.ERROR ? "adb-error" : "adb-connecting";
        adbKeys.ensure();
        return new Object[] {200, new Http.JSONObjectBuilder().put("ok", false).put("reason", reason)
                .put("error", adbKeys.error()).build()};
    }

    private Object[] text(byte[] body) {
        String t;
        try {
            t = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("text");
        } catch (JSONException e) {
            return new Object[] {400, Http.err("Bad request.")};
        }
        if (t.length() > 200) t = t.substring(0, 200);
        if (adbKeys.state() != AdbKeys.State.READY) return adbReason();
        return adbKeys.type(t) ? new Object[] {200, Http.ok()} : adbReason();
    }

    private Object[] key(String k, String count) {
        if (k == null) return new Object[] {400, Http.err("Missing key.")};
        int n = 1;
        try {
            n = Math.max(1, Math.min(50, Integer.parseInt(count)));
        } catch (NumberFormatException ignored) {
        }
        // A message from the phone is on screen: the phone's OK or Back closes it (even without
        // USB debugging, when the keys couldn't reach it).
        if (messages.isShowing() && (k.equals("ok") || k.equals("enter") || k.equals("back"))) {
            messages.hide();
            return new Object[] {200, Http.ok()};
        }
        AudioManager am = ctx.getSystemService(AudioManager.class);
        switch (k) {
            case "up":
            case "down":
            case "left":
            case "right":
            case "ok":
                // Home screen: the launcher's own focus logic (instant). Elsewhere: ADB keypad.
                if (RemoteBridge.isHome()) {
                    for (int i = 0; i < n; i++) RemoteBridge.key(k);
                    return new Object[] {200, Http.ok()};
                }
                if (adbKeys.state() == AdbKeys.State.READY && adbKeys.key(k, n)) return new Object[] {200, Http.ok()};
                return adbReason();
            case "enter":
            case "backspace":
                if (adbKeys.state() == AdbKeys.State.READY && adbKeys.key(k, n)) return new Object[] {200, Http.ok()};
                return adbReason();
            case "back":
                if (RemoteBridge.key("back")) return new Object[] {200, Http.ok()};
                if (RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_BACK)) return new Object[] {200, Http.ok()};
                return new Object[] {200, new Http.JSONObjectBuilder().put("ok", false).put("reason", "no-accessibility").build()};
            case "home":
                if (RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_HOME)) return new Object[] {200, Http.ok()};
                return start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
            case "playpause":
                media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return new Object[] {200, Http.ok()};
            case "next":
                media(am, KeyEvent.KEYCODE_MEDIA_NEXT);
                return new Object[] {200, Http.ok()};
            case "prev":
                media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                return new Object[] {200, Http.ok()};
            case "volup":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return new Object[] {200, volume(am)};
            case "voldown":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return new Object[] {200, volume(am)};
            case "mute":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return new Object[] {200, volume(am)};
            default:
                return new Object[] {400, Http.err("Unknown key.")};
        }
    }

    /** The TV's volume after a volume key, so the phone can show the number. */
    private static JSONObject volume(AudioManager am) {
        return new Http.JSONObjectBuilder().put("ok", true)
                .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC)).build();
    }

    private static void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private Object[] start(Intent intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new Object[] {200, Http.ok()};
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException e) {
            return new Object[] {500, Http.err("No app on the TV can open it.")};
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A fresh, unguessable value for the page's own inline script tag. */
    private String nonce() {
        byte[] b = new byte[12];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private byte[] icon() {
        Bitmap bmp = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        Drawable d = ctx.getDrawable(R.drawable.ic_home);
        if (d != null) {
            d.setBounds(30, 30, 150, 150);
            d.draw(c);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
        return png.toByteArray();
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }
}
