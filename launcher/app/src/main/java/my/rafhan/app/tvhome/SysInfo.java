package my.rafhan.app.tvhome;

import android.app.ActivityManager;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;

/**
 * The raw readings behind the System card (CPU clock, RAM, storage, network, USB debugging), shared
 * by the card on the home screen and the phone remote's dashboard. Formatting stays with each.
 */
final class SysInfo {
    private SysInfo() {}

    /** {average clock of the online cores in GHz, cores online}; {0, 0} if unreadable. */
    static double[] cpu(int cores) {
        long sum = 0;
        int online = 0;
        for (int c = 0; c < cores; c++) {
            long khz = readLong("/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq");
            if (khz > 0) {
                sum += khz;
                online++;
            }
        }
        return new double[] {online > 0 ? sum / (double) online / 1e6 : 0, online};
    }

    /**
     * {used, installed} bytes. Installed RAM, not what the kernel gets: the firmware keeps ~250 MB
     * for video and the GPU, so a 2 GB TV reports 1.8 GB. That reserve counts as used, like
     * Settings does for the system partitions on the disk.
     */
    static long[] ram(Context ctx) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        long total = installedRam(mi.totalMem);
        return new long[] {total - mi.availMem, total};
    }

    /**
     * {used, total} bytes of the whole storage chip, as Settings shows it (16 GB), not just the
     * /data partition (11.4 GB): the system partitions count as used.
     */
    static long[] disk(Context ctx) {
        long total, free;
        try {
            StorageStatsManager ssm = ctx.getSystemService(StorageStatsManager.class);
            total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT);
            free = ssm.getFreeBytes(StorageManager.UUID_DEFAULT);
        } catch (Exception e) {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            total = fs.getTotalBytes();
            free = fs.getAvailableBytes();
        }
        return new long[] {total - free, total};
    }

    /** Rounds what the kernel reports up to the nearest size RAM is sold in. */
    static long installedRam(long reported) {
        double g = reported / 1073741824.0;
        for (double size : new double[] {0.5, 1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24, 32}) {
            if (g <= size) return (long) (size * 1073741824L);
        }
        return reported;
    }

    /** The active network, as the System card shows it. */
    static final class Net {
        boolean wifi, ethernet;
        String ip;            // IPv4 address, or null
        String name;          // Wi-Fi network name, if known
        int rssi = -127;      // Wi-Fi only; -127 = unknown
        int linkMbps;         // Wi-Fi only
        String dnsHost;       // Private DNS host, or null
        boolean dnsAuto;      // Private DNS on, automatic
    }

    static Net network(Context ctx) {
        Net n = new Net();
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network net = cm.getActiveNetwork();
        NetworkCapabilities nc = net == null ? null : cm.getNetworkCapabilities(net);
        LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
        n.wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        n.ethernet = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                    n.ip = la.getAddress().getHostAddress();
                    break;
                }
            }
            // Private DNS arrived in Android 9: on 8 there's none to show.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                String host = lp.getPrivateDnsServerName();
                n.dnsHost = TextUtils.isEmpty(host) ? null : host;
                n.dnsAuto = n.dnsHost == null && lp.isPrivateDnsActive();
            }
        }
        n.name = NetState.read(ctx).name;
        if (n.wifi) {
            WifiInfo wi = ctx.getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
            if (wi != null) {
                n.rssi = wi.getRssi();
                n.linkMbps = Math.max(0, wi.getLinkSpeed());
            }
        }
        return n;
    }

    static boolean adbOn(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /** Network ADB port ("5555"), or "" when network ADB is off. Blocking (runs getprop). */
    static String adbPort() {
        String port = getprop("service.adb.tcp.port");
        return port.isEmpty() || port.equals("-1") || port.equals("0") ? "" : port;
    }

    private static long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String getprop(String key) {
        try {
            Process p = new ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line == null ? "" : line.trim();
            } finally {
                p.destroy();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
