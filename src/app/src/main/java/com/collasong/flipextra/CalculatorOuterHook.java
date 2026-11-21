package com.collasong.flipextra;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * 适配开启 MixFlipMod 计算器外屏开关（hide_outer_com.miui.calculator）后的外屏 UI：
 * 1) 在外屏模式下将 Activity 的 densityDpi 按 0.76x 缩放，使普通计算器、大写数字、换算等所有页面获得充足纵向空间、不裁切文字；
 * 2) 在 CalculatorFragment.C1() 后兜底保证数字显示区至少占 35% 高度，防止被 (w/4)*5 键盘高度挤没。
 */
public final class CalculatorOuterHook {

    private static final String TAG = "FlipExtra/Calc";
    private static final float OUTER_DENSITY_SCALE = 0.76f;
    private static final WeakHashMap<View, Boolean> sHookedRoots = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Integer> sOrigDensityDpi = new WeakHashMap<>();
    private static volatile boolean sInstalled = false;
    private static volatile boolean sLoggedOnce = false;

    private CalculatorOuterHook() {
    }

    public static synchronized void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            XposedCompat.hookAllMethods(Activity.class, "onCreate", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.thisObject instanceof Activity) {
                        applyOuterDensity((Activity) param.thisObject);
                    }
                }
            });

            XposedCompat.hookAllMethods(Activity.class, "onConfigurationChanged", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (param.thisObject instanceof Activity) {
                        applyOuterDensity((Activity) param.thisObject);
                    }
                }
            });

            XposedCompat.hookAllMethods(Activity.class, "onResume", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (!(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity act = (Activity) param.thisObject;
                    if (!act.getClass().getName().startsWith("com.miui.calculator")) {
                        return;
                    }
                    applyOuterDensity(act);
                    Window w = act.getWindow();
                    View decor = w != null ? w.getDecorView() : null;
                    if (decor == null) {
                        return;
                    }
                    ensureLayoutListener(decor);
                    decor.post(() -> adjustCalculatorLayout(decor));
                }
            });

            try {
                Class<?> fragCls = cl.loadClass("com.miui.calculator.cal.CalculatorFragment");
                Method c1 = fragCls.getDeclaredMethod("C1");
                XposedCompat.hook(c1, new XposedCompat.SimpleCallback() {
                    @Override
                    public void after(XposedCompat.HookParam param) {
                        try {
                            Method getView = fragCls.getMethod("getView");
                            Object v = getView.invoke(param.thisObject);
                            if (v instanceof View) {
                                adjustCalculatorLayout((View) v);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            XposedCompat.log(TAG + ": hooks installed");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install failed: " + t);
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyOuterDensity(Activity act) {
        if (act == null || !act.getClass().getName().startsWith("com.miui.calculator")) {
            return;
        }
        if (!XposedCompat.getBool("hide_outer_com.miui.calculator", false)) {
            return;
        }
        if (!isOuterDisplay(act, act.getWindow() != null ? act.getWindow().getDecorView() : null)) {
            return;
        }
        try {
            Configuration cur = act.getResources().getConfiguration();
            Integer origDpi;
            synchronized (sOrigDensityDpi) {
                origDpi = sOrigDensityDpi.get(act);
                if (origDpi == null) {
                    origDpi = cur.densityDpi;
                    sOrigDensityDpi.put(act, origDpi);
                }
            }
            int targetDpi = Math.max(240, Math.round(origDpi * OUTER_DENSITY_SCALE));
            if (cur.densityDpi != targetDpi) {
                Configuration next = new Configuration(cur);
                next.densityDpi = targetDpi;
                DisplayMetrics dm = new DisplayMetrics();
                dm.setTo(act.getResources().getDisplayMetrics());
                dm.densityDpi = targetDpi;
                dm.density = targetDpi / 160.0f;
                dm.scaledDensity = dm.density * cur.fontScale;
                act.getResources().updateConfiguration(next, dm);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureLayoutListener(final View decor) {
        synchronized (sHookedRoots) {
            if (sHookedRoots.containsKey(decor)) {
                return;
            }
            sHookedRoots.put(decor, Boolean.TRUE);
        }
        decor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> adjustCalculatorLayout(v));
    }

    private static void adjustCalculatorLayout(View root) {
        if (root == null) {
            return;
        }
        Context ctx = root.getContext();
        if (ctx == null || !isOuterDisplay(ctx, root)) {
            return;
        }
        if (!XposedCompat.getBool("hide_outer_com.miui.calculator", false)) {
            return;
        }
        View padView = findNumberPad(root);
        if (padView == null) {
            return;
        }
        View padContainer = padView;
        ViewParent p = padView.getParent();
        if (p instanceof View && !(p instanceof LinearLayout && hasFrameSiblingAbove((LinearLayout) p, (View) padView))) {
            ViewParent gp = p.getParent();
            if (gp instanceof LinearLayout && hasFrameSiblingAbove((LinearLayout) gp, (View) p)) {
                padContainer = (View) p;
                p = gp;
            }
        }
        if (!(p instanceof LinearLayout)) {
            return;
        }
        LinearLayout mainLinear = (LinearLayout) p;
        if (mainLinear.getOrientation() != LinearLayout.VERTICAL || mainLinear.getHeight() <= 0) {
            return;
        }
        int padIdx = mainLinear.indexOfChild(padContainer);
        if (padIdx <= 0) {
            return;
        }
        View displayContainer = null;
        for (int i = padIdx - 1; i >= 0; i--) {
            View c = mainLinear.getChildAt(i);
            if (c instanceof FrameLayout && c.getVisibility() == View.VISIBLE) {
                displayContainer = c;
                break;
            }
        }
        if (displayContainer == null) {
            return;
        }
        if (!(displayContainer.getLayoutParams() instanceof LinearLayout.LayoutParams)
                || !(padContainer.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams lpDisplay = (LinearLayout.LayoutParams) displayContainer.getLayoutParams();
        LinearLayout.LayoutParams lpPad = (LinearLayout.LayoutParams) padContainer.getLayoutParams();

        boolean needsUpdate = lpDisplay.height != 0
                || lpPad.height != 0
                || Math.abs(lpDisplay.weight - 0.35f) > 0.02f
                || Math.abs(lpPad.weight - 0.65f) > 0.02f;

        if (!needsUpdate) {
            return;
        }

        lpDisplay.height = 0;
        lpDisplay.weight = 0.35f;
        lpDisplay.topMargin = 0;
        lpDisplay.bottomMargin = 0;

        lpPad.height = 0;
        lpPad.weight = 0.65f;
        lpPad.topMargin = 0;
        lpPad.bottomMargin = dp(ctx, 4);

        displayContainer.setLayoutParams(lpDisplay);
        padContainer.setLayoutParams(lpPad);

        if (!sLoggedOnce) {
            sLoggedOnce = true;
            XposedCompat.log(TAG + ": adjusted outer layout mainH=" + mainLinear.getHeight()
                    + " dpi=" + ctx.getResources().getConfiguration().densityDpi);
        }
    }

    private static boolean hasFrameSiblingAbove(LinearLayout ll, View child) {
        if (ll.getOrientation() != LinearLayout.VERTICAL) {
            return false;
        }
        int idx = ll.indexOfChild(child);
        for (int i = idx - 1; i >= 0; i--) {
            if (ll.getChildAt(i) instanceof FrameLayout) {
                return true;
            }
        }
        return false;
    }

    private static View findNumberPad(View v) {
        String name = v.getClass().getName();
        if (name.endsWith(".NumberPad") || name.endsWith(".NumberPadForLitePhone")) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findNumberPad(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isOuterDisplay(Context ctx, View v) {
        try {
            Display d = v != null ? v.getDisplay() : null;
            if (d == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                d = ctx.getDisplay();
            }
            if (d == null) {
                DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                }
            }
            if (d != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode mode = d.getMode();
                int pw = mode.getPhysicalWidth();
                int ph = mode.getPhysicalHeight();
                if ((pw == 1208 && ph == 1392) || (pw == 1392 && ph == 1208)) {
                    return true;
                }
                if (pw == 1224 && ph == 2912) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.heightPixels <= dm.widthPixels * 1.6f;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
