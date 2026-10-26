package my.rafhan.app.tvhome;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. Focus is a small state machine (section + index) driven from
 * dispatchKeyEvent rather than Android's focus search, so every D-pad press has exactly one
 * outcome: Up/Down move one row, Up from the dock goes to the top bar, row and bar ends stop.
 */
public class MainActivity extends Activity {
    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3, NOW = 4, UP = 5, SETTINGS = 6;
    private static final float NOW_SCALE = 1.12f;
    private static final int RIGHT_CARD_W = 412; // System and Now Playing cards, dp
    private static final int COLS = 5, DOCK_SIZE = DockConfig.MAX;
    private static final float TILE_SCALE = 1.1f, PILL_SCALE = 1.06f;
    // dp
    private static final int TILE_W = 152, TILE_H = 86, COL_W = 172, ROW_PITCH = 132;
    // The grid runs to the top of the screen (GRID_TOP 0) with its first row 70dp down, so the
    // row above scrolls up under the top edge (shaded by gridTopFade) instead of vanishing at a
    // hard edge 56dp down and leaving a blank band.
    private static final int GRID_TOP = 0, GRID_PAD_TOP = 70, GRID_PAD_BOTTOM = 48;
    // PEEK: how much of the grid's first row shows under the dock on the home screen.
    // Focused dock app's name above the dock: line height (layout: 54dp) and gap to the dock.
    private static final int HERO_H = 54, HERO_GAP = 6;
    private static final float PEEK_ALPHA = 0.55f;
    private static final int PEEK = 15, BAND_GAP = 18, BAND_PAD_V = 15, TILE_RADIUS = 11;
    private static final Object PAYLOAD_TILE = new Object();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, AppEntry> rendered = new HashMap<>();
    private final List<ImageView> dockTiles = new ArrayList<>();
    private final int[] loc = new int[2];

    private float dp;
    private int screenW, screenH;
    private SharedPreferences prefs;

    private FrameLayout page;
    private ImageView wallpaper;
    private GlassHighlightView highlight;
    private TextView clock, date, hijri, hero;
    private View glow;
    private View[] homeViews;
    private SystemPanel system;
    private NowPlayingPanel nowPlaying;
    private UpScreen up;
    private SettingsScreen settingsScreen;
    private FrameLayout settingsPage;
    private FrameLayout upPage;
    private PrayerPanel prayer;
    private UsagePanel usage;
    private Map<String, Long> usageScores = new HashMap<>();
    private final GradientDrawable glowDrawable = new GradientDrawable();
    private int glowColor = Color.TRANSPARENT;
    private ValueAnimator glowAnim;
    // Top bar, left to right: bell, Inputs, Wi-Fi, Settings (icon-only round buttons).
    private static final int PILL_BELL = 0, PILL_INPUTS = 1, PILL_WIFI = 2, PILL_SETTINGS = 3;
    private View[] pills;
    private TextView bellBadge;
    private final Alerts.Listener alertsChanged = this::updateBell;
    private LinearLayout dockRow;
    private RecyclerView grid;
    private final GridAdapter adapter = new GridAdapter();
    private LinearLayout menu;
    // The glass dropdown: Inputs (from the pill) or an app's options (hold OK / Menu on a tile).
    private static final class MenuItem {
        final String label;
        final Runnable action;
        final int icon, iconColor;

        MenuItem(String label, Runnable action) {
            this(label, action, 0, 0);
        }

        MenuItem(String label, Runnable action, int icon, int iconColor) {
            this.label = label;
            this.action = action;
            this.icon = icon;
            this.iconColor = iconColor;
        }
    }

    private final List<MenuItem> menuItems = new ArrayList<>();
    private int menuReturnSection = TOP, menuReturnIdx;

    // Dock editing: MOVE wiggles the focused dock app and Left/Right shift it; REPLACE picks which
    // dock app the chosen app replaces (dock full).
    private static final int EDIT_NONE = 0, EDIT_MOVE = 1, EDIT_REPLACE = 2;
    private int editMode = EDIT_NONE;
    private AppEntry replaceWith;
    private String pendingDockFocus; // after a dock change, focus this app in the dock
    private ValueAnimator wiggle;
    private final DockConfig.Listener dockChanged = this::loadApps;
    private long dockStamp; // dock.txt as of the last load: reload if it changed behind our back

    private List<AppEntry> dock = new ArrayList<>();
    private List<AppEntry> apps = new ArrayList<>();

    private int section = DOCK, topIdx, dockIdx, gridIdx, menuIdx, nowIdx, upIdx;
    // Where the grid's scroll is heading (scrollGridToRow / return home); the highlight is placed
    // against this, so it goes straight to its final spot while the list scrolls under it.
    private int gridScrollTarget;
    private int armedKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean resumed;
    // Home was hidden (another app on top) since it was last resumed. Android pauses an
    // activity before handing it a new intent, so `resumed` can't tell "Home pressed on home".
    private boolean hiddenSinceResume;
    // Cards that refresh (System, Now Playing) run only while home is resumed AND has window
    // focus: the screensaver (a daydream window) covers home without pausing it.
    private boolean live;
    private boolean appsLoaded;
    private boolean tilesDropped;
    private long wallpaperStamp = -1;
    private boolean wallpaperFetching;
    private long bingAttemptAt;                 // throttle Bing refresh retries (e.g. offline)
    private java.time.LocalDate lastWallpaperDay; // reload the wallpaper after midnight (Bing daily)
    // Settings → Wallpaper list: the focused wallpaper, shown but not saved (null: the saved one).
    private String wallpaperPreview;
    private int wallpaperGen;
    private ConnectivityManager.NetworkCallback netCallback;

    private final Runnable reloadApps = this::loadApps;

    // Phone remote D-pad keys, attached only while the home screen is on the TV.
    private final RemoteBridge.Launcher remoteKeys = k -> main.post(() -> {
        switch (k) {
            case "up": onUp(); break;
            case "down": onDown(); break;
            case "left": onSide(-1); break;
            case "right": onSide(1); break;
            case "ok": onSelect(); break;
            case "back": onBack(); break;
            default: break;
        }
    });
    // Saved shortly after every move: on a 1 GB TV the launcher can be killed without onPause.
    private final Runnable saveFocus = this::saveFocus;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.removeCallbacks(reloadApps);
            main.postDelayed(reloadApps, 600);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    /** Same proportions on every TV: the screen is treated as 960 x 540 dp (see UiScale). */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        applyOverrideConfiguration(UiScale.config(base));
    }

    /** The screen's size or density changed (e.g. another resolution): lay out again for it. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (UiScale.config(getApplicationContext()).densityDpi != getResources().getConfiguration().densityDpi) {
            recreate();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        // Bell: Bluetooth names and the ACL connect/disconnect broadcasts need BLUETOOTH_CONNECT
        // on Android 12+, and it is a runtime permission. Ask on a fresh start; without it those
        // alerts simply don't appear (the rest of the bell is unaffected).
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 0);
        }

        page = findViewById(R.id.page);
        wallpaper = findViewById(R.id.wallpaper);
        highlight = findViewById(R.id.highlight);
        hijri = findViewById(R.id.hijri);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        hero = findViewById(R.id.hero);
        glow = findViewById(R.id.glow);
        dockRow = findViewById(R.id.dockRow);
        grid = findViewById(R.id.grid);
        menu = findViewById(R.id.menu);
        pills = new View[] {findViewById(R.id.pillBell), findViewById(R.id.pillInputs), findViewById(R.id.pillWifi),
                findViewById(R.id.pillSettings)};
        bellBadge = findViewById(R.id.bellBadge);
        bellBadge.setTypeface(Fonts.outfit(this, 600));
        homeViews = new View[] {findViewById(R.id.clockBlock), findViewById(R.id.pills), findViewById(R.id.dockBand),
                findViewById(R.id.sysPanel), findViewById(R.id.prayerPanel), findViewById(R.id.usagePanel),
                findViewById(R.id.peekFade), findViewById(R.id.nowPlaying)};
        system = new SystemPanel(this, findViewById(R.id.sysPanel), worker);
        nowPlaying = new NowPlayingPanel(this, findViewById(R.id.nowPlaying), worker, shown -> {
            // Playback ended while its buttons had focus: back to the dock.
            if (!shown && section == NOW && !dock.isEmpty()) moveFocus(DOCK, dockIdx);
        });
        prayer = new PrayerPanel(this, findViewById(R.id.prayerPanel), hijri, worker);
        upPage = findViewById(R.id.upPage);
        upPage.setTranslationY(-screenH);
        up = new UpScreen(this, upPage, worker, () -> {
            if (section != UP) return;
            if (up.count() == 0) upIdx = 0;
            else upIdx = clamp(upIdx, up.count());
            up.setFocused(upIdx);
        });
        usage = new UsagePanel(this, findViewById(R.id.usagePanel));
        alignSystemPanel();
        centreDatesOnClock();

        hijri.setTypeface(Fonts.outfit(this, 400));
        clock.setTypeface(Fonts.outfit(this, 200));
        date.setTypeface(Fonts.outfit(this, 400));
        hero.setTypeface(Fonts.outfit(this, 300));
        for (View p : pills) ((ImageView) p).setImageTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        Alerts.get().addListener(alertsChanged);
        updateBell();

        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(px(410));
        glowDrawable.setColors(new int[] {Color.TRANSPARENT, Color.TRANSPARENT});
        glow.setBackground(glowDrawable);

        layoutPage();

        grid.setLayoutManager(new GridLayoutManager(this, COLS));
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setAdapter(adapter);

        // The highlight re-reads its target whenever anything is about to draw, so it follows
        // page slides, grid scrolls and tile scale animations without any polling.
        findViewById(R.id.root).getViewTreeObserver().addOnPreDrawListener(() -> {
            updateHighlight(false);
            placeBellBadge();
            return true;
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        ContextCompat.registerReceiver(this, packageReceiver, pf, ContextCompat.RECEIVER_EXPORTED);

        DockConfig.addListener(dockChanged);
        settingsPage = findViewById(R.id.settingsPage);
        settingsScreen = new SettingsScreen(this, settingsPage, worker, new SettingsScreen.Host() {
            @Override
            public void onFocusMoved() {
                settingsPage.invalidate();
            }

            @Override
            public void openTvSettings() {
                startSafely(new Intent(Settings.ACTION_SETTINGS));
            }

            @Override
            public void previewWallpaper(String id) {
                wallpaperPreview = id;
                loadWallpaper();
            }
        });
        LauncherSettings.addListener(settingsChanged);
        applyCardSettings();
        loadApps(); // restores focus when the list arrives
        startForegroundService(new Intent(this, RemoteService.class));
    }

    // ---------------------------------------------------------------- layout

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private void place(View v, int top, int height) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = top;
        if (height != 0) lp.height = height;
        v.setLayoutParams(lp);
    }

    /** Grid's top edge on the home screen: its first row peeks PEEK dp above the screen bottom. */
    private int gridTop() {
        return screenH - px(PEEK + GRID_PAD_TOP);
    }

    /** How far the page slides up so the grid starts at GRID_TOP. */
    private int gridShift() {
        return gridTop() - px(GRID_TOP);
    }

    private void layoutPage() {
        place(page, 0, gridTop() + screenH - px(GRID_TOP));
        int bandH = px(TILE_H + 2 * BAND_PAD_V);
        int bandTop = screenH - px(PEEK + BAND_GAP) - bandH;
        place(findViewById(R.id.dockBand), bandTop, 0);
        int heroTop = bandTop - px(HERO_GAP + HERO_H);
        place(hero, heroTop, 0);
        place(glow, heroTop + px(HERO_H / 2) - px(410), 0);
        place(grid, gridTop(), screenH - px(GRID_TOP));
        // Shadow from just under the dock band to the screen edge, over the peek.
        View fade = findViewById(R.id.peekFade);
        ViewGroup.LayoutParams flp = fade.getLayoutParams();
        flp.height = px(PEEK + BAND_GAP - 4);
        fade.setLayoutParams(flp);
        int side = (screenW - px(COLS * COL_W)) / 2;
        grid.setPadding(side, px(GRID_PAD_TOP), side, px(GRID_PAD_BOTTOM));
    }

    /**
     * Puts the middle of the two date lines on the middle of the clock's digits. The digits sit
     * between the baseline and cap height, not in the middle of the TextView (which also holds
     * the descent), so the offset is measured from the glyphs.
     */
    private void centreDatesOnClock() {
        View col = findViewById(R.id.dateCol);
        android.graphics.Rect digits = new android.graphics.Rect();
        col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            clock.getPaint().getTextBounds("0", 0, 1, digits);
            float digitsMid = clock.getTop() + clock.getBaseline() + digits.exactCenterY();
            float colMid = t + (b - t) / 2f;
            col.setTranslationY(Math.round(digitsMid - colMid));
        });
    }

    /** The stats card starts under the Inputs pill and ends with the pill row, whatever the Wi-Fi name. */
    private void alignSystemPanel() {
        View row = findViewById(R.id.pills), card = findViewById(R.id.sysPanel);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // Fixed width, right edge under the Settings pill: the Wi-Fi pill's label changes
            // ("No internet", "Disconnected", network names) mustn't resize the cards.
            int right = r - row.getPaddingEnd();
            int width = px(RIGHT_CARD_W);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
            int end = screenW - right;
            if (lp.width != width || lp.getMarginEnd() != end) {
                lp.width = width;
                lp.setMarginEnd(end);
                card.post(() -> card.setLayoutParams(lp));
            }
        });
        // Now Playing sits under the system card, same width and edges.
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeNowPlaying());
    }

    /** Now Playing: under the system card, same width and edges; in its place when it's off. */
    private void placeNowPlaying() {
        View card = findViewById(R.id.sysPanel), np = findViewById(R.id.nowPlaying);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) np.getLayoutParams();
        ViewGroup.MarginLayoutParams sp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        boolean below = card.getVisibility() != View.GONE;
        if (below && card.getBottom() == 0) return; // not laid out yet
        int top = below ? card.getBottom() + px(10) : sp.topMargin;
        int width = sp.width > 0 ? sp.width : card.getWidth();
        if (lp.width != width || lp.getMarginEnd() != sp.getMarginEnd() || lp.topMargin != top) {
            lp.width = width;
            lp.setMarginEnd(sp.getMarginEnd());
            lp.topMargin = top;
            np.post(() -> np.setLayoutParams(lp));
        }

    }

    private ImageView newDockTile() {
        FrameLayout column = new FrameLayout(this);
        column.setClipChildren(false);
        ImageView tile = new ImageView(this);
        tile.setBackgroundResource(R.drawable.tile_bg);
        tile.setClipToOutline(true);
        tile.setScaleType(ImageView.ScaleType.FIT_XY);
        column.addView(tile, new FrameLayout.LayoutParams(px(TILE_W), px(TILE_H), Gravity.CENTER));
        dockRow.addView(column, new LinearLayout.LayoutParams(px(COL_W), px(TILE_H)));
        return tile;
    }

    // ---------------------------------------------------------------- apps

    /**
     * App list, dock and usage ranking are read on the worker (PackageManager and usage-stats
     * queries take a few hundred ms on this TV), so the home screen draws first and fills in.
     */
    private void loadApps() {
        worker.execute(() -> {
            List<AppEntry> all = AppRepository.load(this);
            long stamp = DockConfig.stamp(this);
            List<String> dockPkgs = DockConfig.load(this);
            dockStamp = stamp;
            Map<String, Long> scores = UsageScores.week(this);
            main.post(() -> applyApps(all, dockPkgs, scores));
        });
    }

    /** Dock then grid, in the order the cards and the grid show them. */
    private List<AppEntry> allForUsage() {
        List<AppEntry> all = new ArrayList<>(dock.size() + apps.size());
        all.addAll(dock);
        all.addAll(apps);
        return all;
    }

    /**
     * Forgets cached tiles of apps that are gone (uninstalled, or no longer launchable), so their
     * bitmaps aren't kept until the process dies. A tile the grid is still showing is not
     * recycled: it hasn't rebound yet, and drawing a recycled bitmap crashes.
     */
    private void pruneTileCache(List<AppEntry> all) {
        Set<String> live = new HashSet<>();
        for (AppEntry e : all) live.add(e.cacheKey);
        Set<Bitmap> onScreen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < grid.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(grid.getChildAt(i));
            if (!(vh instanceof Holder)) continue;
            Drawable d = ((Holder) vh).tile.getDrawable();
            if (d instanceof BitmapDrawable) onScreen.add(((BitmapDrawable) d).getBitmap());
        }
        for (Iterator<AppEntry> it = rendered.values().iterator(); it.hasNext(); ) {
            AppEntry e = it.next();
            if (live.contains(e.cacheKey)) continue;
            it.remove();
            if (e.tile != null && !onScreen.contains(e.tile)) e.tile.recycle();
            e.tile = null;
        }
    }

    private void applyApps(List<AppEntry> all, List<String> dockPkgs, Map<String, Long> scores) {
        String focusedPkg = focusedPackage();
        Map<String, AppEntry> byPkg = new HashMap<>();
        for (AppEntry e : all) byPkg.put(e.pkg, e);

        List<AppEntry> newDock = new ArrayList<>();
        for (String pkg : dockPkgs) {
            AppEntry e = byPkg.get(pkg);
            if (e != null && !newDock.contains(e) && newDock.size() < DOCK_SIZE) newDock.add(e);
        }
        List<AppEntry> newApps = new ArrayList<>(all);
        newApps.removeAll(newDock);
        usageScores = scores;
        sortByUsage(newApps);

        pruneTileCache(all);
        List<AppEntry> toRender = new ArrayList<>();
        for (AppEntry e : all) {
            AppEntry done = rendered.get(e.cacheKey);
            if (done != null) {
                e.tile = done.tile;
                e.glow = done.glow;
            } else {
                toRender.add(e);
            }
        }
        dock = newDock;
        apps = newApps;

        stopWiggle();
        dockRow.removeAllViews();
        dockTiles.clear();
        for (AppEntry e : dock) {
            ImageView tile = newDockTile();
            tile.setImageBitmap(e.tile);
            dockTiles.add(tile);
        }
        findViewById(R.id.dockBand).setVisibility(dock.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        adapter.notifyDataSetChanged();

        // Keep focus on the same app across reloads (installs, updates).
        if (focusedPkg != null) {
            int d = indexOf(dock, focusedPkg), g = indexOf(apps, focusedPkg);
            if (section == DOCK && d >= 0) dockIdx = d;
            if (section == GRID && g >= 0) gridIdx = g;
        }
        dockIdx = clamp(dockIdx, dock.size());
        gridIdx = clamp(gridIdx, apps.size());
        if (section == GRID && apps.isEmpty()) section = DOCK;
        if (section == DOCK && dock.isEmpty()) section = TOP;
        usage.render(allForUsage(), usageScores);
        if (!appsLoaded) {
            appsLoaded = true;
            restoreFocus(); // needs the app list; first load only
        } else {
            refreshFocusLook();
        }
        if (pendingDockFocus != null) {
            int d = indexOf(dock, pendingDockFocus);
            pendingDockFocus = null;
            if (d >= 0) moveFocus(DOCK, d);
        }

        // Dock first so the home row fills in before the grid below it.
        toRender.sort((a, b) -> Boolean.compare(!dock.contains(a), !dock.contains(b)));
        int w = px(TILE_W), h = px(TILE_H);
        for (AppEntry e : toRender) {
            worker.execute(() -> {
                Bitmap drawn = TileRenderer.render(this, e, w, h);
                int color = TileRenderer.sampleGlow(drawn);
                // Hardware bitmap: the pixels live once, in GPU memory, instead of a heap copy
                // plus the GPU texture.
                Bitmap hw = drawn.copy(Bitmap.Config.HARDWARE, false);
                Bitmap bmp = hw != null ? hw : drawn;
                if (hw != null) drawn.recycle();
                main.post(() -> {
                    e.tile = bmp;
                    e.glow = color;
                    rendered.put(e.cacheKey, e);
                    onTileReady(e);
                });
            });
        }
    }

    /** Most used first (last 7 days); apps with no recorded use stay A-Z. List.sort is stable. */
    private void sortByUsage(List<AppEntry> list) {
        UsageScores.sort(list, usageScores);
    }

    /** On every return home: re-rank the grid and refresh the card, keeping focus on the same app. */
    private void refreshUsage() {
        if (!appsLoaded) return; // the first loadApps() ranks them
        worker.execute(() -> {
            Map<String, Long> scores = UsageScores.week(this);
            main.post(() -> applyUsage(scores));
        });
    }

    private void applyUsage(Map<String, Long> scores) {
        usageScores = scores;
        List<AppEntry> sorted = new ArrayList<>(apps);
        sortByUsage(sorted);
        if (!sorted.equals(apps)) {
            String focusedPkg = section == GRID ? focusedPackage() : null;
            apps = sorted;
            if (focusedPkg != null) gridIdx = Math.max(0, indexOf(apps, focusedPkg));
            adapter.notifyDataSetChanged();
            if (section == GRID) grid.post(() -> scrollGridToRow(gridIdx / COLS, false));
        }
        List<AppEntry> all = new ArrayList<>(dock);
        all.addAll(apps);
        usage.render(all, usageScores);
    }

    private void onTileReady(AppEntry e) {
        int d = dock.indexOf(e);
        if (d >= 0) {
            dockTiles.get(d).setImageBitmap(e.tile);
            if (d == dockIdx) setGlow(e.glow, true);
        }
        int g = apps.indexOf(e);
        if (g >= 0) adapter.notifyItemChanged(g, PAYLOAD_TILE);
    }

    private static int indexOf(List<AppEntry> list, String pkg) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private static int clamp(int i, int size) {
        return Math.max(0, Math.min(i, size - 1));
    }

    private String focusedPackage() {
        if (section == GRID && gridIdx < apps.size()) return apps.get(gridIdx).pkg;
        if (dockIdx < dock.size()) return dock.get(dockIdx).pkg;
        return null;
    }

    private void launch(AppEntry e) {
        try {
            startActivity(e.launch);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, e.label + " can't be opened", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- focus

    private void restoreFocus() {
        String pkg = prefs.getString("pkg", null);
        int g = indexOf(apps, pkg), d = indexOf(dock, pkg);
        if (prefs.getInt("section", DOCK) == GRID && g >= 0) {
            section = GRID;
            gridIdx = g;
            page.setTranslationY(-gridShift());
            grid.post(() -> scrollGridToRow(gridIdx / COLS, false));
        } else if (!dock.isEmpty()) {
            section = DOCK;
            dockIdx = Math.max(0, d);
        } else {
            section = TOP;
        }
        refreshFocusLook();
    }

    private void saveFocus() {
        String pkg = focusedPackage();
        prefs.edit().putInt("section", section == GRID ? GRID : DOCK).putString("pkg", pkg).apply();
    }

    private int indexIn(int sec) {
        switch (sec) {
            case TOP: return topIdx;
            case DOCK: return dockIdx;
            case GRID: return gridIdx;
            case NOW: return nowIdx;
            case UP: return upIdx;
            case SETTINGS: return 0;
            default: return menuIdx;
        }
    }

    private void moveFocus(int newSection, int newIndex) {
        int oldSection = section;
        applyLook(section, indexIn(section), false, true);
        section = newSection;
        switch (newSection) {
            case TOP: topIdx = newIndex; break;
            case DOCK: dockIdx = newIndex; break;
            case GRID: gridIdx = newIndex; break;
            case NOW: nowIdx = newIndex; break;
            case UP: upIdx = newIndex; break;
            case SETTINGS: break; // SettingsScreen keeps its own focus
            default: menuIdx = newIndex; break;
        }
        applyLook(section, newIndex, true, true);
        // The running-apps screen sits a screen above home: slide both down to show it.
        if (onUpPage(oldSection) != onUpPage(newSection)) {
            float y = onUpPage(newSection) ? screenH : 0;
            page.animate().translationY(y).setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
            upPage.animate().translationY(y - screenH).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            setSystemVisible(!onUpPage(newSection));
            nowPlaying.setVisible(!onUpPage(newSection));
            showPeekFade(!onUpPage(newSection));
            if (onUpPage(newSection)) up.reload();
            else up.resetStatus();
        }
        if (newSection == UP) up.setFocused(newIndex);
        // Settings (gear) fades in over home.
        if ((oldSection == SETTINGS) != (newSection == SETTINGS)) {
            boolean open = newSection == SETTINGS;
            page.animate().alpha(open ? 0f : 1f).setDuration(220).start(); // bell badge follows
            settingsPage.animate().cancel();
            if (open) {
                settingsPage.setAlpha(0f);
                settingsPage.setVisibility(View.VISIBLE);
                settingsPage.animate().alpha(1f).setDuration(220).start();
            } else {
                settingsPage.animate().alpha(0f).setDuration(180)
                        .withEndAction(() -> settingsPage.setVisibility(View.GONE)).start();
                if (wallpaperPreview != null) { // left Settings mid-preview (Home): saved one back
                    wallpaperPreview = null;
                    loadWallpaper();
                }
            }
            setSystemVisible(!open);
            nowPlaying.setVisible(!open);
            showPeekFade(!open);
        }
        if (onGridPage(oldSection) != onGridPage(newSection)) {
            boolean toGrid = onGridPage(newSection);
            page.animate().translationY(toGrid ? -gridShift() : 0)
                    .setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
            setHomeVisible(!toGrid, true);
            // Back on the home screen the peek shows the first row again.
            int scrolled = gridScroll();
            if (!toGrid && scrolled != 0) {
                gridScrollTarget = 0;
                grid.smoothScrollBy(0, -scrolled, new DecelerateInterpolator(2f));
            }
            setSystemVisible(!toGrid);
            nowPlaying.setVisible(!toGrid);
        }
        if (newSection == GRID) scrollGridToRow(newIndex / COLS, true);
        updateHero(true);
        page.invalidate();
        main.removeCallbacks(saveFocus);
        main.postDelayed(saveFocus, 1000);
    }

    /** The app grid page is showing (the grid itself, or a menu opened on a grid tile). */
    private boolean onGridPage(int sec) {
        return sec == GRID || (sec == MENU && menuReturnSection == GRID);
    }

    private boolean onUpPage(int sec) {
        return sec == UP || (sec == MENU && menuReturnSection == UP);
    }

    /** Re-applies every focus visual without animation (after reloads / restore). */
    private void refreshFocusLook() {
        for (int i = 0; i < pills.length; i++) applyLook(TOP, i, section == TOP && i == topIdx, false);
        for (int i = 0; i < dockTiles.size(); i++) applyLook(DOCK, i, section == DOCK && i == dockIdx, false);
        for (int i = 0; i < NowPlayingPanel.BUTTONS; i++) applyLook(NOW, i, section == NOW && i == nowIdx, false);
        adapter.notifyDataSetChanged();
        setHomeVisible(!onGridPage(section), false);
        if (onUpPage(section) || section == SETTINGS) findViewById(R.id.peekFade).setAlpha(0f);
        setSystemVisible(!onGridPage(section) && !onUpPage(section) && section != SETTINGS);
        nowPlaying.setVisible(!onGridPage(section) && !onUpPage(section) && section != SETTINGS);
        updateHero(false);
        page.invalidate();
    }

    /** The clock, pills and dock fade out while the grid has the screen; the peek is dimmed on home. */
    /**
     * The peek shadow sits at the screen's bottom edge, outside the sliding page: hidden while
     * the running-apps screen or Settings is up (the grid hides it via setHomeVisible).
     */
    private void showPeekFade(boolean show) {
        View fade = findViewById(R.id.peekFade);
        fade.animate().cancel();
        fade.animate().alpha(show ? 1f : 0f).setDuration(show ? 320 : 220).start();
    }

    private void setHomeVisible(boolean visible, boolean animate) {
        View topFade = findViewById(R.id.gridTopFade);
        topFade.animate().cancel();
        if (animate) topFade.animate().alpha(visible ? 0f : 1f).setDuration(visible ? 220 : 320).start();
        else topFade.setAlpha(visible ? 0f : 1f);
        grid.animate().cancel();
        if (animate) grid.animate().alpha(visible ? PEEK_ALPHA : 1f).setDuration(visible ? 320 : 220).start();
        else grid.setAlpha(visible ? PEEK_ALPHA : 1f);
        for (View v : homeViews) {
            v.animate().cancel();
            if (animate) v.animate().alpha(visible ? 1f : 0f).setDuration(visible ? 320 : 220).start();
            else v.setAlpha(visible ? 1f : 0f);
        }
    }

    private void applyLook(int sec, int idx, boolean on, boolean animate) {
        View v = null;
        View label = null;
        float scale = on ? TILE_SCALE : 1f;
        if (sec == TOP && idx < pills.length) {
            v = pills[idx];
            scale = on ? PILL_SCALE : 1f;
        } else if (sec == DOCK && idx < dockTiles.size()) {
            v = dockTiles.get(idx);
        } else if (sec == NOW && idx < NowPlayingPanel.BUTTONS) {
            v = nowPlaying.button(idx);
            scale = on ? NOW_SCALE : 1f;
        } else if (sec == GRID) {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(idx);
            if (vh instanceof Holder) {
                v = ((Holder) vh).tile;
                label = ((Holder) vh).name;
            }
        } else if (sec == MENU && idx < menu.getChildCount()) {
            ((TextView) menu.getChildAt(idx)).setTextColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
            return;
        }
        if (v == null) return;
        v.animate().cancel();
        if (animate) {
            v.animate().scaleX(scale).scaleY(scale).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            v.setScaleX(scale);
            v.setScaleY(scale);
        }
        if (label != null) {
            label.animate().cancel();
            if (animate) label.animate().alpha(on ? 1f : 0f).setDuration(180).start();
            else label.setAlpha(on ? 1f : 0f);
        }
    }

    /**
     * How far the grid is scrolled, read from the first laid-out row (all rows are the same
     * height). Not tracked from onScrolled: that count drifted after data reloads (by whole rows),
     * which left a gap at the top and cut off the bottom row.
     */
    private int gridScroll() {
        if (grid.getChildCount() == 0) return 0;
        View first = grid.getChildAt(0);
        int pos = grid.getChildAdapterPosition(first);
        if (pos == RecyclerView.NO_POSITION) return 0;
        return (pos / COLS) * first.getHeight() + grid.getPaddingTop() - first.getTop();
    }

    private void scrollGridToRow(int row, boolean smooth) {
        int rows = (apps.size() + COLS - 1) / COLS;
        int content = rows * px(ROW_PITCH) + px(GRID_PAD_TOP + GRID_PAD_BOTTOM);
        int max = Math.max(0, content - grid.getHeight());
        // Keep one row of context above the focused row.
        int target = Math.max(0, Math.min(max, (row - 1) * px(ROW_PITCH)));
        gridScrollTarget = target;
        int dy = target - gridScroll();
        if (dy == 0) return;
        if (smooth) grid.smoothScrollBy(0, dy, new DecelerateInterpolator(1.5f));
        else grid.scrollBy(0, dy);
    }

    private void updateHighlight(boolean snap) {
        View v = null;
        float scale = 1f, radius = px(TILE_RADIUS);
        switch (section) {
            case TOP:
                v = pills[topIdx];
                scale = PILL_SCALE;
                radius = v.getHeight() * scale / 2f;
                break;
            case DOCK:
                if (dockIdx < dockTiles.size()) v = dockTiles.get(dockIdx);
                scale = TILE_SCALE;
                radius *= scale;
                break;
            case GRID:
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(gridIdx);
                if (vh instanceof Holder) v = ((Holder) vh).tile;
                scale = TILE_SCALE;
                radius *= scale;
                break;
            case MENU:
                v = menu.getChildAt(menuIdx);
                break;
            case NOW:
                v = nowPlaying.button(nowIdx);
                scale = NOW_SCALE;
                radius = v.getHeight() * scale / 2f;
                break;
            case UP:
                v = up.row(upIdx);
                break;
            case SETTINGS:
                v = settingsScreen.focused();
                break;
        }
        // Nothing to frame while the running-apps list is empty or loading.
        highlight.setVisibility((section == UP || section == SETTINGS) && v == null ? View.INVISIBLE : View.VISIBLE);
        if (v == null || v.getWidth() == 0) return;
        // Frame the highlight rides with: the menu (fixed), the grid (page slide + list scroll),
        // or the rest of the page (page slide).
        if (section == MENU) highlight.setFrame(0, 0);
        else if (section == UP) highlight.setFrame(3, upPage.getTranslationY());
        else if (section == SETTINGS) highlight.setFrame(4, 0);
        else if (section == GRID) highlight.setFrame(2 + gridScrollTarget, page.getTranslationY() - gridScrollTarget);
        else highlight.setFrame(1, page.getTranslationY());
        highlight.getLocationInWindow(loc);
        int ox = loc[0], oy = loc[1];
        v.getLocationInWindow(loc);
        float cs = v.getScaleX();
        float cx = loc[0] - ox + v.getWidth() * cs / 2f;
        float cy = loc[1] - oy + v.getHeight() * cs / 2f;
        // Grid: where the tile will be once the scroll in progress finishes.
        if (section == GRID) cy += gridScroll() - gridScrollTarget;
        float hw = v.getWidth() * scale / 2f, hh = v.getHeight() * scale / 2f;
        highlight.moveTo(new RectF(cx - hw, cy - hh, cx + hw, cy + hh), radius, snap);
    }

    private void updateHero(boolean animate) {
        boolean show = section == DOCK && !dock.isEmpty();
        if (dockIdx < dock.size()) {
            AppEntry e = dock.get(dockIdx);
            if (editMode == EDIT_MOVE) hero.setText("Move " + e.label + "  ◀ ▶   ·   OK to place");
            else if (editMode == EDIT_REPLACE && replaceWith != null) hero.setText("Replace which app with " + replaceWith.label + "?");
            else hero.setText(e.label);
            setGlow(e.glow, animate);
        }
        float a = show ? 1f : 0f;
        if (animate) {
            hero.animate().alpha(a).setDuration(220).start();
            glow.animate().alpha(a).setDuration(320).start();
        } else {
            hero.setAlpha(a);
            glow.setAlpha(a);
        }
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) {
            applyGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(glowColor, color);
        glowAnim.setDuration(350);
        glowAnim.addUpdateListener(a -> applyGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void applyGlow(int color) {
        glowColor = color;
        int rgb = color & 0x00FFFFFF;
        glowDrawable.setColors(new int[] {0x70000000 | rgb, 0x2A000000 | rgb, rgb});
    }

    // ---------------------------------------------------------------- keys

    // The launcher owns Back: it closes the menu / edit mode, and a TV has no gesture navigation
    // to register a callback with. On phones the same key still works; only the predictive-back
    // animation is skipped.
    @SuppressLint("GestureBackNavigation")
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) onUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) onDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) onSide(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) onSide(1);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if ((section != DOCK && section != GRID && section != UP) || editMode != EDIT_NONE) {
                    return super.dispatchKeyEvent(e);
                }
                if (down && e.getRepeatCount() == 0) openAppMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Hold OK on an app: its options (the release mustn't also open the app).
                if (down && e.getRepeatCount() == 1 && armedKey == code && editMode == EDIT_NONE
                        && (section == DOCK || section == GRID || section == UP)) {
                    armedKey = KeyEvent.KEYCODE_UNKNOWN;
                    openAppMenu();
                    return true;
                }
                // fall through
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // Act on release, and only for presses that started here, so the OK/Back
                // release from an app that just closed doesn't trigger anything.
                if (down) {
                    if (e.getRepeatCount() == 0) armedKey = code;
                } else if (armedKey == code) {
                    armedKey = KeyEvent.KEYCODE_UNKNOWN;
                    if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) onBack();
                    else onSelect();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void onUp() {
        if (editMode != EDIT_NONE) return; // finish (OK) or cancel (Back) first
        if (section == SETTINGS) {
            settingsScreen.up();
            return;
        }
        switch (section) {
            case DOCK:
                if (nowPlaying.isShown()) moveFocus(NOW, NowPlayingPanel.PLAY_PAUSE);
                else moveFocus(TOP, topIdx);
                break;
            case NOW:
                moveFocus(TOP, topIdx);
                break;
            case TOP:
                moveFocus(UP, 0);
                break;
            case UP:
                if (upIdx > 0) moveFocus(UP, upIdx - 1);
                break;
            case GRID:
                if (gridIdx >= COLS) moveFocus(GRID, gridIdx - COLS);
                else if (!dock.isEmpty()) moveFocus(DOCK, Math.min(gridIdx, dock.size() - 1));
                else moveFocus(TOP, topIdx);
                break;
            case MENU:
                if (menuIdx > 0) moveFocus(MENU, menuIdx - 1);
                else closeMenu();
                break;
            default:
                break;
        }
    }

    private void onDown() {
        if (editMode != EDIT_NONE) return;
        if (section == SETTINGS) {
            settingsScreen.down();
            return;
        }
        int n = apps.size();
        switch (section) {
            case TOP:
                if (nowPlaying.isShown()) moveFocus(NOW, NowPlayingPanel.PLAY_PAUSE);
                else if (!dock.isEmpty()) moveFocus(DOCK, dockIdx);
                else if (n > 0) moveFocus(GRID, 0);
                break;
            case NOW:
                if (!dock.isEmpty()) moveFocus(DOCK, dockIdx);
                break;
            case UP:
                if (upIdx < up.count() - 1) moveFocus(UP, upIdx + 1);
                else moveFocus(TOP, topIdx); // past the last app: back home
                break;
            case DOCK:
                if (n > 0) moveFocus(GRID, Math.min(dockIdx, Math.min(COLS, n) - 1));
                break;
            case GRID:
                int next = gridIdx + COLS;
                if (next < n) moveFocus(GRID, next);
                else if (gridIdx / COLS < (n - 1) / COLS) moveFocus(GRID, n - 1);
                break;
            case MENU:
                if (menuIdx < menu.getChildCount() - 1) moveFocus(MENU, menuIdx + 1);
                break;
        }
    }

    private void onSide(int dir) {
        if (section == SETTINGS) {
            if (dir < 0) settingsScreen.left();
            else settingsScreen.right();
            return;
        }
        if (editMode == EDIT_MOVE) {
            shiftDockApp(dir);
            return;
        }
        switch (section) {
            case TOP: {
                int i = topIdx + dir;
                if (i >= 0 && i < pills.length) moveFocus(TOP, i);
                break;
            }
            case DOCK: {
                int i = dockIdx + dir;
                if (i >= 0 && i < dock.size()) moveFocus(DOCK, i);
                break;
            }
            case NOW: {
                int i = nowIdx + dir;
                if (i >= 0 && i < NowPlayingPanel.BUTTONS) moveFocus(NOW, i);
                break;
            }
            case GRID: {
                int col = gridIdx % COLS, i = gridIdx + dir;
                if (dir < 0 ? col > 0 : col < COLS - 1 && i < apps.size()) moveFocus(GRID, i);
                break;
            }
            default:
                break;
        }
    }

    private void onSelect() {
        if (editMode == EDIT_MOVE) {
            finishMove();
            return;
        }
        if (editMode == EDIT_REPLACE) {
            finishReplace();
            return;
        }
        switch (section) {
            case SETTINGS:
                settingsScreen.select();
                break;
            case UP:
                int kind = up.kind(upIdx);
                if (kind == UpScreen.FOOTER) up.toggleHidden();
                else if (kind == UpScreen.HIDDEN) openUpMenu();
                else up.forceStop(upIdx);
                break;
            case TOP:
                if (topIdx == PILL_BELL) openBell();
                else if (topIdx == PILL_INPUTS) openMenu();
                else if (topIdx == PILL_WIFI) startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else openSettings();
                break;
            case DOCK:
                if (dockIdx < dock.size()) launch(dock.get(dockIdx));
                break;
            case GRID:
                if (gridIdx < apps.size()) launch(apps.get(gridIdx));
                break;
            case NOW:
                nowPlaying.press(nowIdx);
                break;
            case MENU:
                if (menuIdx < menuItems.size()) {
                    Runnable action = menuItems.get(menuIdx).action;
                    closeMenu();
                    action.run();
                }
                break;
        }
    }

    private void onBack() {
        if (editMode == EDIT_MOVE) {
            finishMove();
            return;
        }
        if (editMode == EDIT_REPLACE) {
            editMode = EDIT_NONE;
            replaceWith = null;
            moveFocus(GRID, gridIdx); // back to the app that was going to be added
            return;
        }
        if (section == MENU) closeMenu();
        else if (section == SETTINGS) {
            if (settingsScreen.back()) moveFocus(TOP, PILL_SETTINGS);
        } else if (section == UP) moveFocus(TOP, topIdx);
        else if (section == GRID || section == TOP || section == NOW) {
            if (!dock.isEmpty()) moveFocus(DOCK, dockIdx);
        }
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- inputs

    /** Keeps the badge on the bell's top-right corner, following its zoom, page slides and fades. */
    private void placeBellBadge() {
        if (bellBadge.getVisibility() != View.VISIBLE) return;
        View bell = pills[PILL_BELL];
        if (bell.getWidth() == 0) return;
        bell.getLocationInWindow(loc);
        int bx = loc[0], by = loc[1];
        ((View) bellBadge.getParent()).getLocationInWindow(loc);
        float s = bell.getScaleX();
        // scaled bell's top-right corner (scale is about the centre)
        float right = bx - loc[0] + bell.getWidth() * (1 + s) / 2f;
        float top = by - loc[1] + bell.getHeight() * (1 - s) / 2f;
        float x = right - bellBadge.getWidth() + px(6), y = top - px(6);
        if (bellBadge.getTranslationX() != x) bellBadge.setTranslationX(x);
        if (bellBadge.getTranslationY() != y) bellBadge.setTranslationY(y);
        // The badge sits outside the home page (so the glass ring never covers it), so it fades
        // with both the top bar and the page around it (grid, Settings).
        float a = page.getAlpha() * findViewById(R.id.pills).getAlpha();
        if (bellBadge.getAlpha() != a) bellBadge.setAlpha(a);
    }

    /** Badge with the unread count; the bell turns amber while there's something new. */
    private void updateBell() {
        int n = Alerts.get().unread();
        bellBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        bellBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        bellBadge.post(this::placeBellBadge);
        ((ImageView) pills[PILL_BELL]).setImageTintList(android.content.res.ColorStateList.valueOf(
                n > 0 ? 0xFFFFB340 : 0xE6FFFFFF));
    }

    /** The bell's list: newest 5 alerts, OK opens one (and dismisses it), then Clear all. */
    private void openBell() {
        Alerts al = Alerts.get();
        List<Alerts.Alert> list = al.list();
        List<MenuItem> items = new ArrayList<>();
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            Alerts.Alert a = list.get(i);
            int icon = a.type == Alerts.PLAY ? R.drawable.ic_update : a.type == Alerts.HDMI ? R.drawable.ic_input
                    : a.type == Alerts.BLUETOOTH ? R.drawable.ic_bluetooth : R.drawable.ic_ram;
            int color = a.type == Alerts.MEMORY ? 0xFFFFB340 : a.type == Alerts.PLAY ? 0xFF63A4FF : 0xFF63D489;
            String label = a.title + (a.text == null || a.text.isEmpty() ? "" : " — " + a.text) + "  ·  " + ago(a.time);
            items.add(new MenuItem(label, () -> {
                al.dismiss(a);
                if (a.action != null) startSafely(new Intent(a.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }, icon, color));
        }
        if (items.isEmpty()) items.add(new MenuItem("No notifications", () -> { }, R.drawable.ic_bell, 0x8CFFFFFF));
        else items.add(new MenuItem("Clear all", al::clear));
        al.markAllSeen();
        View bell = pills[PILL_BELL];
        bell.getLocationInWindow(loc);
        int w = 420;
        showMenu(items, Math.min(loc[0], screenW - px(w + 24)), loc[1] + Math.round(bell.getHeight() * PILL_SCALE) + px(10), w);
    }

    private static String ago(long time) {
        long m = Math.max(0, System.currentTimeMillis() - time) / 60_000;
        return m < 1 ? "now" : m < 60 ? m + "m" : m < 1440 ? (m / 60) + "h" : (m / 1440) + "d";
    }

    /** The Inputs pill: HDMI / AV / Live TV. */
    private void openMenu() {
        List<MenuItem> items = new ArrayList<>();
        for (TvInputs.Item in : TvInputs.load(this)) items.add(new MenuItem(in.label, () -> startSafely(in.intent)));
        if (items.isEmpty()) return;
        View pill = pills[PILL_INPUTS];
        pill.getLocationInWindow(loc);
        showMenu(items, Math.min(loc[0], screenW - px(240 + 24)), loc[1] + Math.round(pill.getHeight() * PILL_SCALE) + px(10));
    }

    /** Hold OK / Menu on a dock or grid tile: dock editing, Open and App info. */
    private void openAppMenu() {
        if (section == UP) {
            openUpMenu();
            return;
        }
        boolean inDock = section == DOCK;
        AppEntry e = inDock ? (dockIdx < dock.size() ? dock.get(dockIdx) : null)
                : (gridIdx < apps.size() ? apps.get(gridIdx) : null);
        if (e == null) return;
        List<MenuItem> items = new ArrayList<>();
        if (inDock) {
            if (dock.size() > 1) items.add(new MenuItem("Move", this::startMove));
            items.add(new MenuItem("Remove from dock", () -> {
                List<String> d = dockPkgs();
                d.remove(e.pkg);
                DockConfig.save(this, d);
            }));
        } else if (dock.size() < DOCK_SIZE) {
            items.add(new MenuItem("Add to dock", () -> {
                pendingDockFocus = e.pkg;
                List<String> d = dockPkgs();
                d.add(e.pkg);
                DockConfig.save(this, d);
            }));
        } else {
            items.add(new MenuItem("Replace a dock app…", () -> startReplace(e)));
        }
        items.add(new MenuItem("Open " + e.label, () -> launch(e)));
        // The system's page for the app: storage, cache, permissions, force stop, disable.
        items.add(new MenuItem("App info", () -> startSafely(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));

        View tile = inDock ? dockTiles.get(dockIdx) : focusedGridTile();
        int x = screenW / 2 - px(120), y = screenH / 2;
        int menuH = px(46) * items.size() + px(16);
        if (tile != null) {
            tile.getLocationInWindow(loc);
            int w = Math.round(tile.getWidth() * tile.getScaleX()), h = Math.round(tile.getHeight() * tile.getScaleY());
            x = Math.max(px(24), Math.min(screenW - px(264), loc[0] + w / 2 - px(120)));
            // Above the tile when there's room (always for the dock), else below it.
            y = loc[1] - menuH - px(14) >= px(24) ? loc[1] - menuH - px(14) : loc[1] + h + px(14);
        }
        showMenu(items, x, y);
    }

    /** Hold OK on a running app (or OK on a hidden one): hide / show it, force close, open. */
    private void openUpMenu() {
        RunningApps.App a = up.app(upIdx);
        View row = up.row(upIdx);
        if (a == null || row == null) return;
        int i = upIdx;
        List<MenuItem> items = new ArrayList<>();
        if (a.hidden) {
            items.add(new MenuItem("Show in list again", () -> up.setHidden(i, false)));
        } else {
            items.add(new MenuItem("Hide from this list", () -> up.setHidden(i, true)));
            items.add(new MenuItem("Force close", () -> up.forceStop(i)));
        }
        items.add(new MenuItem("Open " + a.entry.label, () -> launch(a.entry)));
        // Beside the row, in the empty second column.
        row.getLocationInWindow(loc);
        int menuH = px(46) * items.size() + px(16);
        int x = Math.min(screenW - px(264), loc[0] + row.getWidth() + px(24));
        int y = Math.max(px(24), Math.min(screenH - menuH - px(24), loc[1] + row.getHeight() / 2 - menuH / 2));
        showMenu(items, x, y);
    }

    private View focusedGridTile() {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(gridIdx);
        return vh instanceof Holder ? ((Holder) vh).tile : null;
    }

    private void showMenu(List<MenuItem> items, int x, int y) {
        showMenu(items, x, y, 240);
    }

    private void showMenu(List<MenuItem> items, int x, int y, int widthDp) {
        menuItems.clear();
        menuItems.addAll(items);
        menuReturnSection = section;
        menuReturnIdx = indexIn(section);
        menu.removeAllViews();
        for (MenuItem item : items) {
            TextView row = new TextView(this);
            row.setText(item.label);
            row.setTextSize(16);
            row.setTypeface(Fonts.outfit(this, 400));
            row.setTextColor(0xB3FFFFFF);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(px(16), 0, px(16), 0);
            if (item.icon != 0) {
                Drawable d = getDrawable(item.icon).mutate();
                d.setTint(item.iconColor);
                d.setBounds(0, 0, px(20), px(20));
                row.setCompoundDrawablesRelative(d, null, null, null);
                row.setCompoundDrawablePadding(px(12));
            }
            menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46)));
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.width = px(widthDp);
        lp.leftMargin = x;
        lp.topMargin = y;
        menu.setLayoutParams(lp);
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setTranslationY(-px(8));
        menu.animate().alpha(1f).translationY(0).setDuration(160).start();
        moveFocus(MENU, 0);
    }

    private void closeMenu() {
        menu.animate().alpha(0f).translationY(-px(8)).setDuration(120)
                .withEndAction(() -> menu.setVisibility(View.GONE)).start();
        int sec = menuReturnSection, idx = menuReturnIdx;
        menuReturnSection = TOP;
        if (sec == GRID || sec == UP) {
            // Stay on the grid / running-apps page: switch section without the page slide.
            section = MENU;
            menuReturnSection = sec;
        }
        moveFocus(sec, idx);
        menuReturnSection = TOP;
    }

    // ---------------------------------------------------------------- settings

    private void openSettings() {
        settingsScreen.open();
        moveFocus(SETTINGS, 0);
    }

    /** A setting changed (Settings page): apply it to what's on screen. */
    private final LauncherSettings.Listener settingsChanged = key -> {
        switch (key) {
            case LauncherSettings.ZONE:
                prayer.setZone(LauncherSettings.zone(this));
                break;
            case LauncherSettings.CLOCK:
                updateClock(); // also re-renders the prayer times
                break;
            case LauncherSettings.SHOW_PRAYER:
            case LauncherSettings.SHOW_USAGE:
            case LauncherSettings.SHOW_SYSTEM:
            case LauncherSettings.SHOW_NOW:
                applyCardSettings();
                break;
            case LauncherSettings.WALL_DIM:
                applyWallpaperDim();
                break;
            case LauncherSettings.WALLPAPER:
                loadWallpaper();
                break;
            case LauncherSettings.USAGE_EXCLUDED:
            case LauncherSettings.WEEK_START:
                refreshUsage();
                break;
            default:
                break;
        }
    };

    /**
     * Shows or hides the home cards. Each column stacks what's left from the top: Most used moves
     * up when the prayer card is off, Now Playing moves up when the System card is off.
     */
    private void applyCardSettings() {
        View prayerCard = findViewById(R.id.prayerPanel), usageCard = findViewById(R.id.usagePanel);
        boolean showPrayer = LauncherSettings.flag(this, LauncherSettings.SHOW_PRAYER);
        prayerCard.setVisibility(showPrayer ? View.VISIBLE : View.GONE);
        usageCard.setVisibility(LauncherSettings.flag(this, LauncherSettings.SHOW_USAGE) ? View.VISIBLE : View.GONE);
        ViewGroup.MarginLayoutParams ulp = (ViewGroup.MarginLayoutParams) usageCard.getLayoutParams();
        ulp.topMargin = showPrayer ? px(10) : 0;
        usageCard.setLayoutParams(ulp);
        findViewById(R.id.sysPanel).setVisibility(showSystem() ? View.VISIBLE : View.GONE);
        setSystemVisible(!onGridPage(section) && !onUpPage(section) && section != SETTINGS);
        if (!LauncherSettings.flag(this, LauncherSettings.SHOW_NOW)) nowPlaying.disable();
        else if (live) nowPlaying.start();
        placeNowPlaying();
    }

    private boolean showSystem() {
        return LauncherSettings.flag(this, LauncherSettings.SHOW_SYSTEM);
    }

    /** The System card only samples while it's on screen and switched on. */
    private void setSystemVisible(boolean onScreen) {
        system.setVisible(onScreen && showSystem());
    }

    // ---------------------------------------------------------------- dock editing

    /**
     * The dock as shown. Edits save this, so entries for apps that aren't installed or are
     * disabled (skipped on screen) are dropped instead of silently taking a slot.
     */
    private List<String> dockPkgs() {
        List<String> d = new ArrayList<>();
        for (AppEntry a : dock) d.add(a.pkg);
        return d;
    }

    private void startMove() {
        if (dockIdx >= dock.size()) return;
        editMode = EDIT_MOVE;
        startWiggle(dockTiles.get(dockIdx));
        updateHero(true);
    }

    /** Swaps the moving app with its neighbour (images swap, focus follows). */
    private void shiftDockApp(int dir) {
        int to = dockIdx + dir;
        if (to < 0 || to >= dock.size()) return;
        AppEntry a = dock.get(dockIdx), b = dock.get(to);
        dock.set(dockIdx, b);
        dock.set(to, a);
        dockTiles.get(dockIdx).setImageBitmap(b.tile);
        dockTiles.get(to).setImageBitmap(a.tile);
        stopWiggle();
        moveFocus(DOCK, to);
        startWiggle(dockTiles.get(to));
    }

    private void finishMove() {
        editMode = EDIT_NONE;
        stopWiggle();
        List<String> order = dockPkgs();
        if (dockIdx < dock.size()) pendingDockFocus = dock.get(dockIdx).pkg;
        DockConfig.save(this, order); // reloads through dockChanged
        updateHero(true);
    }

    private void startReplace(AppEntry e) {
        editMode = EDIT_REPLACE;
        replaceWith = e;
        moveFocus(DOCK, Math.min(dockIdx, dock.size() - 1));
    }

    private void finishReplace() {
        editMode = EDIT_NONE;
        AppEntry with = replaceWith;
        replaceWith = null;
        if (with != null && dockIdx < dock.size()) {
            pendingDockFocus = with.pkg;
            List<String> d = dockPkgs();
            d.set(dockIdx, with.pkg);
            DockConfig.save(this, d);
        }
        updateHero(true);
    }

    private void startWiggle(View v) {
        stopWiggle();
        wiggle = ValueAnimator.ofFloat(-1.6f, 1.6f);
        wiggle.setDuration(140);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.addUpdateListener(a -> v.setRotation((float) a.getAnimatedValue()));
        wiggle.start();
        wiggle.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                v.setRotation(0f);
            }
        });
    }

    private void stopWiggle() {
        if (wiggle != null) {
            wiggle.cancel();
            wiggle = null;
        }
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        clock.setText(DateFormat.format(LauncherSettings.use24h(this) ? "HH:mm" : "h:mm", c));
        date.setText(DateFormat.format("EEEE, d MMMM yyyy", c));
        prayer.refresh();
        // After midnight, pick up the new Bing photo of the day even if left on the home screen.
        java.time.LocalDate day = java.time.LocalDate.now();
        if (!day.equals(lastWallpaperDay)) {
            lastWallpaperDay = day;
            loadWallpaper();
        }
    }

    private int netShown = -1;
    // Network callbacks fire while Android still reports the old network (e.g. onLost before the
    // active network is cleared), so look again shortly after the last one.
    private final Runnable netRecheck = this::updateNetwork;

    /** Wi-Fi pill: network name, icon and colour for connected / no internet / disconnected. */
    /** Wi-Fi button: icon and colour show connected / no internet / disconnected. */
    private void updateNetwork() {
        NetState st = NetState.read(this);
        if (st.state != netShown) {
            netShown = st.state;
            ImageView wifi = (ImageView) pills[PILL_WIFI];
            wifi.setImageResource(st.icon());
            wifi.setImageTintList(android.content.res.ColorStateList.valueOf(st.color()));
        }
        pills[PILL_WIFI].setContentDescription(st.pillLabel());
        system.refreshNetwork();
        prayer.onNetworkAvailable();
        loadWallpaper(); // a built-in that couldn't download yet gets another try
    }

    /** Settings → Home screen → Wallpaper dim. */
    private void applyWallpaperDim() {
        int a = LauncherSettings.DIM_ALPHA[Math.max(0, Math.min(3, LauncherSettings.choice(this, LauncherSettings.WALL_DIM)))];
        wallpaper.setColorFilter(Color.argb(a, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
    }

    /**
     * The wallpaper chosen in Settings: a built-in photo (downloaded once, then cached), the
     * picture pushed over ADB, or none. A built-in that isn't cached yet is fetched in the
     * background and shown when it lands; until then home stays black.
     */
    private void loadWallpaper() {
        String choice = wallpaperPreview != null ? wallpaperPreview : LauncherSettings.wallpaper(this);
        File found = Wallpapers.file(this, choice);
        // Bing "photo of the day": refresh at most once a day (and no more than once a minute
        // when it keeps failing, e.g. offline); a new day's picture then swaps in via the stamp.
        if (Wallpapers.BING.equals(choice) && Bing.due(this) && !wallpaperFetching
                && System.currentTimeMillis() - bingAttemptAt > 60_000) {
            wallpaperFetching = true;
            bingAttemptAt = System.currentTimeMillis();
            new Thread(() -> {
                boolean ok = Bing.refresh(this);
                main.post(() -> {
                    wallpaperFetching = false;
                    if (ok) loadWallpaper();
                });
            }, "bing-fetch").start();
        }
        // Previewing one that's still downloading: keep what's showing until it lands.
        if (found == null && wallpaperPreview != null && !Wallpapers.NONE.equals(choice)) return;
        if (found == null && Wallpapers.photo(choice) != null && !wallpaperFetching) {
            wallpaperFetching = true;
            new Thread(() -> {
                boolean ok = Wallpapers.fetch(this, choice);
                main.post(() -> {
                    wallpaperFetching = false;
                    if (ok) loadWallpaper();
                });
            }, "wallpaper-fetch").start();
        }
        long stamp = found == null ? 0 : found.getPath().hashCode() * 31L ^ found.lastModified() ^ found.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        // Over a wallpaper the peek shadow is light; on plain black it fades to near-black.
        findViewById(R.id.peekFade).setBackgroundResource(found != null ? R.drawable.peek_fade_soft : R.drawable.peek_fade);
        if (found == null) {
            wallpaperGen++; // drop a decode still in flight
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            return;
        }
        File file = found;
        int gen = ++wallpaperGen; // quick scrolling through previews: only the last one is shown
        worker.execute(() -> {
            if (gen != wallpaperGen) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW && o.outHeight / (o.inSampleSize * 2) >= screenH) {
                o.inSampleSize *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
            main.post(() -> {
                if (bmp == null || gen != wallpaperGen) return;
                wallpaper.setImageBitmap(bmp);
                // Dim it so text and tiles stay readable.
                applyWallpaperDim();
                wallpaper.setVisibility(View.VISIBLE);
            });
        });
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(dropTiles);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                main.post(MainActivity.this::updateNetwork);
                main.removeCallbacks(netRecheck);
                main.postDelayed(netRecheck, 1500);
            }

            @Override
            public void onLost(@NonNull Network network) {
                main.post(MainActivity.this::updateNetwork);
                main.removeCallbacks(netRecheck);
                main.postDelayed(netRecheck, 1500);
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                main.post(MainActivity.this::updateNetwork);
            }

            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, @NonNull android.net.LinkProperties lp) {
                main.post(MainActivity.this::updateNetwork);
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hiddenSinceResume = false;
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        ContextCompat.registerReceiver(this, timeReceiver, tf, ContextCompat.RECEIVER_EXPORTED);
        updateClock();
        updateNetwork();
        loadWallpaper();
        updateLive();
        if (appsLoaded && DockConfig.stamp(this) != dockStamp) {
            tilesDropped = false;
            loadApps(); // dock.txt changed (ADB push, another writer): never edit a stale dock
        } else if (tilesDropped) {
            tilesDropped = false;
            loadApps(); // re-renders the tiles dropped by onTrimMemory
        }
        // Back from an app (e.g. after "Open app"): the dock, not the card's buttons.
        if (section == NOW && !dock.isEmpty()) moveFocus(DOCK, dockIdx);
        refreshUsage();
        RemoteBridge.attach(remoteKeys);
    }

    /**
     * Another app has been in front for a while: drop the grid's tile images (the 5 dock tiles
     * stay, so home looks the same on return). They're re-rendered off the main thread when home
     * comes back. Run from onStop + delay: Android doesn't send this process TRIM_MEMORY_UI_HIDDEN
     * because RemoteService keeps it in the foreground-service state.
     */
    private final Runnable dropTiles = this::dropGridTiles;

    private void dropGridTiles() {
        if (resumed || !appsLoaded || tilesDropped) return;
        // Detach the images from every grid view first (on screen and in the recycle pool), then
        // free them now rather than whenever the GC gets round to it.
        for (int i = 0; i < grid.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(grid.getChildAt(i));
            if (vh instanceof Holder) ((Holder) vh).tile.setImageDrawable(null);
        }
        grid.getRecycledViewPool().clear();
        java.util.Set<Bitmap> free = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (AppEntry e : apps) {
            if (dock.contains(e) || e.tile == null) continue;
            free.add(e.tile);
            e.tile = null;
            AppEntry r = rendered.remove(e.cacheKey);
            if (r != null) r.tile = null;
        }
        for (Bitmap b : free) b.recycle();
        tilesDropped = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateLive();
    }

    private void updateLive() {
        boolean want = resumed && hasWindowFocus();
        if (want == live) return;
        live = want;
        if (want) {
            system.onResume();
            if (LauncherSettings.flag(this, LauncherSettings.SHOW_NOW)) nowPlaying.start();
        } else {
            system.onPause();
            nowPlaying.stop();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close the menu and go back to the dock.
        if (!hiddenSinceResume) {
            if (section == MENU) closeMenu();
            if (section != DOCK && !dock.isEmpty()) moveFocus(DOCK, dockIdx);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        RemoteBridge.detach(remoteKeys);
        unregisterReceiver(timeReceiver);
        updateLive();
        saveFocus();
        super.onPause();
    }

    @Override
    protected void onStop() {
        hiddenSinceResume = true;
        main.postDelayed(dropTiles, 30_000);
        if (netCallback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            netCallback = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Nothing queued may run against a destroyed Activity: dropTiles, saveFocus, reloadApps
        // and netRecheck all touch views. onStop normally unregisters the network callback; be
        // defensive for the paths where destroy arrives without it.
        main.removeCallbacksAndMessages(null);
        if (netCallback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            netCallback = null;
        }
        DockConfig.removeListener(dockChanged);
        LauncherSettings.removeListener(settingsChanged);
        Alerts.get().removeListener(alertsChanged);
        unregisterReceiver(packageReceiver);
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- grid adapter

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView tile;
        final TextView name;

        Holder(View v) {
            super(v);
            tile = v.findViewById(R.id.tile);
            name = v.findViewById(R.id.name);
            tile.setClipToOutline(true);
        }
    }

    private final class GridAdapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Holder h = new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false));
            h.name.setTypeface(Fonts.outfit(MainActivity.this, 500));
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
            if (payloads.contains(PAYLOAD_TILE)) {
                h.tile.setImageBitmap(apps.get(position).tile);
            } else {
                onBindViewHolder(h, position);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AppEntry e = apps.get(position);
            h.tile.setImageBitmap(e.tile);
            h.name.setText(e.label);
            boolean on = section == GRID && position == gridIdx;
            h.tile.animate().cancel();
            h.tile.setScaleX(on ? TILE_SCALE : 1f);
            h.tile.setScaleY(on ? TILE_SCALE : 1f);
            h.name.animate().cancel();
            h.name.setAlpha(on ? 1f : 0f);
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
