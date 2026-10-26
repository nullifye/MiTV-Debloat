package my.rafhan.app.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Plays the azan (res/raw/azan.mp3) at each of the five prayer times and slides in a banner over
 * any app, from RemoteService so it works whatever is on screen. Times come from the prayer
 * card's month cache for the zone in Settings. Only while the screen is on (a TV that looks off
 * stays silent), and only within 2 minutes of the time, so a late check never plays an old azan.
 * Other apps are asked to pause (transient audio focus) and get it back when the azan ends.
 * Settings → Prayer times → Azan switches it off. A silent reminder banner ("Maghrib in 5 min")
 * comes a set number of minutes before each prayer (Settings → Reminder before prayer).
 */
final class Azan {
    private static final long CHECK_MS = 15_000, LATE_MS = 2 * 60_000;

    private final Context ctx;
    private final NetBanner banner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private LocalDate timesDay;
    private String timesZone;
    private LocalTime[] times;
    private String lastPlayed = "";   // "2026-10-23#3": each prayer plays once a day
    private String lastReminded = ""; // same, for the reminder banner
    private MediaPlayer player;
    private AudioFocusRequest focus;

    Azan(Context ctx, NetBanner banner) {
        this.ctx = ctx;
        this.banner = banner;
        audio = ctx.getSystemService(AudioManager.class);
    }

    void start() {
        main.post(check);
        LauncherSettings.addListener(onSetting);
    }

    /** Settings → Prayer times → Play azan now: the next prayer's banner and sound, for testing. */
    private final LauncherSettings.Listener onSetting = this::onSettingChanged;

    private void onSettingChanged(String key) {
        if (!LauncherSettings.AZAN_TEST.equals(key) || player != null) return;
        LocalTime[] t = PrayerPanel.cachedTimes(ctx, LauncherSettings.zone(ctx), LocalDate.now());
        int next = 0;
        if (t != null) for (int i = 0; i < t.length; i++) {
            if (t[i] != null && t[i].isAfter(LocalTime.now())) {
                next = i;
                break;
            }
        }
        play(PrayerPanel.NAMES[next], t != null && t[next] != null ? t[next] : LocalTime.now());
    }

    void stop() {
        LauncherSettings.removeListener(onSetting);
        main.removeCallbacks(check);
        release();
    }

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            main.postDelayed(this, CHECK_MS);
            boolean azanOn = LauncherSettings.flag(ctx, LauncherSettings.AZAN);
            int remind = LauncherSettings.choice(ctx, LauncherSettings.PRAYER_REMINDER);
            if ((!azanOn && remind <= 0) || player != null) return;
            LocalDateTime now = LocalDateTime.now();
            if (now.getYear() < 2025) return; // clock not set yet after a cold boot
            LocalDate today = now.toLocalDate();
            String zone = LauncherSettings.zone(ctx);
            if (!today.equals(timesDay) || !zone.equals(timesZone)) {
                times = PrayerPanel.cachedTimes(ctx, zone, today);
                timesDay = today;
                timesZone = zone;
            }
            if (times == null) return;
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            boolean screenOn = pm == null || pm.isInteractive();
            for (int i = 0; i < times.length; i++) {
                if (times[i] == null) continue;
                LocalDateTime at = today.atTime(times[i]);
                String key = today + "#" + i;
                LocalDateTime soon = at.minusMinutes(remind);
                if (remind > 0 && !now.isBefore(soon) && now.isBefore(at) && !key.equals(lastReminded)) {
                    lastReminded = key; // once per prayer, even if the screen was off
                    if (screenOn) remind(PrayerPanel.NAMES[i], times[i], now, at);
                }
                if (!azanOn) continue;
                if (!now.isBefore(at) && now.isBefore(at.plusNanos(LATE_MS * 1_000_000)) && !key.equals(lastPlayed)) {
                    lastPlayed = key;
                    if (!screenOn) return; // screen off: stay silent
                    play(PrayerPanel.NAMES[i], times[i]);
                    return;
                }
            }
        }
    };

    /** "Maghrib in 5 min · 19:01": rounded up, so 4 min 30 s left reads "5 min". */
    private void remind(String prayer, LocalTime time, LocalDateTime now, LocalDateTime at) {
        long secs = java.time.Duration.between(now, at).getSeconds();
        long mins = Math.max(1, (secs + 59) / 60);
        banner.showMessage(PrayerPanel.icon(prayer), 0xFFFFC857,
                prayer + " in " + mins + " min · " + format(time), 8_000);
    }

    private String format(LocalTime t) {
        return t.format(DateTimeFormatter.ofPattern(LauncherSettings.use24h(ctx) ? "HH:mm" : "h:mm a"));
    }

    private void play(String prayer, LocalTime at) {
        String time = format(at);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        player = MediaPlayer.create(ctx, R.raw.azan, attrs, audio.generateAudioSessionId());
        if (player == null) return;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build();
        audio.requestAudioFocus(focus); // other players pause, and resume when it's abandoned
        player.setOnCompletionListener(mp -> release());
        player.setOnErrorListener((mp, what, extra) -> {
            release();
            return true;
        });
        player.start();
        banner.showMessage(PrayerPanel.icon(prayer), 0xFFFFC857, "Azan " + prayer + " · " + time, 10_000);
    }

    private void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (focus != null) {
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }
}
