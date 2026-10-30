package my.rafhan.app.tvhome;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in wallpapers: seven photos from Wikimedia Commons' Featured pictures, downloaded once
 * (1920 px, 0.5–1 MB each) and cached in the app's files dir; nothing is bundled in the APK.
 * Credits are shown in Settings and the README (CC BY-SA needs them). Your own pictures go in
 * the Wallpapers folder on internal storage (the phone remote's Files tab has a shortcut to it);
 * each one is listed by file name. An older picture pushed over ADB as
 * files/wallpaper.{jpg,png,webp} is still offered as "Your picture".
 */
final class Wallpapers {
    static final String NONE = "none", CUSTOM = "custom", FILE = "file:", BING = "bing",
            DEFAULT = "perseids"; // Stargazer, the first in the list
    static final String UA = "TvHome/1.0 (Android TV home launcher)";
    private static final String COMMONS = "https://thumb.wikimedia.org/wikipedia/commons/thumb/";

    static final class Photo {
        final String id, name, credit, url;

        Photo(String id, String name, String credit, String path) {
            this.id = id;
            this.name = name;
            this.credit = credit;
            this.url = COMMONS + path;
        }
    }

    static final Photo[] BUILT_IN = {
            new Photo("perseids", "Stargazer", "Giles Laurent · CC BY-SA 4.0",
                    "2/23/018_Human_looking_at_the_stars_during_Perseids_with_the_Milky_Way_in_the_background_Photo_by_Giles_Laurent.jpg/"
                            + "1920px-018_Human_looking_at_the_stars_during_Perseids_with_the_Milky_Way_in_the_background_Photo_by_Giles_Laurent.jpg"),
            new Photo("seliger", "Lake Seliger", "Aleksandr Baydukov · CC BY-SA 4.0",
                    "d/d9/Lake_Seliger._Ostashkov._View_of_Voroniy_Island.jpg/"
                            + "1920px-Lake_Seliger._Ostashkov._View_of_Voroniy_Island.jpg"),
            new Photo("sunrise", "Misty sunrise", "Dietmar Rabich · CC BY-SA 4.0",
                    "7/76/D%C3%BClmen%2C_Umland_--_2014_--_7056.jpg/1920px-D%C3%BClmen%2C_Umland_--_2014_--_7056.jpg"),
            new Photo("m81", "Galaxy M81", "NASA, ESA, Hubble Heritage Team · public domain",
                    "6/63/Messier_81_HST.jpg/1920px-Messier_81_HST.jpg"),
            new Photo("domecreek", "Dome Creek", "Jakub Fryš · CC BY-SA 4.0",
                    "a/a3/Lake_in_Dome_Creek.jpg/1920px-Lake_in_Dome_Creek.jpg"),
            new Photo("forest", "Forest light", "Dietmar Rabich · CC BY-SA 4.0",
                    "3/3d/D%C3%BClmen%2C_Rorup%2C_NSG_Roruper_Holz_--_2021_--_8187-91.jpg/"
                            + "1920px-D%C3%BClmen%2C_Rorup%2C_NSG_Roruper_Holz_--_2021_--_8187-91.jpg"),
            new Photo("nightclouds", "Night clouds", "Martin Koitmäe · CC BY-SA 4.0",
                    "3/3f/Helkivad_%C3%B6%C3%B6pilved_Kuresoo_kohal.jpg/1920px-Helkivad_%C3%B6%C3%B6pilved_Kuresoo_kohal.jpg"),
    };

    private Wallpapers() {}

    static Photo photo(String id) {
        for (Photo p : BUILT_IN) if (p.id.equals(id)) return p;
        return null;
    }

    /** Where a built-in is cached (may not exist yet). */
    static File cached(Context ctx, String id) {
        return new File(new File(ctx.getFilesDir(), "wallpapers"), id + ".jpg");
    }

    static boolean isCached(Context ctx, String id) {
        return cached(ctx, id).isFile();
    }

    /** The picture pushed over ADB, or null. */
    static File custom(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        for (String n : new String[] {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, n);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Internal storage/Wallpapers: your own pictures. */
    static File folder() {
        return new File(Environment.getExternalStorageDirectory(), "Wallpapers");
    }

    /** Pictures in the Wallpapers folder, A–Z. Creates the folder so it shows up in Files. */
    static List<File> userPictures() {
        File dir = folder();
        if (!dir.isDirectory()) dir.mkdirs();
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".png") || n.endsWith(".webp"))) {
                out.add(f);
            }
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** The file for a wallpaper choice, or null (none, not downloaded yet, no pushed picture). */
    static File file(Context ctx, String choice) {
        if (CUSTOM.equals(choice)) return custom(ctx);
        if (BING.equals(choice)) return Bing.shown(ctx);
        if (choice.startsWith(FILE)) {
            String name = choice.substring(FILE.length());
            File f = new File(folder(), name);
            return !name.contains("/") && f.isFile() ? f : null;
        }
        if (photo(choice) == null) return null;
        File f = cached(ctx, choice);
        return f.isFile() ? f : null;
    }

    /** Downloads a built-in into the cache. Blocking: call off the main thread. */
    static synchronized boolean fetch(Context ctx, String id) {
        Photo p = photo(id);
        if (p == null) return false;
        File out = cached(ctx, id);
        if (out.isFile()) return true;
        File dir = out.getParentFile();
        // Photos dropped from the list in an update: free their space.
        File[] old = dir == null ? null : dir.listFiles();
        if (old != null) for (File f : old) {
            String n = f.getName();
            if (photo(n.endsWith(".jpg") ? n.substring(0, n.length() - 4) : "") == null) f.delete();
        }
        return download(p.url, out);
    }

    /**
     * Downloads a picture to {@code out} (through a .tmp file, so a cut-off download never looks
     * finished). Also the Globe screen saver's maps. Blocking: call off the main thread.
     */
    static boolean download(String url, File out) {
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        File tmp = new File(out.getPath() + ".tmp");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", UA);
            String type = c.getContentType();
            if (c.getResponseCode() != 200 || type == null || !type.startsWith("image/")) return false;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
            }
            return tmp.renameTo(out);
        } catch (Exception e) {
            android.util.Log.w("TvHome", "download failed: " + url, e);
            return false;
        } finally {
            if (c != null) c.disconnect();
            if (tmp.isFile()) tmp.delete();
        }
    }
}
