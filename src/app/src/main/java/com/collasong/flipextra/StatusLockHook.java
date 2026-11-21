package com.collasong.flipextra;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.reflect.Method;

/**
 * P1: 双击外屏状态栏锁屏（合并进 mixflipmod）。
 */
public final class StatusLockHook {

    private static final String TAG = "FlipExtra/StatusLock";
    private static final long DOUBLE_TAP_TIMEOUT_MS = 350L;
    private static final long LOCK_COOLDOWN_MS = 600L;

    private static long sLastTapUpTime = 0L;
    private static float sLastTapX = 0f;
    private static float sLastTapY = 0f;
    private static long sDownTime = 0L;
    private static float sDownX = 0f;
    private static float sDownY = 0f;
    private static long sLastLockTime = 0L;

    private static Method sIsFlipTinyScreenMethod;
    private static boolean sTriedTinyScreenMethod = false;
    private static boolean sLoggedFirstTouch = false;

    public static void install(ClassLoader cl) {
        try {
            Class<?> miuiConfigs = cl.loadClass("com.miui.utils.configs.MiuiConfigs");
            sIsFlipTinyScreenMethod = miuiConfigs.getMethod("isFlipTinyScreen", Context.class);
            sIsFlipTinyScreenMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }
        sTriedTinyScreenMethod = true;

        boolean hooked = false;
        String[] candidates = new String[]{
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                "com.android.systemui.statusbar.window.StatusBarWindowView"
        };
        for (String clsName : candidates) {
            try {
                Class<?> cls = cl.loadClass(clsName);
                XposedCompat.hookMethod(cls, "dispatchTouchEvent", new Class<?>[]{MotionEvent.class},
                        new XposedCompat.SimpleCallback() {
                            @Override
                            public void before(XposedCompat.HookParam param) {
                                handleTouch((View) param.thisObject, (MotionEvent) param.args[0]);
                            }
                        });
                XposedCompat.log(TAG + ": hooked " + clsName + ".dispatchTouchEvent");
                hooked = true;
                break;
            } catch (Throwable ignored) {
            }
        }
        if (!hooked) {
            XposedCompat.log(TAG + ": no status bar view found");
        }
    }

    private static boolean isOuterScreen(View view) {
        if (sIsFlipTinyScreenMethod != null) {
            try {
                Object res = sIsFlipTinyScreenMethod.invoke(null, view.getContext());
                if (res instanceof Boolean) {
                    return (Boolean) res;
                }
            } catch (Throwable ignored) {
            }
        }
        Display display = view.getDisplay();
        if (display == null) {
            return false;
        }
        if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
            return true;
        }
        try {
            Display.Mode m = display.getMode();
            if (m != null) {
                int mw = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                int mh = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                if (mw == 1208 && mh == 1392) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        android.graphics.Point pt = new android.graphics.Point();
        display.getRealSize(pt);
        int min = Math.min(pt.x, pt.y);
        int max = Math.max(pt.x, pt.y);
        return min == 1208 && max == 1392;
    }

    private static void handleTouch(View view, MotionEvent ev) {
        if (view == null || ev == null) {
            return;
        }
        if (!XposedCompat.getBool(ExtraPrefsUI.KEY_STATUS_DOUBLE_TAP, true)) {
            return;
        }
        if (!isOuterScreen(view)) {
            return;
        }
        if (!sLoggedFirstTouch && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            sLoggedFirstTouch = true;
            Display d = view.getDisplay();
            XposedCompat.log(TAG + ": outer touch received view=" + view.getClass().getSimpleName()
                    + " displayId=" + (d != null ? d.getDisplayId() : -1));
        }

        int action = ev.getActionMasked();
        int touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        long now = SystemClock.uptimeMillis();

        if (action == MotionEvent.ACTION_DOWN) {
            sDownTime = now;
            sDownX = ev.getRawX();
            sDownY = ev.getRawY();
        } else if (action == MotionEvent.ACTION_UP) {
            if (now - sDownTime > 250L) {
                return;
            }
            float dxDown = ev.getRawX() - sDownX;
            float dyDown = ev.getRawY() - sDownY;
            if (dxDown * dxDown + dyDown * dyDown > (float) touchSlop * touchSlop * 4f) {
                return;
            }
            float x = ev.getRawX();
            float y = ev.getRawY();
            if (now - sLastTapUpTime <= DOUBLE_TAP_TIMEOUT_MS
                    && now - sLastLockTime > LOCK_COOLDOWN_MS) {
                float dx = x - sLastTapX;
                float dy = y - sLastTapY;
                float maxDist = touchSlop * 3f;
                if (dx * dx + dy * dy <= maxDist * maxDist) {
                    sLastTapUpTime = 0L;
                    sLastLockTime = now;
                    goToSleep(view.getContext());
                    return;
                }
            }
            sLastTapUpTime = now;
            sLastTapX = x;
            sLastTapY = y;
        }
    }

    private static void goToSleep(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                Method m = PowerManager.class.getMethod("goToSleep", long.class);
                m.invoke(pm, SystemClock.uptimeMillis());
                XposedCompat.log(TAG + ": goToSleep triggered");
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": goToSleep failed: " + t);
        }
    }
}
