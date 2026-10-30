package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phones paired to the remote: one entry per pairing, kept in SharedPreferences "remote".
 *
 * Only the SHA-256 of each token is written down, so a copy of the file can't be replayed as a
 * token, and tokens are compared in constant time. Each entry also carries the phone's name and a
 * last-seen time, so a single phone can be forgotten without unpairing the others.
 */
final class RemoteDevices {
    private static final String KEY = "devices";
    /** Plaintext token set from before this class existed; migrated on first read. */
    private static final String LEGACY_KEY = "tokens";
    /** last-seen is only written when it's at least this stale, to keep prefs writes rare. */
    private static final long SEEN_STEP_MS = 60_000;

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    RemoteDevices(Context ctx) {
        prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    static final class Device {
        final String id;
        final String hash; // hex SHA-256 of the token, never the token itself
        final String name;
        final long created;
        final long lastSeen;

        Device(String id, String hash, String name, long created, long lastSeen) {
            this.id = id;
            this.hash = hash;
            this.name = name;
            this.created = created;
            this.lastSeen = lastSeen;
        }
    }

    /** Records a phone for {@code token} and returns the id the phone keeps to identify itself. */
    synchronized String add(String token, String name) {
        long now = System.currentTimeMillis();
        JSONArray arr = read();
        String id = newId();
        try {
            arr.put(new JSONObject().put("id", id).put("hash", hex(sha256(token))).put("name", name)
                    .put("created", now).put("lastSeen", now));
        } catch (JSONException ignored) {
        }
        write(arr);
        return id;
    }

    /** The phone that owns {@code token}, or null. Refreshes its last-seen time at most once a minute. */
    synchronized Device find(String token) {
        if (token == null || token.isEmpty()) return null;
        String want = hex(sha256(token));
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            Device d = parse(o);
            if (d == null || !same(d.hash, want)) continue;
            long now = System.currentTimeMillis();
            if (now - d.lastSeen > SEEN_STEP_MS) {
                try {
                    o.put("lastSeen", now);
                    write(arr);
                } catch (JSONException ignored) {
                }
                return new Device(d.id, d.hash, d.name, d.created, now);
            }
            return d;
        }
        return null;
    }

    /** Forgets one phone by id. True if it was paired. */
    synchronized boolean remove(String id) {
        if (id == null || id.isEmpty()) return false;
        JSONArray arr = read();
        JSONArray kept = new JSONArray();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            Device d = parse(o);
            if (d != null && d.id.equals(id)) {
                found = true;
                continue;
            }
            kept.put(o);
        }
        if (found) write(kept);
        return found;
    }

    /** Forgets one phone by its token (the phone unpairing itself). */
    synchronized boolean removeByToken(String token) {
        Device d = find(token);
        return d != null && remove(d.id);
    }

    synchronized int count() {
        return read().length();
    }

    /** All phones, newest first, with {@code current} marking the one making the request. */
    synchronized JSONArray json(String currentToken) throws JSONException {
        JSONArray arr = read();
        String want = currentToken == null || currentToken.isEmpty() ? null : hex(sha256(currentToken));
        JSONArray out = new JSONArray();
        for (int i = arr.length() - 1; i >= 0; i--) {
            Device d = parse(arr.optJSONObject(i));
            if (d == null) continue;
            out.put(new JSONObject().put("id", d.id).put("name", d.name).put("lastSeen", d.lastSeen)
                    .put("current", want != null && same(d.hash, want)));
        }
        return out;
    }

    synchronized void removeAll() {
        prefs.edit().remove(KEY).remove(LEGACY_KEY).apply();
    }

    // ---------------------------------------------------------------- storage

    private JSONArray read() {
        String raw = prefs.getString(KEY, null);
        if (raw == null && prefs.contains(LEGACY_KEY)) raw = migrate();
        return parseAll(raw);
    }

    private void write(JSONArray arr) {
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    /** One-time upgrade of the old plaintext token set, so already-paired phones keep working. */
    private String migrate() {
        long now = System.currentTimeMillis();
        JSONArray arr = new JSONArray();
        Set<String> old = prefs.getStringSet(LEGACY_KEY, Collections.emptySet());
        for (String t : old) {
            if (t == null || t.isEmpty()) continue;
            try {
                arr.put(new JSONObject().put("id", newId()).put("hash", hex(sha256(t)))
                        .put("name", "Phone").put("created", now).put("lastSeen", now));
            } catch (JSONException ignored) {
            }
        }
        String raw = arr.toString();
        prefs.edit().putString(KEY, raw).remove(LEGACY_KEY).apply();
        return raw;
    }

    private static JSONArray parseAll(String raw) {
        if (raw == null) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static Device parse(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        String hash = o.optString("hash", "");
        if (id.isEmpty() || hash.length() != 64) return null;
        return new Device(id, hash, o.optString("name", "Phone"), o.optLong("created"), o.optLong("lastSeen"));
    }

    private String newId() {
        byte[] b = new byte[8];
        random.nextBytes(b);
        return hex(b);
    }

    /** Constant-time comparison of two hex digests (equal length, so no length leak). */
    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // every Android ships SHA-256
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /**
     * A short, human name for the list: {@code given}, the name typed on the phone (pairing, or
     * Rename), else one from the User-Agent. Chrome no longer puts the phone's model there: it
     * sends "(Linux; Android 10; K)" for every phone, so "K" alone says nothing and reads "Android
     * phone". (The real model is only told to HTTPS pages; the remote is plain HTTP.)
     */
    static String name(String userAgent, String given) {
        String m = given == null ? "" : given.trim();
        if (!m.isEmpty() && !m.equals("K") && m.length() <= 40 && m.chars().noneMatch(Character::isISOControl)) {
            return m;
        }
        return name(userAgent);
    }

    static String name(String userAgent) {
        if (userAgent == null || userAgent.trim().isEmpty()) return "Phone";
        Matcher m = Pattern.compile("\\(([^)]*)\\)").matcher(userAgent);
        String detail = m.find() ? m.group(1) : userAgent;
        if (detail.contains("iPhone")) return "iPhone";
        if (detail.contains("iPad")) return "iPad";
        String[] bits = detail.split(";");
        for (int i = bits.length - 1; i >= 0; i--) {
            String b = bits[i].trim().replaceAll("\\s*Build/.*$", "");
            if (b.isEmpty() || b.equals("K") || b.startsWith("wv") || b.startsWith("Android") || b.startsWith("Linux")) continue;
            return b.length() > 40 ? b.substring(0, 40) : b;
        }
        return detail.contains("Android") ? "Android phone" : "Phone";
    }

    /** Gives the phone that owns {@code token} a new name (Rename on the phone). */
    synchronized void rename(String token, String name) {
        if (token == null || token.isEmpty() || name == null) return;
        String want = hex(sha256(token));
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            Device d = parse(o);
            if (d == null || !same(d.hash, want) || name.equals(d.name)) continue;
            try {
                o.put("name", name);
                write(arr);
            } catch (JSONException ignored) {
            }
            return;
        }
    }
}
