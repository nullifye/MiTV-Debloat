package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The Globe screen saver's picture: the Earth as it really sits in space right now (turned by
 * sidereal time, its axis tipped 23.44° against its orbit, ecliptic north up), seen from a
 * point in the orbit's plane that circles it once every two minutes, westward, so the surface
 * passes west to east. Lit by the sun where it really stands, city lights on the night side, the lines where it's Maghrib and
 * Fajr right now, today's clouds, snow, sea ice and aurora, the wind blowing, Makkah and your zone with the
 * qibla arc between them, and live dots (map
 * edits, earthquakes; GlobeFeeds) and satellites (the ISS, Tiangong and Hubble; Orbit).
 *
 * Drawn with OpenGL ES 2.0, which every Android TV has: a sphere mesh whose vertices carry
 * their spot on the two maps and the sun's height there (worked out at high precision, so it
 * looks the same on GPUs with only medium precision per pixel), then the atmosphere on a square
 * around it, then the surface dots and satellites at their orbital height as GL points.
 * About 20 frames a second. Text (clock, legend) is the Overlay view on top.
 *
 * Against burn-in, like the other screen savers: nothing white, the globe slides to a new
 * offset every minute and the text jumps to another corner.
 */
@SuppressLint("ViewConstructor") // only built in code (GlobeDream), with its feeds
final class GlobeView extends GLSurfaceView {
    static final long SPIN_MS = 120_000;
    /** The globe's radius, as a share of the screen's short side. */
    static final float RADIUS = 0.45f;

    /** Which layers are on (Settings → Screen saver, with Globe chosen); read again every few seconds. */
    static final class Layers {
        boolean lines, makkah, zone, lights, clouds, snow, aurora, wind, glint, rim, edits, quakes, iss, tiangong, hubble,
                legend;

        void read(Context ctx) {
            lines = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_PRAYER_LINES);
            makkah = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_MAKKAH);
            zone = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_ZONE);
            lights = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_CITY_LIGHTS);
            clouds = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_CLOUDS);
            snow = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_SNOW);
            aurora = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_AURORA);
            edits = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_EDITS);
            quakes = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_QUAKES);
            iss = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_ISS);
            tiangong = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_TIANGONG);
            hubble = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_HUBBLE);
            wind = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_WIND);
            glint = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_GLINT);
            rim = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_RIM);
            legend = LauncherSettings.flag(ctx, LauncherSettings.GLOBE_LEGEND);
        }
    }
    private static final long MOVE_EVERY_MS = 60_000, SLIDE_MS = 2_000;

    // Dot colours (r, g, b): dim, like the other screen savers.
    private static final float[] EDIT = {0.42f, 0.72f, 0.40f}, QUAKE = {0.82f, 0.47f, 0.20f},
            ISS_C = {0.40f, 0.68f, 0.82f}, TIANGONG_C = {0.84f, 0.44f, 0.44f}, HUBBLE_C = {0.74f, 0.76f, 0.80f},
            GOLD = {0.80f, 0.64f, 0.28f}, HOME = {0.62f, 0.66f, 0.70f};

    private final GlobeFeeds feeds;
    private final Context ctx;
    private final float density;
    private final Random random = new Random();
    private final double homeLat, homeLon;
    private final List<double[]> qibla;
    private static final long IDLE_MS = 10_000; // after the last arrow, it turns on its own again
    private static final double STEP = 10, REPEAT_STEP = 4, MAX_TILT = 70; // degrees: a press, a repeat, the furthest tilt
    private final double startFrom;
    // Turning the arrows asked for and not yet done (degrees), and when one was last pressed (uptime).
    private double toTurn, toTilt;
    private volatile long lastInput = -IDLE_MS;
    /** Run (on the main thread) if the globe can't be drawn on this TV; gpu says which graphics chip. */
    volatile Runnable failed;
    volatile String gpu = "";
    // Globe drift (px): eased from (fromX, fromY) to (toX, toY) starting at slideAt.
    private volatile float fromX, fromY, toX, toY;
    private volatile long slideAt;
    private boolean pacing;
    private int frame;

    GlobeView(Context ctx, GlobeFeeds feeds) {
        super(ctx);
        this.ctx = ctx;
        this.feeds = feeds;
        density = ctx.getResources().getDisplayMetrics().density;
        double[] home = Globe.zonePlace(LauncherSettings.zone(ctx));
        homeLat = home[0];
        homeLon = home[1];
        double degrees = Math.toDegrees(Math.acos(dot3(Globe.vector(homeLat, homeLon),
                Globe.vector(Globe.MAKKAH_LAT, Globe.MAKKAH_LON))));
        // A dot every 1.2° (about 130 km) along the way to Makkah.
        qibla = Globe.greatCircle(homeLat, homeLon, Globe.MAKKAH_LAT, Globe.MAKKAH_LON, (int) Math.max(2, degrees / 1.2));
        startFrom = facing(homeLat, homeLon, System.currentTimeMillis());
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(new Renderer());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** Starts drawing (about 30 fps) and the minutely drift. */
    void start() {
        onResume();
        if (!pacing) {
            pacing = true;
            Choreographer.getInstance().postFrameCallback(tick);
        }
        removeCallbacks(move);
        postDelayed(move, MOVE_EVERY_MS);
    }

    void stop() {
        pacing = false;
        Choreographer.getInstance().removeFrameCallback(tick);
        removeCallbacks(move);
        onPause();
    }

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!pacing) return;
            if (frame++ % 3 == 0) requestRender(); // ~20 fps on a 60 Hz TV; the slow orbital motion stays smooth
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Runnable move = new Runnable() {
        @Override
        public void run() {
            float[] now = drift(SystemClock.uptimeMillis());
            fromX = now[0];
            fromY = now[1];
            toX = dp(24) * (random.nextFloat() * 2 - 1);
            toY = dp(24) * (random.nextFloat() * 2 - 1);
            slideAt = SystemClock.uptimeMillis();
            postDelayed(this, MOVE_EVERY_MS);
        }
    };

    private float[] drift(long now) {
        float f = Math.min(1f, Math.max(0f, (now - slideAt) / (float) SLIDE_MS));
        f = 1 - (1 - f) * (1 - f); // decelerate
        return new float[]{fromX + (toX - fromX) * f, fromY + (toY - fromY) * f};
    }

    private float dp(float v) {
        return v * density;
    }

    private static double dot3(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Earth-fixed → view rotation at this moment, seen from ecliptic longitude {@code from}
     * (degrees) in the orbit's plane, ecliptic north up: column-major for GL.
     */
    static float[] earthToView(long epochMs, double from, double tilt) {
        float[] m = new float[9];
        double f = Math.toRadians(from), t = Math.toRadians(tilt);
        for (int j = 0; j < 3; j++) {
            double[] c = Globe.toEcliptic(new double[]{j == 0 ? 1 : 0, j == 1 ? 1 : 0, j == 2 ? 1 : 0}, epochMs);
            // The viewer's right is ecliptic longitude from + 90°, up is the ecliptic pole…
            double x = c[0] * Math.cos(f) - c[2] * Math.sin(f), y = c[1], z = c[0] * Math.sin(f) + c[2] * Math.cos(f);
            // …then looked at from tilt degrees above the orbit's plane (the arrows; 0 on its own).
            m[j * 3] = (float) x;
            m[j * 3 + 1] = (float) (y * Math.cos(t) - z * Math.sin(t));
            m[j * 3 + 2] = (float) (z * Math.cos(t) + y * Math.sin(t));
        }
        return m;
    }

    /**
     * The remote's arrows turn the globe: left/right round it, up/down to see more of the north
     * or south. A press moves it a step; held, the remote's repeats keep it going. The movement
     * is eased on the drawing thread. Ten seconds after the last arrow it turns on its own
     * again. False for any other key.
     */
    boolean key(android.view.KeyEvent e) {
        int dx = 0, dy = 0;
        switch (e.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_UP: dy = 1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: dy = -1; break;
            default: return false;
        }
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            boolean first = e.getRepeatCount() == 0;
            synchronized (this) {
                toTurn += dx * (first ? STEP : REPEAT_STEP);
                toTilt += dy * (first ? STEP * 0.8 : REPEAT_STEP * 0.8);
            }
        }
        lastInput = SystemClock.uptimeMillis();
        return true;
    }

    /** Takes a share of the turning still to do (degrees across, degrees of tilt), easing it out. */
    private synchronized double[] takeTurn(float dt) {
        double k = Math.min(1, dt * 8);
        double across = toTurn * k, up = toTilt * k;
        toTurn -= across;
        toTilt -= up;
        return new double[]{across, up};
    }

    /** The ecliptic longitude (degrees) a place faces right now: where to look from to see it. */
    private static double facing(double lat, double lon, long epochMs) {
        double[] c = Globe.toEcliptic(Globe.vector(lat, lon), epochMs);
        return Math.toDegrees(Math.atan2(c[0], c[2]));
    }

    private static float[] transpose(float[] m) {
        return new float[]{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    // The globe is a sphere mesh (GlobeView.mesh). Everything that needs precision is worked out
    // per vertex, where every GPU has highp: the spot on the maps, the sun's height, which side
    // it's rising on. GPUs whose pixel shaders only have mediump (Mali-400/450: Mi Box 4) would
    // band and blur if those were worked out per pixel. A uniform may not be used by both shaders
    // (its precision would have to match, and can't on those GPUs), hence u_px and u_radius.
    private static final String SPHERE_VS =
            "attribute vec3 a_pos;\n" // the spot, Earth-fixed (Globe.vector)
                    + "attribute vec2 a_uv;\n"  // and where it is on the maps
                    + "uniform mat3 u_toView;\n"
                    + "uniform vec2 u_center, u_screen;\n"
                    + "uniform float u_radius;\n"
                    + "uniform vec3 u_sun;\n"
                    + "varying vec2 v_uv;\n"
                    + "varying vec3 v_n;\n"      // facing (view space)
                    + "varying float v_s, v_rise;\n" // sine of the sun's height; > 0 where it's rising
                    + "void main() {\n"
                    + "  vec3 n = u_toView * a_pos;\n"
                    + "  v_uv = a_uv;\n"
                    + "  v_n = n;\n"
                    + "  v_s = dot(a_pos, u_sun);\n"
                    + "  v_rise = u_sun.x * a_pos.z - u_sun.z * a_pos.x;\n" // Globe.sunRising
                    + "  gl_Position = vec4((u_center + n.xy * u_radius) / u_screen * 2.0 - 1.0, -n.z * 0.5, 1.0);\n"
                    + "}\n";

    // Each pixel of the sphere: the day and night maps blended by how high the sun stands there,
    // with snow and ice, clouds, aurora, the sun's glint, a hint of atmosphere towards the edge,
    // and the prayer lines where that height equals Maghrib's (setting side) or Fajr's (rising).
    private static final String SPHERE_FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                    + "uniform sampler2D u_day, u_night, u_clouds, u_snow, u_aurora;\n"
                    + "uniform vec3 u_sunView;\n"
                    + "uniform float u_px, u_maghrib, u_fajr;\n"
                    + "uniform float u_linesOn, u_lightsOn, u_cloudsOn, u_snowOn, u_auroraOn, u_glintOn, u_rimOn;\n"
                    + "varying vec2 v_uv;\n"
                    + "varying vec3 v_n;\n"
                    + "varying float v_s, v_rise;\n"
                    + "const vec3 MAGHRIB = vec3(0.80, 0.58, 0.25), FAJR = vec3(0.30, 0.66, 0.62);\n"
                    + "float line(float s, float at, float px) {\n"
                    + "  float d = abs(s - at);\n"
                    + "  return (1.0 - smoothstep(0.9 * px, 2.2 * px, d)) * 0.85 + exp(-d / (5.0 * px)) * 0.18;\n"
                    + "}\n"
                    + "void main() {\n"
                    // v_uv straight into the lookups, no maths on it: full precision even on Mali-400.
                    + "  vec3 day = texture2D(u_day, v_uv).rgb, night = texture2D(u_night, v_uv).rgb;\n"
                    + "  float s = v_s;\n"
                    + "  float lit = smoothstep(-0.08, 0.10, s);\n"
                    // Snow and sea ice whiten the land and sea; clouds go over both, and at night
                    // they hide the city lights and show only faintly.
                    // The cloud map reads ~0.3 for clear land, ~0.45 for clear sea, 0.8–0.95 for cloud.
                    + "  float snow = texture2D(u_snow, v_uv).a * u_snowOn;\n"
                    + "  float cloud = pow(smoothstep(0.45, 0.95, texture2D(u_clouds, v_uv).r), 1.5) * u_cloudsOn;\n"
                    + "  vec3 ground = mix(day, vec3(0.84, 0.87, 0.92), snow * 0.75);\n"
                    + "  ground = mix(ground, vec3(0.90, 0.91, 0.93), cloud * 0.8);\n"
                    + "  vec3 dayC = ground * (0.40 + 0.60 * smoothstep(-0.05, 0.55, s));\n"
                    // The night side: the city lights; or with those off, the day map, dimmed and a
                    // little cool, so the land still shows and the night still reads as night.
                    + "  vec3 lights = night * vec3(1.05, 0.95, 0.80) * (1.0 - cloud * 0.7) + vec3(0.003, 0.006, 0.016)\n"
                    + "      + vec3(0.020, 0.025, 0.035) * cloud;\n"
                    + "  vec3 nightC = mix(ground * vec3(0.30, 0.33, 0.40), lights, u_lightsOn);\n"
                    + "  vec3 c = mix(nightC, dayC, lit);\n"
                    // The aurora glows green where it's dark.
                    + "  c += vec3(0.18, 0.80, 0.45) * smoothstep(0.03, 0.45, texture2D(u_aurora, v_uv).a) * (1.0 - lit) * 0.8 * u_auroraOn;\n"
                    + "  c += vec3(0.30, 0.12, 0.03) * exp(-(s * s) / 0.0025) * 0.22;\n"
                    // Sun glint: the sun mirrored in the sea, where the surface faces halfway between the
                    // sun and the viewer. Water is told from the day map by its colour (blue over red and
                    // green, and dark); none under cloud or ice. At most ~65 % bright: nothing white.
                    // 1 − cos(angle to the halfway direction) as |n − h|² / 2: stays accurate at mediump.
                    + "  float water = smoothstep(0.03, 0.10, day.b - max(day.r, day.g)) * (1.0 - smoothstep(0.30, 0.45, dot(day, vec3(0.3, 0.5, 0.2))));\n"
                    + "  vec3 dh = v_n - normalize(u_sunView + vec3(0.0, 0.0, 1.0));\n"
                    + "  float k = dot(dh, dh) * 0.5;\n"
                    + "  float glint = (exp(-60.0 * k) * 0.38 + exp(-600.0 * k) * 0.32) * water * (1.0 - cloud) * (1.0 - snow) * smoothstep(0.0, 0.15, s);\n"
                    + "  c += vec3(1.0, 0.90, 0.72) * glint * u_glintOn;\n"
                    + "  float toSun = clamp(dot(normalize(v_n.xy + 1e-4), normalize(u_sunView.xy + 1e-4)) * 0.5 + 0.5, 0.0, 1.0);\n"
                    + "  c = mix(c, vec3(0.30, 0.45, 0.75) * (0.25 + 0.75 * lit), pow(1.0 - clamp(v_n.z, 0.0, 1.0), 3.0) * (0.35 + 0.25 * pow(toSun, 3.0) * u_rimOn));\n"
                    // Fajr only where the sun is coming up, Maghrib where it's going down (Globe.sunRising).
                    + "  float morning = smoothstep(-0.03, 0.03, v_rise);\n"
                    + "  c = mix(c, MAGHRIB, clamp(line(s, u_maghrib, u_px), 0.0, 1.0) * (1.0 - morning) * u_linesOn);\n"
                    + "  c = mix(c, FAJR, clamp(line(s, u_fajr, u_px), 0.0, 1.0) * morning * u_linesOn);\n"
                    + "  gl_FragColor = vec4(c * 0.85, 1.0);\n" // nothing at full white
                    + "}\n";

    // The atmosphere around the sphere, drawn over it on a square: a faint glow outside, brighter
    // towards the sun (much more so with the sunlit rim on), and a soft sphere edge (premultiplied:
    // nothing inside, the glow outside, a blend across the edge's pixel and a half).
    private static final String GLOW_VS =
            "attribute vec2 a_corner;\n"
                    + "uniform vec2 u_center, u_screen;\n"
                    + "uniform float u_half;\n"
                    + "void main() {\n"
                    + "  vec2 px = u_center + a_corner * u_half;\n"
                    + "  gl_Position = vec4(px / u_screen * 2.0 - 1.0, 0.0, 1.0);\n"
                    + "}\n";

    private static final String GLOW_FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                    + "uniform vec3 u_sunView;\n"
                    + "uniform vec2 u_mid;\n"
                    + "uniform float u_radius, u_rimOn;\n"
                    + "void main() {\n"
                    + "  vec2 p = (gl_FragCoord.xy - u_mid) / u_radius;\n"
                    + "  float r = length(p), px = 1.0 / u_radius;\n"
                    + "  float toSun = clamp(dot(p / max(r, 1e-4), normalize(u_sunView.xy + 1e-4)) * 0.5 + 0.5, 0.0, 1.0);\n"
                    + "  float fade = 1.0 - smoothstep(1.0, 1.09, r);\n"
                    + "  vec3 glow = vec3(0.16, 0.30, 0.58) * fade * 0.55 * (0.35 + 0.65 * toSun);\n"
                    + "  float sunlit = pow(toSun, 3.0) * u_rimOn;\n"
                    + "  glow += (vec3(0.22, 0.32, 0.50) + vec3(0.20, 0.10, 0.00) * pow(toSun, 6.0)) * fade * fade * sunlit * 0.7;\n"
                    + "  float a = 1.0 - clamp((1.0 - r) / (1.5 * px), 0.0, 1.0);\n"
                    + "  gl_FragColor = vec4(glow * a, a);\n"
                    + "}\n";

    /** The sphere mesh: 144 × 72 steps of 2.5°, so its outline is round to a tenth of a pixel. */
    private static final int MESH_LON = 144, MESH_LAT = 72;

    // Dots: a_pos = (x, y, z, kind). Surface markers are on the unit sphere; orbital points
    // carry their actual position in Earth radii, and are occluded by the sphere per pixel.
    private static final String DOT_VS =
            "attribute vec4 a_pos;\n"
                    + "attribute vec4 a_color;\n"
                    + "attribute float a_size;\n"
                    + "uniform mat3 u_toView;\n"
                    + "uniform vec2 u_center, u_screen;\n"
                    + "uniform float u_radius;\n"
                    + "varying vec4 v_color;\n"
                    + "varying float v_kind;\n"
                    + "varying float v_orbit, v_depth;\n"
                    + "void main() {\n"
                    + "  vec3 v = u_toView * a_pos.xyz;\n"
                    + "  bool orbital = a_pos.w > 2.5 && a_pos.w < 3.5 || a_pos.w > 4.5;\n"
                    + "  vec2 px = u_center + v.xy * u_radius;\n"
                    + "  gl_Position = vec4(px / u_screen * 2.0 - 1.0, 0.0, 1.0);\n"
                    + "  gl_PointSize = a_size;\n"
                    + "  v_color = vec4(a_color.rgb, a_color.a * (orbital ? 1.0 : smoothstep(0.0, 0.12, v.z)));\n"
                    + "  v_kind = a_pos.w;\n"
                    + "  v_orbit = orbital ? 1.0 : 0.0;\n"
                    + "  v_depth = v.z;\n"
                    + "}\n";

    private static final String DOT_FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                    + "uniform sampler2D u_icons;\n"
                    + "uniform vec2 u_mid;\n"
                    + "uniform float u_rimRadius;\n"
                    + "varying vec4 v_color;\n"
                    + "varying float v_kind;\n"
                    + "varying float v_orbit, v_depth;\n"
                    + "void main() {\n"
                    // A satellite in front of the sphere is visible over the continents; a far-side
                    // one is hidden only where the sphere actually covers this pixel of its icon.
                    + "  if (v_orbit > 0.5) {\n"
                    + "    vec2 p = (gl_FragCoord.xy - u_mid) / u_rimRadius;\n"
                    + "    float r2 = dot(p, p);\n"
                    + "    if (r2 < 1.0 && v_depth < sqrt(1.0 - r2)) discard;\n"
                    + "  }\n"
                    + "  if (v_kind > 1.5 && v_kind < 6.5) {\n" // the icons are side by side in one texture
                    + "    gl_FragColor = texture2D(u_icons, vec2((v_kind - 2.0 + gl_PointCoord.x) * 0.125, gl_PointCoord.y)) * v_color.a;\n"
                    + "    return;\n"
                    + "  }\n"
                    + "  float d = length(gl_PointCoord - 0.5) * 2.0;\n"
                    + "  float a = v_kind > 0.5 ? smoothstep(0.62, 0.78, d) * (1.0 - smoothstep(0.86, 1.0, d))\n"
                    + "                         : 1.0 - smoothstep(0.55, 1.0, d);\n"
                    + "  a *= v_color.a;\n"
                    + "  gl_FragColor = vec4(v_color.rgb * a, a);\n" // premultiplied, like the icons
                    + "}\n";

    static final float DOT = 0, RING = 1, KAABA = 2, SATELLITE = 3, PERSON = 4, SATELLITE_CN = 5, HUBBLE = 6,
            ORBIT_TRAIL = 7;

    // Wind streaks: line segments between points on the sphere, a_pos = (x, y, z, alpha).
    private static final String WIND_VS =
            "attribute vec4 a_pos;\n"
                    + "uniform mat3 u_toView;\n"
                    + "uniform vec2 u_center, u_screen;\n"
                    + "uniform float u_radius;\n"
                    + "varying float v_a;\n"
                    + "void main() {\n"
                    + "  vec3 v = u_toView * a_pos.xyz;\n"
                    + "  gl_Position = vec4((u_center + v.xy * u_radius) / u_screen * 2.0 - 1.0, 0.0, 1.0);\n"
                    + "  v_a = a_pos.w * smoothstep(0.0, 0.15, v.z);\n"
                    + "}\n";

    private static final String WIND_FS =
            "precision mediump float;\n"
                    + "varying float v_a;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = vec4(vec3(0.80, 0.86, 0.92) * v_a, v_a);\n" // premultiplied
                    + "}\n";

    // The wind animation: particles carried by the wind, each with a short fading trail.
    private static final int WIND_N = 900, TRAIL = 8;
    private static final long TRAIL_EVERY_NS = 100_000_000L; // trail point about every 0.1 s, independent of frame rate
    /** Degrees a second per m/s of wind: 10 m/s moves 4° a second (about 35 px on a 1080p TV). */
    private static final float WIND_PACE = 0.4f;

    private final class Renderer implements GLSurfaceView.Renderer {
        private int globe, glow, meshVbo, meshIbo, dots, windProg, dayTex, nightTex, iconTex, cloudTex, snowTex, auroraTex, width, height, frames;
        // Particles: where (radians), how old and how long they live (s), their trail (unit vectors, newest first).
        private final float[] pLat = new float[WIND_N], pLon = new float[WIND_N], pAge = new float[WIND_N],
                pLife = new float[WIND_N], pTrail = new float[WIND_N * TRAIL * 3];
        private final int[] pCount = new int[WIND_N];
        private final float[] windVerts = new float[WIND_N * TRAIL * 2 * 4];
        private FloatBuffer windBuf;
        private boolean windStarted;
        private long windFrameNs;
        private long windTrailNs;
        // What the weather textures were made from: the files' times, the aurora forecast's count.
        private long cloudsMade = -1, snowMade = -1;
        private int auroraMade = -1;
        private double from = startFrom, tilt;
        private long lastUp;
        private final Layers on = new Layers();
        private final FloatBuffer corners = floats(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        private FloatBuffer points = floats(new float[9 * 512]);
        private int count;
        private static final int TRAIL_DOTS = 30; // every 20 s over 10 minutes
        @SuppressWarnings("unchecked")
        private final List<double[]>[] trails = new List[]{new ArrayList<double[]>(), new ArrayList<double[]>(),
                new ArrayList<double[]>()};
        private final long[] trailSecond = {-1, -1, -1};

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            globe = program(SPHERE_VS, SPHERE_FS);
            glow = program(GLOW_VS, GLOW_FS);
            dots = program(DOT_VS, DOT_FS);
            mesh();
            if (globe == 0 || glow == 0) { // this GPU can't draw it: let the screen saver show something else
                String chip = GLES20.glGetString(GLES20.GL_RENDERER);
                gpu = chip == null ? "" : chip;
                android.util.Log.w("TvHome", "globe: can't draw on " + gpu);
                Runnable f = failed;
                if (f != null) post(f);
            }
            windProg = program(WIND_VS, WIND_FS);
            windBuf = ByteBuffer.allocateDirect(windVerts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] range = new float[2];
            GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0);
            GLES20.glLineWidth(Math.max(1, Math.min(range[1], density)));
            dayTex = texture(GlobeFeeds.dayMap(ctx));
            nightTex = texture(GlobeFeeds.nightMap(ctx));
            if (dayTex == 0 || nightTex == 0) GlobeFeeds.dropMaps(ctx); // downloaded again next time
            Bitmap icons = icons(64);
            iconTex = upload(icons);
            icons.recycle();
            // Empty until their data is in (no clouds, snow or aurora).
            cloudTex = bytes(0, GLES20.GL_LUMINANCE, 1, 1, new byte[1]);
            snowTex = bytes(0, GLES20.GL_ALPHA, 1, 1, new byte[1]);
            auroraTex = bytes(0, GLES20.GL_ALPHA, 1, 1, new byte[1]);
            cloudsMade = snowMade = -1;
            auroraMade = -1;
            GLES20.glClearColor(0, 0, 0, 1);
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            width = w;
            height = h;
            GLES20.glViewport(0, 0, w, h);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (globe == 0 || glow == 0 || dayTex == 0 || nightTex == 0) return;
            if (frames++ % 300 == 0) weather(); // every 15 s or so at 20 fps: new clouds, snow, aurora?
            long up = SystemClock.uptimeMillis(), now = System.currentTimeMillis();
            float[] d = drift(up);
            float radius = RADIUS * Math.min(width, height);
            float cx = width / 2f + d[0], cy = height / 2f - d[1];
            // The surface moves west to east (left to right), as seen from space.
            // Starts facing your zone; on its own the viewpoint circles westward (the land passes west
            // to east) at a level tilt. The arrows take over for a while.
            float dt = lastUp == 0 ? 0 : Math.min(0.1f, (up - lastUp) / 1000f);
            lastUp = up;
            double[] turn = takeTurn(dt);
            from += turn[0];
            tilt = Math.max(-MAX_TILT, Math.min(MAX_TILT, tilt + turn[1]));
            if (up - lastInput > IDLE_MS) {
                from -= 360.0 * dt * 1000 / SPIN_MS;
                tilt -= tilt * Math.min(1, dt * 1.5); // eases back to level
            }
            float[] toView = earthToView(now, from, tilt), toEarth = transpose(toView);
            double[] sub = Globe.subsolar(now), sun = Globe.vector(sub[0], sub[1]);
            float[] sunView = mul(toView, sun);

            // The sphere.
            GLES20.glUseProgram(globe);
            GLES20.glUniform2f(loc(globe, "u_center"), cx, cy);
            GLES20.glUniform2f(loc(globe, "u_screen"), width, height);
            GLES20.glUniform1f(loc(globe, "u_radius"), radius);
            GLES20.glUniform1f(loc(globe, "u_px"), 1f / radius);
            GLES20.glUniformMatrix3fv(loc(globe, "u_toView"), 1, false, toView, 0);
            GLES20.glUniform3f(loc(globe, "u_sun"), (float) sun[0], (float) sun[1], (float) sun[2]);
            GLES20.glUniform3f(loc(globe, "u_sunView"), sunView[0], sunView[1], sunView[2]);
            GLES20.glUniform1f(loc(globe, "u_maghrib"), (float) Math.sin(Math.toRadians(Globe.MAGHRIB_ALT)));
            GLES20.glUniform1f(loc(globe, "u_fajr"), (float) Math.sin(Math.toRadians(Globe.FAJR_ALT)));
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dayTex);
            GLES20.glUniform1i(loc(globe, "u_day"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nightTex);
            GLES20.glUniform1i(loc(globe, "u_night"), 1);
            GLES20.glUniform1f(loc(globe, "u_linesOn"), on.lines ? 1 : 0);
            GLES20.glUniform1f(loc(globe, "u_lightsOn"), on.lights ? 1 : 0);
            GLES20.glUniform1f(loc(globe, "u_cloudsOn"), on.clouds ? 1 : 0);
            GLES20.glUniform1f(loc(globe, "u_snowOn"), on.snow ? 1 : 0);
            GLES20.glUniform1f(loc(globe, "u_auroraOn"), on.aurora ? 1 : 0);
            GLES20.glUniform1f(loc(globe, "u_glintOn"), on.glint ? 1 : 0);
            GLES20.glUniform1f(loc(globe, "u_rimOn"), on.rim ? 1 : 0);
            bind(GLES20.GL_TEXTURE3, cloudTex, "u_clouds", 3);
            bind(GLES20.GL_TEXTURE4, snowTex, "u_snow", 4);
            bind(GLES20.GL_TEXTURE5, auroraTex, "u_aurora", 5);
            int aPos = GLES20.glGetAttribLocation(globe, "a_pos"), aUv = GLES20.glGetAttribLocation(globe, "a_uv");
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVbo);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, 0);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, 12);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshIbo);
            GLES20.glEnable(GLES20.GL_CULL_FACE); // the far side faces away: convex, so nothing else hides
            GLES20.glCullFace(GLES20.GL_BACK);
            GLES20.glFrontFace(GLES20.GL_CCW);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, MESH_LON * MESH_LAT * 6, GLES20.GL_UNSIGNED_SHORT, 0);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glDisableVertexAttribArray(aPos);
            GLES20.glDisableVertexAttribArray(aUv);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); // the other passes use arrays from memory
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

            // The atmosphere and the sphere's soft edge.
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(glow);
            GLES20.glUniform2f(loc(glow, "u_center"), cx, cy);
            GLES20.glUniform2f(loc(glow, "u_mid"), cx, cy);
            GLES20.glUniform2f(loc(glow, "u_screen"), width, height);
            GLES20.glUniform1f(loc(glow, "u_half"), radius * 1.1f);
            GLES20.glUniform1f(loc(glow, "u_radius"), radius);
            GLES20.glUniform3f(loc(glow, "u_sunView"), sunView[0], sunView[1], sunView[2]);
            GLES20.glUniform1f(loc(glow, "u_rimOn"), on.rim ? 1 : 0);
            int corner = GLES20.glGetAttribLocation(glow, "a_corner");
            corners.position(0);
            GLES20.glVertexAttribPointer(corner, 2, GLES20.GL_FLOAT, false, 0, corners);
            GLES20.glEnableVertexAttribArray(corner);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(corner);
            float[] u = feeds.windU, v = feeds.windV;
            if (on.wind && u != null && v != null && windProg != 0) wind(u, v, toView, toEarth, cx, cy, radius);
            else windStarted = false;

            buildDots(now, up);
            if (count == 0) {
                GLES20.glDisable(GLES20.GL_BLEND);
                return;
            }
            GLES20.glUseProgram(dots);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iconTex);
            GLES20.glUniform1i(loc(dots, "u_icons"), 2);
            GLES20.glUniformMatrix3fv(loc(dots, "u_toView"), 1, false, toView, 0);
            GLES20.glUniform2f(loc(dots, "u_center"), cx, cy);
            GLES20.glUniform2f(loc(dots, "u_mid"), cx, cy);
            GLES20.glUniform2f(loc(dots, "u_screen"), width, height);
            GLES20.glUniform1f(loc(dots, "u_radius"), radius);
            GLES20.glUniform1f(loc(dots, "u_rimRadius"), radius);
            int pos = GLES20.glGetAttribLocation(dots, "a_pos"), col = GLES20.glGetAttribLocation(dots, "a_color"),
                    size = GLES20.glGetAttribLocation(dots, "a_size");
            points.position(0);
            GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 36, points);
            points.position(4);
            GLES20.glVertexAttribPointer(col, 4, GLES20.GL_FLOAT, false, 36, points);
            points.position(8);
            GLES20.glVertexAttribPointer(size, 1, GLES20.GL_FLOAT, false, 36, points);
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glEnableVertexAttribArray(col);
            GLES20.glEnableVertexAttribArray(size);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);
            GLES20.glDisableVertexAttribArray(pos);
            GLES20.glDisableVertexAttribArray(col);
            GLES20.glDisableVertexAttribArray(size);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        /** This frame's dots: the qibla arc, home, quakes, edits, satellites, Makkah on top. */
        private void buildDots(long now, long up) {
            count = 0;
            points.clear();
            if (on.makkah) for (double[] p : qibla) dot(p, DOT, GOLD, 0.35f, dp(2.5f));
            if (on.zone) dot(Globe.vector(homeLat, homeLon), PERSON, HOME, 1f, dp(10));

            for (Globe.Spot q : on.quakes ? feeds.quakes : java.util.Collections.<Globe.Spot>emptyList()) {
                long age = now - q.time;
                float size = dp(Math.max(7, Math.min(30, 7 + (q.mag - 2.5f) * 5)));
                if (age < 3_600_000) size *= 1 + 0.18f * (float) Math.sin(up / 400.0); // the last hour pulses
                float a = 0.95f - 0.55f * Math.max(0, Math.min(1, age / 86_400_000f));
                double[] v = Globe.vector(q.lat, q.lon);
                dot(v, RING, QUAKE, a, size);
                dot(v, DOT, QUAKE, a, dp(3));
            }

            for (GlobeFeeds.Edit e : on.edits ? feeds.edits : java.util.Collections.<GlobeFeeds.Edit>emptyList()) {
                long age = now - e.showAt;
                if (age < 0 || age > GlobeFeeds.EDIT_FADE_MS) continue;
                double[] v = Globe.vector(e.spot.lat, e.spot.lon);
                float f = age / (float) GlobeFeeds.EDIT_FADE_MS;
                dot(v, DOT, EDIT, 0.9f * (1 - f) * (1 - f) + 0.08f, dp(4.5f));
                if (age < 1_500) { // a ping as it appears
                    float g = age / 1_500f;
                    dot(v, RING, EDIT, 0.9f * (1 - g), dp(5 + 22 * g));
                }
            }

            if (on.hubble) station(feeds.hubble, 2, now, HUBBLE_C, HUBBLE);
            if (on.tiangong) station(feeds.tiangong, 1, now, TIANGONG_C, SATELLITE_CN);
            if (on.iss) station(feeds.iss, 0, now, ISS_C, SATELLITE);
            // Makkah last, on top of anything nearby.
            if (on.makkah) dot(Globe.vector(Globe.MAKKAH_LAT, Globe.MAKKAH_LON), KAABA, GOLD, 1f, dp(15));
            points.position(0);
        }

        /**
         * Moves the wind particles on and draws their trails. A particle lives 2–5 s, then starts
         * again somewhere on the side facing the viewer (so none are wasted round the back).
         */
        private void wind(float[] u, float[] v, float[] toView, float[] toEarth, float cx, float cy, float radius) {
            long ns = System.nanoTime();
            float dt = windStarted ? Math.min(0.1f, (ns - windFrameNs) / 1e9f) : 0;
            windFrameNs = ns;
            if (!windStarted) {
                windStarted = true;
                for (int i = 0; i < WIND_N; i++) {
                    spawn(i, toEarth);
                    pAge[i] = random.nextFloat() * pLife[i]; // not all at once
                }
            }
            boolean mark = ns - windTrailNs >= TRAIL_EVERY_NS;
            if (mark) windTrailNs = ns;
            float pace = (float) Math.toRadians(WIND_PACE) * dt;
            int n = 0;
            for (int i = 0; i < WIND_N; i++) {
                pAge[i] += dt;
                if (pAge[i] > pLife[i] || Math.abs(pLat[i]) > 1.48f) spawn(i, toEarth); // past 85°
                // Where it is: four sines and cosines a particle a frame, all the trig there is.
                float lat = pLat[i], lon = pLon[i];
                float cl = (float) Math.cos(lat), sl = (float) Math.sin(lat), co = (float) Math.cos(lon),
                        so = (float) Math.sin(lon);
                float hx = cl * so, hy = sl, hz = cl * co;
                // The wind here, between the four grid points around it (rows from 90° N, columns from 0° E).
                float fx = lon * 57.29578f, fy = 90 - lat * 57.29578f;
                fx = ((fx % 360) + 360) % 360;
                int x0 = (int) fx, y0 = Math.min(179, Math.max(0, (int) fy)), x1 = x0 == 359 ? 0 : x0 + 1;
                float tx = fx - x0, ty = fy - y0;
                int a = y0 * 360 + x0, b = y0 * 360 + x1, c = a + 360, d = b + 360;
                float wu = (u[a] * (1 - tx) + u[b] * tx) * (1 - ty) + (u[c] * (1 - tx) + u[d] * tx) * ty;
                float wv = (v[a] * (1 - tx) + v[b] * tx) * (1 - ty) + (v[c] * (1 - tx) + v[d] * tx) * ty;
                pLat[i] = lat + wv * pace;
                pLon[i] = lon + wu * pace / Math.max(0.2f, cl);
                int t0 = i * TRAIL * 3;
                if (mark) { // a trail point: shift the older ones along
                    System.arraycopy(pTrail, t0, pTrail, t0 + 3, (TRAIL - 1) * 3);
                    pTrail[t0] = hx;
                    pTrail[t0 + 1] = hy;
                    pTrail[t0 + 2] = hz;
                    if (pCount[i] < TRAIL) pCount[i]++;
                }
                // Brighter for stronger wind, fading in and out over its life.
                float speed = (float) Math.sqrt(wu * wu + wv * wv);
                float life = pAge[i] / pLife[i];
                float alpha = Math.min(1, Math.max(0.3f, speed / 10f)) * Math.min(1, Math.min(life * 4, (1 - life) * 4)) * 0.8f;
                // Head (where it is now) back through the trail, fading along it.
                float px = hx, py = hy, pz = hz;
                for (int k = 0; k < pCount[i]; k++) {
                    float qx = pTrail[t0 + k * 3], qy = pTrail[t0 + k * 3 + 1], qz = pTrail[t0 + k * 3 + 2];
                    float a0 = alpha * (1 - k / (float) TRAIL), a1 = alpha * (1 - (k + 1) / (float) TRAIL);
                    windVerts[n++] = px; windVerts[n++] = py; windVerts[n++] = pz; windVerts[n++] = a0;
                    windVerts[n++] = qx; windVerts[n++] = qy; windVerts[n++] = qz; windVerts[n++] = a1;
                    px = qx;
                    py = qy;
                    pz = qz;
                }
            }
            if (n == 0) return;
            windBuf.clear();
            windBuf.put(windVerts, 0, n).position(0);
            GLES20.glUseProgram(windProg);
            GLES20.glUniformMatrix3fv(loc(windProg, "u_toView"), 1, false, toView, 0);
            GLES20.glUniform2f(loc(windProg, "u_center"), cx, cy);
            GLES20.glUniform2f(loc(windProg, "u_screen"), width, height);
            GLES20.glUniform1f(loc(windProg, "u_radius"), radius);
            int pos = GLES20.glGetAttribLocation(windProg, "a_pos");
            GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 16, windBuf);
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, n / 4);
            GLES20.glDisableVertexAttribArray(pos);
        }

        /** Particle i starts afresh at a random spot on the side facing the viewer. */
        private void spawn(int i, float[] toEarth) {
            float z = random.nextFloat(), phi = random.nextFloat() * 6.2831853f, r = (float) Math.sqrt(1 - z * z);
            float x = r * (float) Math.cos(phi), y = r * (float) Math.sin(phi);
            // View → Earth (column-major).
            float ex = toEarth[0] * x + toEarth[3] * y + toEarth[6] * z, ey = toEarth[1] * x + toEarth[4] * y + toEarth[7] * z,
                    ez = toEarth[2] * x + toEarth[5] * y + toEarth[8] * z;
            pLat[i] = (float) Math.asin(Math.max(-1, Math.min(1, ey))); // radians
            pLon[i] = (float) Math.atan2(ex, ez);
            pAge[i] = 0;
            pLife[i] = 2 + random.nextFloat() * 3;
            pCount[i] = 0;
        }

        /** A satellite and its fading 10-minute trail in the same inertial frame as this view. */
        private void station(Orbit o, int which, long now, float[] rgb, float kind) {
            if (o == null) return;
            List<double[]> trail = trails[which];
            if (now / 1000 != trailSecond[which]) { // the path, worked out once a second
                trailSecond[which] = now / 1000;
                trail.clear();
                for (int i = TRAIL_DOTS; i >= 1; i--) {
                    double[] at = o.earthFixed(now - i * 20_000L, now);
                    if (at != null) trail.add(at);
                }
            }
            for (int i = 0; i < trail.size(); i++)
                dot(trail.get(i), ORBIT_TRAIL, rgb, 0.12f + 0.45f * i / trail.size(), dp(2.5f));
            double[] at = o.earthFixed(now, now);
            if (at != null) dot(at, kind, rgb, 0.95f, dp(19));
        }

        private void dot(double[] v, float kind, float[] rgb, float alpha, float size) {
            if (points.remaining() < 9) {
                FloatBuffer bigger = floats(new float[points.capacity() * 2]);
                points.flip();
                bigger.put(points);
                points = bigger;
            }
            points.put((float) v[0]).put((float) v[1]).put((float) v[2]).put(kind)
                    .put(rgb[0]).put(rgb[1]).put(rgb[2]).put(alpha).put(size);
            count++;
        }

        /**
         * The sphere, once into GPU buffers: (MESH_LON + 1) × (MESH_LAT + 1) points (the seam at
         * ±180° twice, with u 0 and 1, so the maps don't wrap across a triangle), each its
         * Earth-fixed position and its spot on the maps; two triangles a step, counter-clockwise
         * seen from outside.
         */
        private void mesh() {
            int cols = MESH_LON + 1;
            float[] v = new float[cols * (MESH_LAT + 1) * 5];
            int k = 0;
            for (int j = 0; j <= MESH_LAT; j++) {
                double lat = 90 - 180.0 * j / MESH_LAT;
                for (int i = 0; i <= MESH_LON; i++) {
                    double[] e = Globe.vector(lat, -180 + 360.0 * i / MESH_LON);
                    v[k++] = (float) e[0];
                    v[k++] = (float) e[1];
                    v[k++] = (float) e[2];
                    v[k++] = (float) i / MESH_LON;
                    v[k++] = (float) j / MESH_LAT;
                }
            }
            short[] idx = new short[MESH_LON * MESH_LAT * 6];
            k = 0;
            for (int j = 0; j < MESH_LAT; j++) {
                for (int i = 0; i < MESH_LON; i++) {
                    int a = j * cols + i, b = a + 1, c = a + cols, d = c + 1; // a b over c d
                    idx[k++] = (short) a;
                    idx[k++] = (short) c;
                    idx[k++] = (short) b;
                    idx[k++] = (short) b;
                    idx[k++] = (short) c;
                    idx[k++] = (short) d;
                }
            }
            int[] ids = new int[2];
            GLES20.glGenBuffers(2, ids, 0);
            meshVbo = ids[0];
            meshIbo = ids[1];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.length * 4, floats(v), GLES20.GL_STATIC_DRAW);
            java.nio.ShortBuffer ib = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            ib.put(idx).position(0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshIbo);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, ib, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        private void bind(int unit, int tex, String sampler, int index) {
            GLES20.glActiveTexture(unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(loc(globe, sampler), index);
        }

        /** Reloads whichever weather layer has changed since its texture was made. */
        private void weather() {
            on.read(ctx);
            File clouds = GlobeFeeds.clouds(ctx), snow = GlobeFeeds.snow(ctx), ice = GlobeFeeds.ice(ctx);
            long c = clouds.lastModified();
            if (c != cloudsMade) {
                cloudsMade = c;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = c == 0 ? null : BitmapFactory.decodeFile(clouds.getPath(), o);
                if (b != null) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cloudTex);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                    b.recycle();
                }
            }
            long s = snow.lastModified() * 31 + ice.lastModified();
            if (s != snowMade) {
                snowMade = s;
                byte[] mask = snowAndIce(snow, ice);
                if (mask != null) bytes(snowTex, GLES20.GL_ALPHA, SNOW_W, SNOW_H, mask);
            }
            int a = feeds.auroraVersion;
            byte[] grid = feeds.aurora;
            if (a != auroraMade && grid != null) {
                auroraMade = a;
                bytes(auroraTex, GLES20.GL_ALPHA, Globe.AURORA_W, Globe.AURORA_H, grid);
            }
        }

        private int loc(int program, String name) {
            return GLES20.glGetUniformLocation(program, name);
        }
    }

    private static float[] mul(float[] m, double[] v) {
        return new float[]{(float) (m[0] * v[0] + m[3] * v[1] + m[6] * v[2]),
                (float) (m[1] * v[0] + m[4] * v[1] + m[7] * v[2]),
                (float) (m[2] * v[0] + m[5] * v[1] + m[8] * v[2])};
    }

    private static FloatBuffer floats(float[] f) {
        FloatBuffer b = ByteBuffer.allocateDirect(f.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(f).position(0);
        return b;
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            android.util.Log.w("TvHome", "globe: link failed: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            android.util.Log.w("TvHome", "globe: shader failed: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /** A map as a texture (16-bit colour: half the memory, and plenty for a dim globe), or 0. */
    private static int texture(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return 0;
        int t = upload(b);
        b.recycle();
        return t;
    }

    private static final int SNOW_W = 1024, SNOW_H = 512; // as GlobeFeeds asks NASA for them

    /** The snow and sea-ice maps as one mask (Globe.snowLevel, iceLevel), or null when neither is in. */
    private static byte[] snowAndIce(File snow, File ice) {
        byte[] mask = new byte[SNOW_W * SNOW_H];
        int[] row = new int[SNOW_W];
        boolean any = false;
        for (File f : new File[]{snow, ice}) {
            Bitmap b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (b == null) continue;
            if (b.getWidth() == SNOW_W && b.getHeight() == SNOW_H) {
                any = true;
                for (int y = 0; y < SNOW_H; y++) {
                    b.getPixels(row, 0, SNOW_W, 0, y, SNOW_W, 1);
                    for (int x = 0; x < SNOW_W; x++) {
                        int v = f == snow ? Globe.snowLevel(row[x]) : Globe.iceLevel(row[x]);
                        int i = y * SNOW_W + x;
                        if (v > (mask[i] & 0xFF)) mask[i] = (byte) v;
                    }
                }
            }
            b.recycle();
        }
        return any ? mask : null;
    }

    /** One byte a texel (GL_ALPHA or GL_LUMINANCE) into texture t, or a new one when t is 0. */
    private static int bytes(int t, int format, int w, int h, byte[] data) {
        if (t == 0) {
            int[] id = new int[1];
            GLES20.glGenTextures(1, id, 0);
            t = id[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
        buf.put(data).position(0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, w, h, 0, format, GLES20.GL_UNSIGNED_BYTE, buf);
        return t;
    }

    private static int upload(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        // The maps (1920×960) aren't a power of two: ES 2.0 then needs clamping and no mipmaps.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }

    /**
     * The Kaaba, a satellite (the ISS's colours), a person in a circle, another satellite
     * (Tiangong's) and the Hubble telescope, side by side in one picture (8·cell × cell, the
     * last three cells empty): the globe's icons for Makkah, the satellites and your zone, and
     * the legend's. Drawn here rather than shipped, in
     * the globe's dim colours.
     */
    static Bitmap icons(int cell) {
        Bitmap b = Bitmap.createBitmap(cell * 8, cell, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(cell / 64f, cell / 64f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int gold = 0xFFC9A24E;
        // The Kaaba, seen from a corner above: top, front and side, the gold band and the door.
        fill(c, p, 0xFF3A3A3A, 32, 7, 57, 17, 32, 27, 7, 17);
        fill(c, p, 0xFF262626, 7, 17, 32, 27, 32, 58, 7, 48);
        fill(c, p, 0xFF1B1B1B, 32, 27, 57, 17, 57, 48, 32, 58);
        fill(c, p, gold, 7, 23, 32, 33, 32, 37, 7, 27);
        fill(c, p, gold, 32, 33, 57, 23, 57, 27, 32, 37);
        fill(c, p, gold, 22, 41, 28, 43.4f, 28, 52, 22, 49.6f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(gold);
        p.setAlpha(200);
        android.graphics.Path edge = new android.graphics.Path();
        edge.moveTo(7, 17);
        edge.lineTo(32, 7);
        edge.lineTo(57, 17);
        edge.lineTo(57, 48);
        edge.lineTo(32, 58);
        edge.lineTo(7, 48);
        edge.close();
        edge.moveTo(7, 17);
        edge.lineTo(32, 27);
        edge.lineTo(57, 17);
        edge.moveTo(32, 27);
        edge.lineTo(32, 58);
        c.drawPath(edge, p);
        satellite(c, p, 64, 0xFF7DB4CC, 0xFF2C5A78);  // the ISS
        satellite(c, p, 192, 0xFFDB8A8A, 0xFF7A2E2E); // Tiangong
        hubble(c, p, 256);
        // You: a person (head and shoulders) in a circle.
        c.translate(128, 0);
        int light = 0xFFB8C0C8;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF262B30);
        c.drawCircle(32, 32, 28, p);
        c.save();
        android.graphics.Path inside = new android.graphics.Path();
        inside.addCircle(32, 32, 26, android.graphics.Path.Direction.CW);
        c.clipPath(inside);
        p.setColor(light);
        c.drawCircle(32, 25, 9.5f, p);
        c.drawOval(14, 39, 50, 69, p);
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawCircle(32, 32, 28, p);
        return b;
    }

    /** A satellite at x (in the 64-unit cell grid): a body with two solar panels, tipped like it's flying. */
    private static void satellite(Canvas c, Paint p, float x0, int body, int panel) {
        c.save();
        c.translate(x0, 0);
        c.rotate(-30, 32, 32);
        p.setStyle(Paint.Style.FILL);
        p.setColor(body);
        c.drawRect(26, 25, 38, 39, p);
        c.drawRect(21, 31, 43, 33, p);
        for (float x : new float[]{3, 43}) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(panel);
            c.drawRect(x, 26, x + 18, 38, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f);
            p.setColor(body);
            c.drawRect(x, 26, x + 18, 38, p);
            c.drawLine(x + 6, 26, x + 6, 38, p);
            c.drawLine(x + 12, 26, x + 12, 38, p);
            c.drawLine(x, 32, x + 18, 32, p);
        }
        p.setStrokeWidth(1.8f);
        c.drawLine(32, 25, 32, 17, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(32, 15, 3, p);
        c.restore();
    }

    /** Hubble at x (in the 64-unit grid): a silver tube, its dark open end, and two solar wings. */
    private static void hubble(Canvas c, Paint p, float x0) {
        c.save();
        c.translate(x0, 0);
        c.rotate(-30, 32, 32);
        int silver = 0xFFBCC1C8, wing = 0xFF4E6A9C;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(silver);
        c.drawLine(30, 22, 30, 15, p);
        c.drawLine(30, 42, 30, 49, p);
        for (float y : new float[]{6, 49}) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(wing);
            c.drawRect(21, y, 39, y + 9, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f);
            p.setColor(silver);
            c.drawRect(21, y, 39, y + 9, p);
            c.drawLine(27, y, 27, y + 9, p);
            c.drawLine(33, y, 33, y + 9, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(silver);
        c.drawRoundRect(12, 22, 48, 42, 4, 4, p);
        p.setColor(0xFF8E949C);
        c.drawRect(12, 22, 20, 42, p);  // the equipment section, a shade darker
        p.setColor(0xFF2A2E33);
        c.drawRect(47, 23.5f, 53, 40.5f, p); // the open aperture door
        c.restore();
    }

    /** Fills the polygon through the points (x0, y0, x1, y1, …). */
    private static void fill(Canvas c, Paint p, int color, float... xy) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    /**
     * The text beside the globe, kept clear of it: a small clock in a top corner and, in the
     * bottom corner below it, the legend (one line per layer with its line, dot or icon); in the
     * other bottom corner the latest map edit, the latest earthquake and the credits, wrapped to
     * the room beside the globe. The sides swap every minute, fading.
     */
    @SuppressLint("ViewConstructor")
    static final class Overlay extends View {
        private static final int GREY = 0xFF737373, DIM = 0xFF5E5E5E, DARK = 0xFF454545;
        private static final String CREDITS = "Earth, snow & ice: NASA  ·  Clouds: contains modified EUMETSAT data"
                + "  ·  Aurora, wind: NOAA  ·  Map edits © OpenStreetMap contributors  ·  Earthquakes: USGS  ·  Orbits: CelesTrak";
        // The legend: what each line's swatch is, and its colour (icons have their own).
        private static final int LINE = 0, KAABA_ICON = 1, RING_MARK = 2, DOT_MARK = 3, SATELLITE_ICON = 4, PERSON_ICON = 5,
                SATELLITE_CN_ICON = 6, HUBBLE_ICON = 7;
        private static final int[] KINDS = {LINE, LINE, KAABA_ICON, PERSON_ICON, DOT_MARK, RING_MARK, LINE, LINE,
                SATELLITE_ICON, SATELLITE_CN_ICON, HUBBLE_ICON};
        private static final int[] COLORS = {0xFFCC9440, 0xFF4DA89E, 0, 0xFF9EA8B3, 0xFF6BB866, 0xFFD17833, 0xFF3DC77A,
                0xFFB8C4D0, 0, 0, 0};
        private final String[] parts = new String[KINDS.length];
        private final List<String> wrapped = new ArrayList<>();
        private final GlobeFeeds feeds;
        private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
                small = new Paint(Paint.ANTI_ALIAS_FLAG), swatch = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Bitmap icons;
        private final int cell;
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final Random random = new Random();
        private final float density;
        private final String zone;
        private boolean legendRight; // clock and legend on the right, the rest on the left; or mirrored
        private float alpha = 1f, jitterX, jitterY;
        private android.animation.ValueAnimator fade;
        private final Layers on = new Layers();
        private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String problem; // null, or the graphics chip that couldn't draw the globe
        private long hintFrom = SystemClock.uptimeMillis(); // shown for HINT_MS from the start
        private static final long HINT_MS = 8_000;

        Overlay(Context ctx, GlobeFeeds feeds) {
            super(ctx);
            this.feeds = feeds;
            density = ctx.getResources().getDisplayMetrics().density;
            float sd = ctx.getResources().getDisplayMetrics().scaledDensity;
            clock.setTypeface(Fonts.outfit(ctx, 500));
            clock.setTextSize(16 * sd);
            clock.setColor(GREY);
            clock.setFontFeatureSettings("tnum");
            text.setTypeface(Fonts.outfit(ctx, 400));
            text.setTextSize(12 * sd);
            small.setTypeface(Fonts.outfit(ctx, 400));
            small.setTextSize(8 * sd);
            cell = Math.round(text.getTextSize() * 1.5f);
            icons = icons(cell);
            zone = zoneName(ctx, LauncherSettings.zone(ctx));
            hint.setTypeface(Fonts.outfit(ctx, 400));
            hint.setTextSize(12 * sd);
            hint.setColor(DIM);
            legendRight = random.nextBoolean();
        }

        /** "Your zone: Klang" for SGR03: the first place in the prayer zone list's entry. */
        private static String zoneName(Context ctx, String code) {
            for (String e : ctx.getResources().getStringArray(R.array.prayer_zones)) {
                String[] f = e.split("\\|");
                if (f.length >= 3 && f[1].equals(code)) return "Your zone: " + f[2].split(",")[0].trim();
            }
            return "Your zone";
        }

        /** The globe can't be drawn on this TV: say so in the middle, the rest stays as it is. */
        void cantDraw(String gpu) {
            problem = gpu.isEmpty() ? "" : " (" + gpu + ")";
            invalidate();
        }

        /** An arrow was pressed: no need to show how any more. */
        void touched() {
            hintFrom = 0;
        }

        void start() {
            hintFrom = SystemClock.uptimeMillis();
            removeCallbacks(ticker);
            removeCallbacks(move);
            ticker.run();
            postDelayed(move, MOVE_EVERY_MS);
        }

        void stop() {
            removeCallbacks(ticker);
            removeCallbacks(move);
            if (fade != null) fade.cancel();
        }

        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                on.read(getContext());
                invalidate();
                postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
            }
        };

        private final Runnable move = new Runnable() {
            @Override
            public void run() {
                if (fade != null) fade.cancel();
                boolean side = random.nextBoolean();
                float jx = (random.nextFloat() * 2 - 1) * 16 * density, jy = (random.nextFloat() * 2 - 1) * 10 * density;
                fade = android.animation.ValueAnimator.ofFloat(1f, 0f, 1f).setDuration(SLIDE_MS);
                fade.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    boolean moved;

                    @Override
                    public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        alpha = (float) a.getAnimatedValue();
                        if (!moved && a.getAnimatedFraction() >= 0.5f) {
                            moved = true;
                            legendRight = side;
                            jitterX = jx;
                            jitterY = jy;
                        }
                        invalidate();
                    }
                });
                fade.start();
                postDelayed(this, MOVE_EVERY_MS);
            }
        };

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float pad = 40 * density;
            int a = Math.round(255 * alpha);
            // The room beside the globe (drifting 24 dp).
            float radius = RADIUS * Math.min(getWidth(), getHeight());
            float room = Math.max(160 * density, (getWidth() - 2 * radius) / 2 - pad - 44 * density);

            // The clock, top corner on the legend's side.
            Calendar now = Calendar.getInstance();
            boolean h24 = LauncherSettings.use24h(getContext());
            int h = now.get(Calendar.HOUR_OF_DAY);
            String time = h24 ? String.format(Locale.ROOT, "%02d:%02d:%02d", h, now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
                    : String.format(Locale.ROOT, "%d:%02d:%02d", h % 12 == 0 ? 12 : h % 12, now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND));
            String t = String.format(Locale.getDefault(), "%s  ·  %ta %<td %<tb", time, now);
            float place = clock.measureText(t.replaceAll("\\d", "8")); // fixed width: no shuffle each second
            clock.setAlpha(a);
            c.drawText(t, (legendRight ? getWidth() - pad - place : pad) + jitterX, pad + clock.getTextSize() + jitterY, clock);

            // The globe couldn't be drawn: say so where it would be.
            if (problem != null) {
                hint.setAlpha(a);
                String l1 = "This TV can't draw the globe" + problem, l2 = "Its graphics chip doesn't support what it needs";
                float cx = getWidth() / 2f + jitterX, cy = getHeight() / 2f + jitterY;
                c.drawText(l1, cx - hint.measureText(l1) / 2, cy, hint);
                c.drawText(l2, cx - hint.measureText(l2) / 2, cy + hint.getFontSpacing(), hint);
            }

            // How to use it, for the first few seconds (fading out over the last two), in the top
            // corner the clock isn't in: the globe nearly reaches the top in the middle.
            long shown = SystemClock.uptimeMillis() - hintFrom;
            if (shown < HINT_MS) {
                hint.setAlpha(Math.round(255 * Math.min(1, (HINT_MS - shown) / 2000f)));
                float y0 = pad + clock.getTextSize() + jitterY;
                String l1 = "◀ ▶ ▲ ▼  turn the globe", l2 = "Any other key to exit";
                float w1 = hint.measureText(l1), w2 = hint.measureText(l2);
                c.drawText(l1, (legendRight ? pad : getWidth() - pad - w1) + jitterX, y0, hint);
                c.drawText(l2, (legendRight ? pad : getWidth() - pad - w2) + jitterX, y0 + hint.getFontSpacing(), hint);
            }

            // The legend, bottom up; a feed's line only once it has answered.
            long ms = System.currentTimeMillis();
            List<Globe.Spot> quakes = feeds.quakes;
            int edits = feeds.editsPerMinute;
            parts[0] = on.lines ? "Maghrib now" : null;
            parts[1] = on.lines ? "Fajr now" : null;
            parts[2] = on.makkah ? "Makkah" : null;
            parts[3] = on.zone ? zone : null;
            parts[4] = on.edits && edits >= 0 ? edits + " map edits a minute" : null;
            parts[5] = on.quakes && !quakes.isEmpty() ? quakes.size() + " earthquakes today" : null;
            parts[6] = on.aurora && feeds.aurora != null ? "Aurora" : null;
            parts[7] = on.wind && feeds.windU != null ? "Wind" : null;
            parts[8] = on.iss && feeds.iss != null ? "ISS" : null;
            parts[9] = on.tiangong && feeds.tiangong != null ? "Tiangong (China)" : null;
            parts[10] = on.hubble && feeds.hubble != null ? "Hubble telescope" : null;
            if (!on.legend) java.util.Arrays.fill(parts, null);
            // One column: the swatches line up on either side.
            float widest = 0;
            for (String part : parts) if (part != null) widest = Math.max(widest, text.measureText(part));
            float gap = 10 * density, x = legendRight ? getWidth() - pad - cell - gap - widest : pad;
            float lh = text.getFontSpacing() * 1.3f, y = getHeight() - pad + jitterY;
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i] == null) continue;
                drawItem(c, i, x + jitterX, y, a);
                y -= lh;
            }

            // The other corner, bottom up: credits, the latest earthquake, the latest map edit.
            boolean right = !legendRight;
            gap = 8 * density;
            y = block(c, CREDITS, small, DARK, -1, 0, right, getHeight() - pad + jitterY, room, 7, a, pad) - gap;
            // Each after its legend mark: "1 h ago · M4.8, Fiji region", "1 min ago · “Added a cafe”".
            if (on.legend && on.quakes && !quakes.isEmpty()) {
                Globe.Spot q = quakes.get(0);
                y = block(c, String.format(Locale.ROOT, "%s · M%.1f%s", ago(ms - q.time), q.mag,
                        q.text.isEmpty() ? "" : ", " + q.text), text, GREY, RING_MARK, COLORS[5], right, y, room, 2, a, pad) - gap;
            }
            GlobeFeeds.Edit edit = on.legend && on.edits ? latestEdit(ms) : null;
            if (edit != null) {
                long at = edit.spot.time > 0 ? edit.spot.time : edit.showAt; // when it was saved
                block(c, ago(ms - at) + " · \u201c" + edit.spot.text.replaceAll("\\s+", " ") + "\u201d", text, GREY,
                        DOT_MARK, COLORS[4], right, y, room, 2, a, pad);
            }
        }

        /** One legend line from x: its swatch (a line, ring, dot or icon), then the words. */
        private void drawItem(Canvas c, int i, float x, float y, int a) {
            float gap = 10 * density, mid = y - text.getTextSize() * 0.35f;
            switch (KINDS[i]) {
                case LINE:
                case RING_MARK:
                case DOT_MARK:
                    mark(c, KINDS[i], COLORS[i], x, cell, mid, a);
                    break;
                default: // the icons, in the picture's order: Kaaba, ISS, person, Tiangong, Hubble
                    swatch.setAlpha(a);
                    int col = KINDS[i] == KAABA_ICON ? 0 : KINDS[i] == SATELLITE_ICON ? 1 : KINDS[i] == PERSON_ICON ? 2
                            : KINDS[i] == SATELLITE_CN_ICON ? 3 : 4;
                    src.set(col * cell, 0, (col + 1) * cell, cell);
                    float half = cell / 2f * (col == 2 ? 0.7f : 1f); // the person, smaller: its circle fills its cell
                    dst.set(x + cell / 2f - half, mid - half, x + cell / 2f + half, mid + half);
                    swatch.setAlpha(a);
                    c.drawBitmap(icons, src, dst, swatch);
            }
            text.setColor(DIM);
            text.setAlpha(a);
            c.drawText(parts[i], x + cell + gap, y, text);
        }

        /** A legend mark (a line, ring or dot) in a slot w wide from x, centred on mid. */
        private void mark(Canvas c, int kind, int color, float x, float w, float mid, int a) {
            swatch.setColor(color);
            swatch.setAlpha(Math.round(a * 0.9f));
            if (kind == LINE) {
                swatch.setStyle(Paint.Style.STROKE);
                swatch.setStrokeWidth(2.5f * density);
                c.drawLine(x + w / 2 - cell / 2f + 2 * density, mid, x + w / 2 + cell / 2f - 2 * density, mid, swatch);
            } else if (kind == RING_MARK) {
                swatch.setStyle(Paint.Style.STROKE);
                swatch.setStrokeWidth(1.6f * density);
                c.drawCircle(x + w / 2, mid, cell * 0.21f, swatch);
            } else {
                swatch.setStyle(Paint.Style.FILL);
                c.drawCircle(x + w / 2, mid, cell * 0.2f, swatch);
            }
        }

        /**
         * Text wrapped to {@code width} (at most maxLines, the last cut with "…"), drawn with its
         * last line on {@code bottom}, after a legend mark when {@code markKind} isn't −1 (on the
         * left the lines hang under the mark; on the right it sits before the first line).
         * Returns the top of the block, for the next one above.
         */
        private float block(Canvas c, String s, Paint p, int color, int markKind, int markColor, boolean right,
                            float bottom, float width, int maxLines, int a, float pad) {
            // Just the mark's width and a small gap: the legend's slots are wider for its icons.
            float slot = cell * 0.45f, markW = markKind < 0 ? 0 : slot + 6 * density;
            width -= markW;
            wrapped.clear();
            int start = 0;
            while (start < s.length() && wrapped.size() < maxLines) {
                if (wrapped.size() == maxLines - 1) {
                    wrapped.add(ellipsize(s.substring(start).trim(), p, width));
                    break;
                }
                int end = start + p.breakText(s, start, s.length(), true, width, null);
                if (end < s.length()) {
                    int space = s.lastIndexOf(' ', end);
                    if (space > start) end = space;
                }
                wrapped.add(s.substring(start, end).trim());
                start = end;
                while (start < s.length() && s.charAt(start) == ' ') start++;
            }
            p.setColor(color);
            p.setAlpha(a);
            float lh = p.getFontSpacing(), y = bottom;
            for (int i = wrapped.size() - 1; i >= 0; i--) {
                String line = wrapped.get(i);
                float x = (right ? getWidth() - pad - p.measureText(line) : pad + markW) + jitterX;
                c.drawText(line, x, y, p);
                if (i == 0 && markKind >= 0) mark(c, markKind, markColor, x - markW, slot, y - p.getTextSize() * 0.35f, a);
                y -= lh;
            }
            return y + lh - p.getTextSize();
        }

        private static String ellipsize(String s, Paint p, float width) {
            if (p.measureText(s) <= width) return s;
            int n = p.breakText(s, true, width - p.measureText("…"), null);
            return s.substring(0, n).trim() + "…";
        }

        /** The edit whose dot appeared last, if it has a comment; null before the first. */
        private GlobeFeeds.Edit latestEdit(long now) {
            GlobeFeeds.Edit last = null;
            for (GlobeFeeds.Edit e : feeds.edits) if (e.showAt <= now) last = e;
            return last == null || last.spot.text.isEmpty() ? null : last;
        }

        private static String ago(long ms) {
            long min = Math.max(0, ms / 60_000);
            if (min < 1) return "just now";
            if (min < 60) return min + " min ago";
            return min / 60 + " h ago";
        }
    }
}
