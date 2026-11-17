package com.themoosecompany.ambienthome;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Settings live in a plain text file in the app's media folder on the TV:
 * /sdcard/Android/media/com.themoosecompany.ambienthome/home.properties
 *
 *   apps=YouTube,Netflix,Prime Video,Stremio
 *   rotate_minutes=10
 *   shuffle=false
 *   motion=true          slow zoom on each photo
 *   idle_seconds=20      hide the apps and buttons after this long without the remote (0 = never)
 *
 * Wallpapers go in the "wallpapers" folder next to it. On Fire OS 8 (Android 11)
 * /sdcard/Android/data/<app> is closed to adb, but the media folder is not, so the install
 * script copies both there. The app needs no storage permission to read its own folders.
 * The older files folder (/sdcard/Android/data/.../files) is still read as a fallback.
 */
final class Config {
    static final String[] DEFAULT_APPS = {"YouTube", "Netflix", "Prime Video", "Stremio"};

    final List<String> apps = new ArrayList<>();
    long rotateMillis = 10 * 60_000L;
    boolean shuffle = false;
    boolean motion = true;
    long idleMillis = 20_000L;

    /** Folders to look in, in order: media folder first, then the app's external and internal files. */
    @SuppressWarnings("deprecation")
    static List<File> dirs(Context c) {
        List<File> out = new ArrayList<>();
        File[] media = c.getExternalMediaDirs();
        if (media != null) {
            for (File m : media) {
                if (m != null) {
                    out.add(m);
                    break;
                }
            }
        }
        File external = c.getExternalFilesDir(null);
        if (external != null) out.add(external);
        out.add(c.getFilesDir());
        return out;
    }

    static List<File> wallpaperDirs(Context c) {
        List<File> out = new ArrayList<>();
        for (File d : dirs(c)) out.add(new File(d, "wallpapers"));
        return out;
    }

    static Config load(Context c) {
        Config cfg = new Config();
        Properties p = new Properties();
        for (File d : dirs(c)) {
            File f = new File(d, "home.properties");
            if (!f.isFile()) continue;
            try (InputStream in = new FileInputStream(f)) {
                p.load(new InputStreamReader(in, "UTF-8"));
                break;
            } catch (IOException ignored) {
                // Try the next folder, then fall back to the defaults below.
            }
        }

        String apps = p.getProperty("apps");
        if (apps == null || apps.trim().isEmpty()) {
            cfg.apps.addAll(Arrays.asList(DEFAULT_APPS));
        } else {
            for (String s : apps.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) cfg.apps.add(s);
            }
        }

        try {
            int minutes = Integer.parseInt(p.getProperty("rotate_minutes", "10").trim());
            if (minutes >= 1) cfg.rotateMillis = minutes * 60_000L;
        } catch (NumberFormatException ignored) {
        }
        // For testing the crossfade: rotate_seconds overrides rotate_minutes (minimum 5).
        String seconds = p.getProperty("rotate_seconds");
        if (seconds != null) {
            try {
                int s = Integer.parseInt(seconds.trim());
                if (s >= 5) cfg.rotateMillis = s * 1000L;
            } catch (NumberFormatException ignored) {
            }
        }
        cfg.shuffle = "true".equalsIgnoreCase(p.getProperty("shuffle", "false").trim());
        cfg.motion = !"false".equalsIgnoreCase(p.getProperty("motion", "true").trim());
        try {
            int idle = Integer.parseInt(p.getProperty("idle_seconds", "20").trim());
            if (idle == 0) cfg.idleMillis = 0;
            else if (idle >= 5) cfg.idleMillis = idle * 1000L;
        } catch (NumberFormatException ignored) {
        }
        return cfg;
    }
}
