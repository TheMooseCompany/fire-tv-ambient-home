package com.themoosecompany.ambienthome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.View;
import android.view.ViewGroup;
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
 * Ambient Home: a photo wallpaper that rotates, a large clock, one row of apps,
 * and Wi-Fi, Bluetooth and Settings buttons top right.
 * Laid out on a 1920x1080 design grid and scaled to the screen; 5% safe margins.
 */
public class HomeActivity extends Activity {
    static final String TAG = "AmbientHome";

    private static final float DESIGN_W = 1920f;
    private static final float DESIGN_H = 1080f;
    private static final String LEANBACK = "android.intent.category.LEANBACK_LAUNCHER";

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
    private final List<File> photos = new ArrayList<>();
    private int photoIndex = -1;

    private TextView timeView;
    private TextView periodView;
    private TextView dateView;
    private TextView topLabel;
    private LinearLayout appRow;
    private ImageView wifiButton;
    private ImageView bluetoothButton;
    private ImageView settingsButton;
    private FrameLayout allAppsLayer;
    private GridLayout allAppsGrid;

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
        }
        handler.removeCallbacks(clockTick);
        clockTick.run();
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, config.rotateMillis);
        if (allAppsLayer.getVisibility() != View.VISIBLE) focusFirstTile();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(rotate);
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

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1D1F24);
        root.setClipChildren(false);

        wallBack = newWallpaperView();
        wallFront = newWallpaperView();
        root.addView(wallBack, matchParent());
        root.addView(wallFront, matchParent());

        root.addView(buildTopButtons(), topRightParams());
        root.addView(buildBottomBlock(), bottomLeftParams());
        root.addView(buildAllAppsLayer(), matchParent());
        return root;
    }

    private View buildTopButtons() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        top.setClipToPadding(false);

        topLabel = new TextView(this);
        styleText(topLabel, 28, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        topLabel.setGravity(Gravity.CENTER_VERTICAL);
        topLabel.setPadding(px(24), 0, px(24), 0);
        topLabel.setBackground(rounded(0xBF0A0C14, px(28), 0, 0));
        topLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(56));
        labelParams.rightMargin = px(16);
        top.addView(topLabel, labelParams);

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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(72), px(72));
            if (i > 0) lp.leftMargin = px(16);
            top.addView(buttons[i], lp);
        }
        wifiButton.setNextFocusLeftId(wifiButton.getId());
        settingsButton.setNextFocusRightId(settingsButton.getId());
        return top;
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
        styleText(timeView, 160, Typeface.create("sans-serif-light", Typeface.NORMAL), Color.WHITE);
        timeView.setLetterSpacing(-0.025f);
        timeView.setFontFeatureSettings("tnum");
        shadow(timeView);
        clockRow.addView(timeView);

        periodView = new TextView(this);
        styleText(periodView, 44, Typeface.create("sans-serif", Typeface.NORMAL), Color.WHITE);
        shadow(periodView);
        LinearLayout.LayoutParams periodParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        periodParams.leftMargin = px(14);
        clockRow.addView(periodView, periodParams);
        bottom.addView(clockRow);

        dateView = new TextView(this);
        styleText(dateView, 36, Typeface.create("sans-serif", Typeface.NORMAL), Color.WHITE);
        shadow(dateView);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = px(10);
        bottom.addView(dateView, dateParams);

        appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setClipChildren(false);
        appRow.setClipToPadding(false);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = px(44);
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
        scroll.addView(allAppsGrid);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        allAppsLayer.addView(column, matchParent());
        return allAppsLayer;
    }

    // ---------------------------------------------------------------- apps

    private void rebuildApps() {
        appRow.removeAllViews();
        List<AppEntry> installed = loadLaunchableApps();
        for (String wanted : config.apps) {
            final AppEntry app = findApp(installed, wanted);
            if (app == null) {
                Log.w(TAG, "app not found on this TV: " + wanted);
                continue;
            }
            addRowTile(appTile(wanted, false, new Runnable() {
                @Override
                public void run() {
                    launch(app);
                }
            }));
        }
        addRowTile(appTile("All", true, new Runnable() {
            @Override
            public void run() {
                showAllApps();
            }
        }));

        int count = appRow.getChildCount();
        int firstId = appRow.getChildAt(0).getId();
        for (int i = 0; i < count; i++) {
            View tile = appRow.getChildAt(i);
            tile.setNextFocusUpId(wifiButton.getId());
            if (i == 0) tile.setNextFocusLeftId(tile.getId());
            if (i == count - 1) tile.setNextFocusRightId(tile.getId());
        }
        wifiButton.setNextFocusDownId(firstId);
        bluetoothButton.setNextFocusDownId(firstId);
        settingsButton.setNextFocusDownId(firstId);
    }

    private void addRowTile(TextView tile) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(200), px(112));
        if (appRow.getChildCount() > 0) lp.leftMargin = px(24);
        appRow.addView(tile, lp);
    }

    private void focusFirstTile() {
        if (appRow.getChildCount() > 0) appRow.getChildAt(0).requestFocus();
    }

    private void showAllApps() {
        allAppsGrid.removeAllViews();
        for (final AppEntry app : loadLaunchableApps()) {
            addGridTile(appTile(app.label, false, new Runnable() {
                @Override
                public void run() {
                    hideAllApps();
                    launch(app);
                }
            }));
        }
        addGridTile(appTile("Amazon home", false, new Runnable() {
            @Override
            public void run() {
                hideAllApps();
                openAmazonHome();
            }
        }));
        allAppsLayer.setVisibility(View.VISIBLE);
        if (allAppsGrid.getChildCount() > 0) allAppsGrid.getChildAt(0).requestFocus();
    }

    private void addGridTile(TextView tile) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = px(240);
        lp.height = px(112);
        lp.setMargins(px(12), px(12), px(12), px(12));
        allAppsGrid.addView(tile, lp);
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
        File[] files = Config.wallpaperDir(this).listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    photos.add(f);
                }
            }
        }
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

    private void crossfadeTo(Bitmap bmp, boolean animate) {
        if (bmp == null || isFinishing() || isDestroyed()) return;
        Bitmap old = frontBitmap;
        wallBack.setImageBitmap(old);
        wallBack.setAlpha(1f);
        frontBitmap = bmp;
        wallFront.animate().cancel();
        wallFront.setImageBitmap(bmp);
        if (animate && old != null) {
            wallFront.setAlpha(0f);
            wallFront.animate().alpha(1f).setDuration(1500).start();
        } else {
            wallFront.setAlpha(1f);
        }
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

    private TextView appTile(String label, boolean allApps, final Runnable action) {
        final TextView t = new TextView(this);
        t.setId(View.generateViewId());
        t.setText(label);
        styleText(t, 28, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(px(12), 0, px(12), 0);
        if (allApps) {
            Drawable grid = getDrawable(R.drawable.ic_grid);
            if (grid != null) {
                grid = grid.mutate();
                grid.setBounds(0, 0, px(32), px(32));
                t.setCompoundDrawables(grid, null, null, null);
                t.setCompoundDrawablePadding(px(10));
            }
        }
        t.setFocusable(true);
        t.setClickable(true);
        applyTileState(t, false);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyTileState(t, hasFocus);
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return t;
    }

    private void applyTileState(TextView t, boolean focused) {
        // Unfocused: 65% dark glass (white text measured at 6.8:1 or better over every wallpaper).
        t.setBackground(rounded(focused ? Color.WHITE : 0xA60A0C14, px(18), Math.max(1, px(1)), 0x47FFFFFF));
        int fg = focused ? 0xFF14161C : Color.WHITE;
        t.setTextColor(fg);
        for (Drawable d : t.getCompoundDrawables()) {
            if (d != null) d.setColorFilter(fg, PorterDuff.Mode.SRC_IN);
        }
        t.setElevation(focused ? px(12) : 0);
        float s = focused ? 1.08f : 1f;
        t.animate().scaleX(s).scaleY(s).setDuration(150).start();
    }

    private ImageView roundButton(int icon, final String label, final Runnable action) {
        final ImageView v = new ImageView(this);
        v.setId(View.generateViewId());
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = px(20);
        v.setPadding(pad, pad, pad, pad);
        v.setFocusable(true);
        v.setClickable(true);
        v.setContentDescription(label);
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
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(focused ? Color.WHITE : 0x990A0C14);
        bg.setStroke(Math.max(1, px(1)), 0x4DFFFFFF);
        v.setBackground(bg);
        v.setColorFilter(focused ? 0xFF14161C : Color.WHITE, PorterDuff.Mode.SRC_IN);
        float s = focused ? 1.1f : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(150).start();
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
        lp.bottomMargin = px(64);
        return lp;
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
