package com.themoosecompany.ambienthome;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ambient Home: full-screen family photos that crossfade and slowly zoom, a large clock
 * bottom left, one row of app cards showing each app's own TV banner, and Wi-Fi, Bluetooth
 * and Settings buttons top right. After a while without the remote the apps and buttons fade
 * away, leaving the photo and the clock; the next button press brings them back.
 * Laid out on a 1920x1080 design grid and scaled to the screen; 5% safe margins.
 */
public class HomeActivity extends Activity {
    static final String TAG = "AmbientHome";

    private static final float DESIGN_W = 1920f;
    private static final float DESIGN_H = 1080f;
    private static final String LEANBACK = "android.intent.category.LEANBACK_LAUNCHER";

    private static final int FADE_MS = 1500;
    private static final float ZOOM = 1.06f;
    // Text contrast was measured over every wallpaper: with the floor fade at 70% in idle mode the
    // clock stays at 4.2:1 or better and the date at 5.4:1 or better (3:1 needed for large text).
    private static final float IDLE_FLOOR = 0.7f;

    // Fire OS settings. The individual pages are permission-protected on Fire OS 8, so each
    // button tries its page first and falls back to Fire TV's main Settings screen.
    private static final String MAIN_SETTINGS = "com.amazon.tv.launcher/com.amazon.tv.launcher.ui.MainSettingsActivity";
    private static final String[] NETWORK_PAGE = {
            "com.amazon.tv.settings.v2/com.amazon.tv.settings.v2.tv.network.NetworkActivity", MAIN_SETTINGS};
    private static final String[] BLUETOOTH_PAGE = {
            "com.amazon.tv.settings.v2/com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity",
            MAIN_SETTINGS};
    private static final String[] SETTINGS_HOME = {MAIN_SETTINGS};

    // Used only if an app can't be found by its name on the TV.
    private static final Map<String, String[]> KNOWN_PACKAGES = new HashMap<>();

    static {
        KNOWN_PACKAGES.put("netflix", new String[]{"com.netflix.ninja"});
        KNOWN_PACKAGES.put("youtube", new String[]{"com.amazon.firetv.youtube"});
        KNOWN_PACKAGES.put("primevideo", new String[]{"com.amazon.firebat", "com.amazon.avod"});
        KNOWN_PACKAGES.put("stremio", new String[]{"com.stremio.one"});
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float scale = 1f;
    private Config config;

    private ImageView wallBack;
    private ImageView wallFront;
    private Bitmap frontBitmap;
    private Animator frontZoom;
    private Animator backZoom;
    private final List<File> photos = new ArrayList<>();
    private int photoIndex = -1;

    private View floorScrim;
    private LinearLayout topCluster;
    private TextView timeView;
    private TextView periodView;
    private TextView dateView;
    private TextView topLabel;
    private LinearLayout appRow;
    private final List<View> rowCards = new ArrayList<>();
    private ImageView wifiButton;
    private ImageView bluetoothButton;
    private ImageView settingsButton;
    private FrameLayout allAppsLayer;
    private GridLayout allAppsGrid;

    private boolean idle;
    private int swallowKey = -1;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            showNextPhoto(true);
            handler.postDelayed(this, config.rotateMillis);
        }
    };

    private final Runnable goIdle = new Runnable() {
        @Override
        public void run() {
            setIdle(true, true);
        }
    };

    private int px(float designPx) {
        return Math.round(designPx * scale);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        scale = Math.min(w / DESIGN_W, h / DESIGN_H);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeGuardService.allowAmazonHomeUntil = 0;
        config = Config.load(this);
        rebuildApps();
        loadPhotos();
        if (frontBitmap == null) {
            photoIndex = -1;
            showNextPhoto(false);
        } else {
            resumeZoom();
        }
        handler.removeCallbacks(clockTick);
        clockTick.run();
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, config.rotateMillis);
        swallowKey = -1;
        setIdle(false, false);
        scheduleIdle();
        if (allAppsLayer.getVisibility() != View.VISIBLE) focusFirstTile();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(goIdle);
        // Nothing animates while another app is on screen.
        cancel(frontZoom);
        cancel(backZoom);
        wallFront.animate().cancel();
        wallFront.setAlpha(1f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideAllApps();
        focusFirstTile();
    }

    @Override
    public void onBackPressed() {
        // Back on the home screen does nothing; it only closes the All apps view.
        if (allAppsLayer.getVisibility() == View.VISIBLE) hideAllApps();
    }

    // ---------------------------------------------------------------- idle mode

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == swallowKey) {
            // The press that woke the screen only wakes it; it doesn't also move or open anything.
            if (event.getAction() == KeyEvent.ACTION_UP) swallowKey = -1;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean wasIdle = idle;
            setIdle(false, true);
            scheduleIdle();
            if (wasIdle && isNavigationKey(code)) {
                swallowKey = code;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isNavigationKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    private void scheduleIdle() {
        handler.removeCallbacks(goIdle);
        if (config != null && config.idleMillis > 0) handler.postDelayed(goIdle, config.idleMillis);
    }

    private void setIdle(boolean on, boolean animate) {
        if (on && allAppsLayer.getVisibility() == View.VISIBLE) {
            scheduleIdle();
            return;
        }
        if (idle == on && animate) return;
        idle = on;
        float ui = on ? 0f : 1f;
        float floor = on ? IDLE_FLOOR : 1f;
        View[] views = {topCluster, appRow};
        for (View v : views) {
            v.animate().cancel();
            if (animate) v.animate().alpha(ui).setDuration(on ? 900 : 250).start();
            else v.setAlpha(ui);
        }
        floorScrim.animate().cancel();
        if (animate) floorScrim.animate().alpha(floor).setDuration(on ? 900 : 250).start();
        else floorScrim.setAlpha(floor);
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF111318);
        root.setClipChildren(false);

        wallBack = newWallpaperView();
        wallFront = newWallpaperView();
        root.addView(wallBack, matchParent());
        root.addView(wallFront, matchParent());

        // Two soft fades keep the clock and apps readable on bright photos: one from the left
        // (55% to clear by about half way across) and one rising from the floor (to 90%).
        View leftScrim = new View(this);
        leftScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x8C05060A, 0x0005060A}));
        root.addView(leftScrim, new FrameLayout.LayoutParams(px(998), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        floorScrim = new View(this);
        floorScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0005060A, 0xE605060A}));
        root.addView(floorScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(702), Gravity.BOTTOM));

        root.addView(buildTopButtons(), topRightParams());
        root.addView(buildBottomBlock(), bottomLeftParams());
        root.addView(buildAllAppsLayer(), matchParent());
        return root;
    }

    private View buildTopButtons() {
        topCluster = new LinearLayout(this);
        topCluster.setOrientation(LinearLayout.HORIZONTAL);
        topCluster.setGravity(Gravity.CENTER_VERTICAL);
        topCluster.setClipChildren(false);
        topCluster.setClipToPadding(false);

        topLabel = new TextView(this);
        styleText(topLabel, 28, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        topLabel.setGravity(Gravity.CENTER_VERTICAL);
        topLabel.setPadding(px(22), 0, px(22), 0);
        topLabel.setBackground(rounded(0xB30A0C14, px(26), 0, 0));
        topLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(52));
        labelParams.rightMargin = px(14);
        topCluster.addView(topLabel, labelParams);

        // One tinted capsule holds the three buttons. Fire OS 8 is Android 11, which can't blur
        // what's behind a view, so the capsule is a darker tint instead (icons 6.5:1 or better).
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClipChildren(false);
        pill.setClipToPadding(false);
        int pad = px(8);
        pill.setPadding(pad, pad, pad, pad);
        pill.setBackground(rounded(0x990A0C14, px(40), Math.max(1, px(1)), 0x2EFFFFFF));

        wifiButton = roundButton(R.drawable.ic_wifi, "Wi-Fi", new Runnable() {
            @Override
            public void run() {
                openSettings(NETWORK_PAGE);
            }
        });
        bluetoothButton = roundButton(R.drawable.ic_bluetooth, "Bluetooth", new Runnable() {
            @Override
            public void run() {
                openSettings(BLUETOOTH_PAGE);
            }
        });
        settingsButton = roundButton(R.drawable.ic_settings, "Settings", new Runnable() {
            @Override
            public void run() {
                openSettings(SETTINGS_HOME);
            }
        });
        ImageView[] buttons = {wifiButton, bluetoothButton, settingsButton};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(64), px(64));
            if (i > 0) lp.leftMargin = px(8);
            pill.addView(buttons[i], lp);
        }
        wifiButton.setNextFocusLeftId(wifiButton.getId());
        settingsButton.setNextFocusRightId(settingsButton.getId());
        topCluster.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return topCluster;
    }

    private View buildBottomBlock() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setBaselineAligned(true);

        timeView = new TextView(this);
        styleText(timeView, 140, Typeface.create("sans-serif-light", Typeface.NORMAL), Color.WHITE);
        timeView.setLetterSpacing(-0.02f);
        timeView.setFontFeatureSettings("tnum");
        shadow(timeView);
        clockRow.addView(timeView);

        periodView = new TextView(this);
        styleText(periodView, 40, Typeface.create("sans-serif-light", Typeface.NORMAL), Color.WHITE);
        shadow(periodView);
        LinearLayout.LayoutParams periodParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        periodParams.leftMargin = px(12);
        clockRow.addView(periodView, periodParams);
        bottom.addView(clockRow);

        dateView = new TextView(this);
        styleText(dateView, 34, Typeface.create("sans-serif", Typeface.NORMAL), 0xFFEEF1F6);
        shadow(dateView);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = px(8);
        bottom.addView(dateView, dateParams);

        appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setClipChildren(false);
        appRow.setClipToPadding(false);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = px(36);
        bottom.addView(appRow, rowParams);
        return bottom;
    }

    private View buildAllAppsLayer() {
        allAppsLayer = new FrameLayout(this);
        allAppsLayer.setBackgroundColor(0xE60A0C14);
        allAppsLayer.setVisibility(View.GONE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(112), px(64), px(112), px(32));
        column.setClipChildren(false);

        TextView title = new TextView(this);
        styleText(title, 40, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        title.setText("All apps");
        column.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding(0, px(24), 0, px(24));
        allAppsGrid = new GridLayout(this);
        allAppsGrid.setColumnCount(6);
        allAppsGrid.setClipChildren(false);
        allAppsGrid.setClipToPadding(false);
        scroll.addView(allAppsGrid);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        allAppsLayer.addView(column, matchParent());
        return allAppsLayer;
    }

    // ---------------------------------------------------------------- apps

    private void rebuildApps() {
        appRow.removeAllViews();
        rowCards.clear();
        List<AppEntry> installed = loadLaunchableApps();
        for (String wanted : config.apps) {
            final AppEntry app = findApp(installed, wanted);
            if (app == null) {
                Log.w(TAG, "app not found on this TV: " + wanted);
                continue;
            }
            Logo logo = loadLogo(app.component);
            Log.i(TAG, "logo: " + wanted + " = " + (logo.banner != null ? "banner" : logo.icon != null ? "icon" : "none"));
            addRowCard(card(wanted, logo, false, px(256), px(144), 1.1f, true, new Runnable() {
                @Override
                public void run() {
                    launch(app);
                }
            }));
        }
        addRowCard(card("All apps", null, true, px(256), px(144), 1.1f, true, new Runnable() {
            @Override
            public void run() {
                showAllApps();
            }
        }));

        int count = rowCards.size();
        int firstId = rowCards.get(0).getId();
        for (int i = 0; i < count; i++) {
            View c = rowCards.get(i);
            c.setNextFocusUpId(wifiButton.getId());
            if (i == 0) c.setNextFocusLeftId(c.getId());
            if (i == count - 1) c.setNextFocusRightId(c.getId());
        }
        wifiButton.setNextFocusDownId(firstId);
        bluetoothButton.setNextFocusDownId(firstId);
        settingsButton.setNextFocusDownId(firstId);
    }

    private void addRowCard(LinearLayout column) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (appRow.getChildCount() > 0) lp.leftMargin = px(28);
        appRow.addView(column, lp);
        rowCards.add(column.getChildAt(0));
    }

    private void focusFirstTile() {
        if (!rowCards.isEmpty()) rowCards.get(0).requestFocus();
    }

    private void showAllApps() {
        allAppsGrid.removeAllViews();
        for (final AppEntry app : loadLaunchableApps()) {
            addGridCard(card(app.label, loadLogo(app.component), false, px(240), px(135), 1.08f, false, new Runnable() {
                @Override
                public void run() {
                    hideAllApps();
                    launch(app);
                }
            }));
        }
        Logo amazon = new Logo();
        try {
            amazon.icon = getPackageManager().getApplicationIcon(HomeGuardService.AMAZON_LAUNCHER);
        } catch (Exception ignored) {
            // Shown with its name only.
        }
        addGridCard(card("Amazon home", amazon, false, px(240), px(135), 1.08f, false, new Runnable() {
            @Override
            public void run() {
                hideAllApps();
                openAmazonHome();
            }
        }));
        allAppsLayer.setVisibility(View.VISIBLE);
        if (allAppsGrid.getChildCount() > 0) {
            ((ViewGroup) allAppsGrid.getChildAt(0)).getChildAt(0).requestFocus();
        }
    }

    private void addGridCard(LinearLayout column) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.setMargins(px(12), px(12), px(12), px(20));
        allAppsGrid.addView(column, lp);
    }

    private void hideAllApps() {
        if (allAppsLayer.getVisibility() != View.VISIBLE) return;
        allAppsLayer.setVisibility(View.GONE);
        focusFirstTile();
    }

    private List<AppEntry> loadLaunchableApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        collect(pm, LEANBACK, true, byPackage);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, byPackage);
        List<AppEntry> list = new ArrayList<>(byPackage.values());
        Collections.sort(list, new java.util.Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    private void collect(PackageManager pm, String category, boolean leanback, Map<String, AppEntry> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            out.put(pkg, new AppEntry(label == null ? pkg : label.toString(), pkg,
                    new ComponentName(pkg, ri.activityInfo.name), leanback));
        }
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static AppEntry findApp(List<AppEntry> apps, String wanted) {
        String w = norm(wanted);
        if (w.isEmpty()) return null;
        for (AppEntry a : apps) if (norm(a.label).equals(w)) return a;
        for (AppEntry a : apps) {
            String l = norm(a.label);
            if (!l.isEmpty() && (l.startsWith(w) || w.startsWith(l))) return a;
        }
        String[] known = KNOWN_PACKAGES.get(w);
        if (known != null) {
            for (String pkg : known) for (AppEntry a : apps) if (a.packageName.equals(pkg)) return a;
        }
        return null;
    }

    /**
     * Each app's own artwork, read from the app on the TV: its 16:9 TV banner if it has one
     * (the activity's, then the app's), and its icon. Nothing is bundled, so no brand logos
     * live in this app or its repository.
     */
    private Logo loadLogo(ComponentName component) {
        PackageManager pm = getPackageManager();
        Logo logo = new Logo();
        try {
            ActivityInfo ai = pm.getActivityInfo(component, 0);
            logo.banner = ai.loadBanner(pm);
            logo.icon = ai.loadIcon(pm);
        } catch (Exception e) {
            Log.w(TAG, "no activity info for " + component.flattenToShortString());
        }
        String pkg = component.getPackageName();
        if (logo.banner == null) {
            try {
                logo.banner = pm.getApplicationBanner(pkg);
            } catch (Exception ignored) {
                // Falls back to the icon card.
            }
        }
        if (logo.icon == null) {
            try {
                logo.icon = pm.getApplicationIcon(pkg);
            } catch (Exception ignored) {
                // Falls back to the name alone.
            }
        }
        return logo;
    }

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? LEANBACK : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "could not open " + app.packageName, e);
            Toast.makeText(this, "Couldn't open " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings(String[] components) {
        for (String c : components) {
            ComponentName cn = ComponentName.unflattenFromString(c);
            if (cn == null) continue;
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Log.i(TAG, "opened " + c);
                return;
            } catch (Exception e) {
                Log.i(TAG, "can't open " + c + ": " + e);
            }
        }
        // Last resort. On Fire OS 8 this goes through Amazon's home first, so let it through briefly.
        HomeGuardService.allowAmazonHomeUntil = SystemClock.elapsedRealtime() + 5000;
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "no settings screen could be opened", e);
            Toast.makeText(this, "Couldn't open Settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAmazonHome() {
        // Lets Amazon's home stay up for 10 minutes; coming back here ends that early.
        HomeGuardService.allowAmazonHomeUntil = SystemClock.elapsedRealtime() + 10 * 60_000L;
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeGuardService.AMAZON_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "could not open Amazon home", e);
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private void loadPhotos() {
        photos.clear();
        for (File dir : Config.wallpaperDirs(this)) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    photos.add(f);
                }
            }
            if (!photos.isEmpty()) break; // use the first folder that has photos
        }
        Log.i(TAG, "wallpapers found: " + photos.size());
        Collections.sort(photos);
        if (config.shuffle) Collections.shuffle(photos);
    }

    private void showNextPhoto(final boolean animate) {
        if (photos.isEmpty()) return;
        photoIndex = (photoIndex + 1) % photos.size();
        final File file = photos.get(photoIndex);
        final int targetWidth = getResources().getDisplayMetrics().widthPixels;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decode(file, targetWidth);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        crossfadeTo(bmp, animate);
                    }
                });
            }
        }, "wallpaper-decode").start();
    }

    private static Bitmap decode(File file, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    /**
     * The new photo fades in over the old one and starts a slow zoom (6% over its time on
     * screen). The old photo keeps zooming at the same speed while it fades out, so nothing jumps.
     */
    private void crossfadeTo(Bitmap bmp, boolean animate) {
        if (bmp == null || isFinishing() || isDestroyed()) return;
        Bitmap old = frontBitmap;
        float oldScale = wallFront.getScaleX();
        long life = config.rotateMillis + FADE_MS;
        float perMs = (ZOOM - 1f) / life;

        cancel(frontZoom);
        cancel(backZoom);
        wallBack.setImageBitmap(old);
        wallBack.setAlpha(1f);
        setScale(wallBack, oldScale);
        backZoom = (config.motion && old != null) ? zoom(wallBack, oldScale, oldScale + perMs * FADE_MS, FADE_MS) : null;

        frontBitmap = bmp;
        wallFront.animate().cancel();
        wallFront.setImageBitmap(bmp);
        setScale(wallFront, 1f);
        if (animate && old != null) {
            wallFront.setAlpha(0f);
            wallFront.animate().alpha(1f).setDuration(FADE_MS).start();
        } else {
            wallFront.setAlpha(1f);
        }
        frontZoom = config.motion ? zoom(wallFront, 1f, ZOOM, life) : null;
    }

    private void resumeZoom() {
        cancel(frontZoom);
        if (!config.motion) {
            setScale(wallFront, 1f);
            frontZoom = null;
            return;
        }
        float s = wallFront.getScaleX();
        frontZoom = zoom(wallFront, s, Math.min(s + (ZOOM - 1f), 1.12f), config.rotateMillis + FADE_MS);
    }

    private static Animator zoom(View v, float from, float to, long ms) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to));
        a.setDuration(ms);
        a.setInterpolator(new LinearInterpolator());
        a.start();
        return a;
    }

    private static void cancel(Animator a) {
        if (a != null) a.cancel();
    }

    private static void setScale(View v, float s) {
        v.setScaleX(s);
        v.setScaleY(s);
    }

    // ---------------------------------------------------------------- clock

    private void updateClock() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        timeView.setText(new SimpleDateFormat(h24 ? "H:mm" : "h:mm", locale).format(now));
        if (h24) {
            periodView.setVisibility(View.GONE);
        } else {
            periodView.setVisibility(View.VISIBLE);
            periodView.setText(new SimpleDateFormat("a", locale).format(now));
        }
        dateView.setText(new SimpleDateFormat("EEEE, MMMM d", locale).format(now));
    }

    // ---------------------------------------------------------------- widgets

    /**
     * A 16:9 app card with its name underneath. The card shows the app's TV banner; an app
     * without one gets its icon and name on a card tinted with the icon's own colour.
     * Focus: the card grows, gains a white ring and a deeper shadow, and its neighbours dim.
     */
    private LinearLayout card(final String name, Logo logo, boolean allApps, int w, int h,
                              final float focusScale, final boolean nameOnFocusOnly, final Runnable action) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        final FrameLayout holder = new FrameLayout(this);
        holder.setId(View.generateViewId());
        holder.setFocusable(true);
        holder.setClickable(true);
        holder.setContentDescription(name);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        noDefaultHighlight(holder);

        int radius = Math.round(h * 16f / 144f);
        final FrameLayout face = new FrameLayout(this);
        face.setClipToOutline(true);
        if (allApps) {
            face.setBackground(rounded(0x29FFFFFF, radius, 0, 0));
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setGravity(Gravity.CENTER);
            ImageView grid = new ImageView(this);
            grid.setImageResource(R.drawable.ic_grid);
            grid.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            inner.addView(grid, new LinearLayout.LayoutParams(px(34), px(34)));
            TextView t = new TextView(this);
            styleText(t, 30, Typeface.create("sans-serif", Typeface.BOLD), Color.WHITE);
            t.setText(name);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = px(12);
            inner.addView(t, tp);
            face.addView(inner, matchParent());
        } else if (logo != null && logo.banner != null) {
            face.setBackground(rounded(logo.icon != null ? brandColor(logo.icon) : 0xFF1A1C22, radius, 0, 0));
            ImageView banner = new ImageView(this);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(logo.banner);
            face.addView(banner, matchParent());
        } else {
            int base = logo != null && logo.icon != null ? brandColor(logo.icon) : 0xFF2A2D36;
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{base, darker(base, 0.55f)});
            bg.setCornerRadius(radius);
            face.setBackground(bg);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(px(12), 0, px(12), 0);
            if (logo != null && logo.icon != null) {
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(logo.icon);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int size = Math.round(h * 0.44f);
                inner.addView(icon, new LinearLayout.LayoutParams(size, size));
            }
            TextView t = new TextView(this);
            styleText(t, h * 24f / 144f / scale, Typeface.create("sans-serif", Typeface.BOLD), Color.WHITE);
            t.setText(name);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = Math.round(h * 10f / 144f);
            inner.addView(t, tp);
            face.addView(inner, matchParent());
        }
        holder.addView(face, new FrameLayout.LayoutParams(w, h));

        // The focus ring sits just outside the card.
        int ringW = Math.max(2, px(4));
        final View ring = new View(this);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setColor(Color.TRANSPARENT);
        ringBg.setCornerRadius(radius + ringW);
        ringBg.setStroke(ringW, Color.WHITE);
        ring.setBackground(ringBg);
        ring.setAlpha(0f);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(w + 2 * ringW, h + 2 * ringW);
        rp.leftMargin = -ringW;
        rp.topMargin = -ringW;
        holder.addView(ring, rp);
        column.addView(holder, new LinearLayout.LayoutParams(w, h));

        final TextView label = new TextView(this);
        styleText(label, h >= px(144) ? 28 : 24, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        label.setText(name);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        shadow(label);
        final boolean showName = !allApps;
        label.setAlpha(showName && !nameOnFocusOnly ? 1f : 0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(16);
        column.addView(label, lp);

        applyCardState(holder, face, ring, label, false, focusScale, showName && nameOnFocusOnly);
        holder.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyCardState(holder, face, ring, label, hasFocus, focusScale, showName && nameOnFocusOnly);
            }
        });
        holder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return column;
    }

    private void applyCardState(View holder, View face, View ring, TextView label, boolean focused,
                                float focusScale, boolean nameOnFocus) {
        float s = focused ? focusScale : 1f;
        DecelerateInterpolator ease = new DecelerateInterpolator();
        holder.animate().scaleX(s).scaleY(s).alpha(focused ? 1f : 0.82f).setDuration(160).setInterpolator(ease).start();
        face.animate().translationZ(focused ? px(18) : px(4)).setDuration(160).start();
        ring.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
        if (nameOnFocus) label.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
    }

    /** The icon's main colour, darkened so white text on it stays readable. */
    private static int brandColor(Drawable icon) {
        try {
            int n = 32;
            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
            d.setBounds(0, 0, n, n);
            d.draw(new Canvas(b));
            double r = 0, g = 0, bl = 0, total = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    // Saturated pixels say more about a brand than white, grey or black ones.
                    double weight = (0.1 + hsv[1]) * (hsv[2] < 0.15f ? 0.3 : 1.0);
                    r += Color.red(p) * weight;
                    g += Color.green(p) * weight;
                    bl += Color.blue(p) * weight;
                    total += weight;
                }
            }
            b.recycle();
            if (total == 0) return 0xFF2A2D36;
            int avg = Color.rgb((int) (r / total), (int) (g / total), (int) (bl / total));
            Color.colorToHSV(avg, hsv);
            hsv[1] = Math.min(hsv[1], 0.75f);
            hsv[2] = Math.max(0.16f, Math.min(hsv[2], 0.42f));
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF2A2D36;
        }
    }

    private static int darker(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= factor;
        return Color.HSVToColor(hsv);
    }

    private ImageView roundButton(int icon, final String label, final Runnable action) {
        final ImageView v = new ImageView(this);
        v.setId(View.generateViewId());
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = px(17);
        v.setPadding(pad, pad, pad, pad);
        v.setFocusable(true);
        v.setClickable(true);
        v.setContentDescription(label);
        noDefaultHighlight(v);
        applyRoundState(v, false);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                applyRoundState(v, hasFocus);
                if (hasFocus) {
                    topLabel.setText(label);
                    topLabel.setVisibility(View.VISIBLE);
                } else {
                    topLabel.setVisibility(View.GONE);
                }
            }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        return v;
    }

    private void applyRoundState(ImageView v, boolean focused) {
        if (focused) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.WHITE);
            v.setBackground(bg);
        } else {
            v.setBackground(null);
        }
        v.setColorFilter(focused ? 0xFF14161C : Color.WHITE, PorterDuff.Mode.SRC_IN);
        float s = focused ? 1.08f : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(150).start();
    }

    /** Android 8+ can draw its own grey focus overlay; these views draw their own focus. */
    private static void noDefaultHighlight(View v) {
        if (Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
    }

    private void styleText(TextView t, float designSize, Typeface tf, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(designSize));
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    private void shadow(TextView t) {
        t.setShadowLayer(px(16), 0, px(2), 0x73000000);
    }

    private static GradientDrawable rounded(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor);
        return g;
    }

    private ImageView newWallpaperView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Zoom toward a point a little above centre, where faces sit in these photos.
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                view.setPivotX((r - l) * 0.5f);
                view.setPivotY((b - t) * 0.42f);
            }
        });
        return v;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams topRightParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = px(64);
        lp.rightMargin = px(112);
        return lp;
    }

    private FrameLayout.LayoutParams bottomLeftParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = px(112);
        lp.bottomMargin = px(56);
        return lp;
    }

    static final class Logo {
        Drawable banner;
        Drawable icon;
    }

    static final class AppEntry {
        final String label;
        final String packageName;
        final ComponentName component;
        final boolean leanback;

        AppEntry(String label, String packageName, ComponentName component, boolean leanback) {
            this.label = label;
            this.packageName = packageName;
            this.component = component;
            this.leanback = leanback;
        }
    }
}
