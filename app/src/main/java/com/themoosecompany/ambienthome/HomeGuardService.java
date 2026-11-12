package com.themoosecompany.ambienthome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS has no "default launcher" setting, and the Home key never reaches apps.
 * This service is told (by res/xml/home_guard.xml) only about window changes in Amazon's
 * launcher package, and it cannot read window content. When Amazon's home screen itself
 * comes to the front, it brings Ambient Home back. Amazon's Settings screens live in the
 * same package and are left alone.
 */
public class HomeGuardService extends AccessibilityService {
    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";

    /** Set by the home screen when the user opens Amazon's home on purpose. */
    static volatile long allowAmazonHomeUntil = 0;

    private long lastRedirect = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null || !AMAZON_LAUNCHER.contentEquals(pkg)) return;

        String className = cls.toString();
        if (!isAmazonHome(className)) {
            Log.d(HomeActivity.TAG, "guard: leaving " + className + " alone");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < allowAmazonHomeUntil) return;
        if (now - lastRedirect < 700) return;
        lastRedirect = now;

        Log.i(HomeActivity.TAG, "guard: " + className + " came up, returning to Ambient Home");
        Intent home = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(home);
        } catch (Exception e) {
            Log.w(HomeActivity.TAG, "guard: could not start home", e);
        }
    }

    /** Amazon's home is com.amazon.tv.launcher.ui.HomeActivity_vNext on Fire OS 8; Settings is MainSettingsActivity. */
    static boolean isAmazonHome(String className) {
        return className.contains("Home") && !className.contains("Settings");
    }

    @Override
    public void onInterrupt() {
    }
}
