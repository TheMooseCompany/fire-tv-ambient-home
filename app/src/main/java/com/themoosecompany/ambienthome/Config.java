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
 * Settings live in a plain text file in the app's own folder on the TV:
 * /sdcard/Android/data/com.themoosecompany.ambienthome/files/home.properties
 *
 *   apps=YouTube,Netflix,Prime Video,Stremio
 *   rotate_minutes=10
 *   shuffle=false
 *
 * Wallpapers go in the "wallpapers" folder next to it. Both are copied there over adb;
 * the app itself needs no storage permission to read its own folder.
 */
final class Config {
    static final String[] DEFAULT_APPS = {"YouTube", "Netflix", "Prime Video", "Stremio"};

    final List<String> apps = new ArrayList<>();
    long rotateMillis = 10 * 60_000L;
    boolean shuffle = false;

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    static File wallpaperDir(Context c) {
        return new File(dir(c), "wallpapers");
    }

    static Config load(Context c) {
        Config cfg = new Config();
        Properties p = new Properties();
        File f = new File(dir(c), "home.properties");
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(new InputStreamReader(in, "UTF-8"));
            } catch (IOException ignored) {
                // Fall back to the defaults below.
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
        return cfg;
    }
}
