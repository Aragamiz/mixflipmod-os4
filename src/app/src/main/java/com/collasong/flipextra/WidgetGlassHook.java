package com.collasong.flipextra;

import android.graphics.Point;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * P6: 外屏桌面（com.miui.fliphome）小部件常态柔光玻璃 + 通透度调节 + 未补色应用侧边智能补色：
 * 1) 固定 getDeskTopPureBlur() = 1；
 * 2) 根据用户在 FlipMod 中选择的通透度（默认 15% 轻透白 0x26FFFFFF），仅通过原生 setBackgroundBlendColors
 *    替换混色（不额外对子 View 开 PassWindowBlur/BlurRadius，不 hook LauncherFadingView/BlurUtilities）；
 * 3) 当非全屏第三方 App（如 LSPosed 等透明导航栏应用）上报 appColor == 0 导致侧边未补白色时，
 *    自动保持黑字（mamlTextColor = 1）并将小部件磨砂白底抬高至 >=70%（0xB3FFFFFF），保证清晰可读。
 */
public final class WidgetGlassHook {

    private static final String TAG = "FlipExtra/WidgetGlass";
    private static final String HELPER_CLS = "com.miui.fliphome.widget.WidgetBgHelper";
    private static final String BLUR_UTIL_CLS = "com.miui.fliphome.utils.BlurColorUtil";

    private static final int BLEND_MODE_SRC_OVER = 3;
    private static final int COLOR_FILL_MIN_ALPHA_PCT = 70;

    private static Method sIsViewWithoutColor;
    private static Method sSetViewBlurMode;
    private static Method sSetBgBlendColors;

    private static final Map<Object, Boolean> sNeedsColorFillByHelper =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static volatile boolean sLastNeedsColorFill = false;

    private WidgetGlassHook() {
    }

    public static void install(ClassLoader cl) {
        try {
            Class<?> helperCls = cl.loadClass(HELPER_CLS);
            Class<?> blurUtilCls = cl.loadClass(BLUR_UTIL_CLS);

            sIsViewWithoutColor = helperCls.getDeclaredMethod("isViewWithoutColor", View.class);
            sIsViewWithoutColor.setAccessible(true);

            sSetViewBlurMode = blurUtilCls.getMethod("setViewBlurMode", View.class, int.class);
            sSetBgBlendColors = blurUtilCls.getMethod("setBackgroundBlendColors", View.class, ArrayList.class);

            final Field fIsDesk = getDeclaredFieldSafe(helperCls, "isDesk");
            final Field fIsFullScreen = getDeclaredFieldSafe(helperCls, "isFullScreen");
            final Field fAppColor = getDeclaredFieldSafe(helperCls, "appColor");

            XposedCompat.hookAllMethods(helperCls, "getDeskTopPureBlur", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (XposedCompat.getBool(ExtraPrefsUI.KEY_FLIPHOME_WIDGET_GLASS, true)) {
                        param.setResult(1);
                    }
                }
            });

            try {
                Class<?> stateCls = cl.loadClass(HELPER_CLS + "$BackgroundState");
                final Field fBlendFirst = stateCls.getDeclaredField("blendFirstColors");
                final Field fBlendSecond = stateCls.getDeclaredField("blendSecondColors");
                final Field fSecondColor = stateCls.getDeclaredField("blendSecondColor");
                final Field fMamlTextColor = stateCls.getDeclaredField("mamlTextColor");
                fBlendFirst.setAccessible(true);
                fBlendSecond.setAccessible(true);
                fSecondColor.setAccessible(true);
                fMamlTextColor.setAccessible(true);

                XposedCompat.hookAllMethods(helperCls, "setDefaultBlurState", new XposedCompat.SimpleCallback() {
                    @Override
                    public void after(XposedCompat.HookParam param) {
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) {
                            return;
                        }
                        boolean needsFill = computeNeedsColorFill(param.thisObject, fIsDesk, fIsFullScreen, fAppColor);
                        sNeedsColorFillByHelper.put(param.thisObject, needsFill);
                        sLastNeedsColorFill = needsFill;

                        boolean glassOn = XposedCompat.getBool(ExtraPrefsUI.KEY_FLIPHOME_WIDGET_GLASS, true);
                        if (!glassOn && !needsFill) {
                            return;
                        }
                        try {
                            Object state = param.args[0];
                            if (needsFill) {
                                fMamlTextColor.setInt(state, 1);
                            }
                            int tint = resolveTintColor(needsFill);
                            fBlendFirst.set(state, createGlassBlendList(tint));
                            fBlendSecond.set(state, createGlassBlendList(tint));
                            fSecondColor.setInt(state, tint);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            XposedCompat.hookAllMethods(helperCls, "setBlurStateOfHome", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    sNeedsColorFillByHelper.put(param.thisObject, Boolean.FALSE);
                    sLastNeedsColorFill = false;
                }
            });

            XposedCompat.hookAllMethods(helperCls, "setBgOfState", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (param.args == null || param.args.length < 3 || !(param.args[2] instanceof View)) {
                        return;
                    }
                    boolean glassOn = XposedCompat.getBool(ExtraPrefsUI.KEY_FLIPHOME_WIDGET_GLASS, true);
                    boolean needsFill = sLastNeedsColorFill;
                    if (!glassOn && !needsFill) {
                        return;
                    }
                    applyFrostedGlass((View) param.args[2], needsFill);
                }
            });

            XposedCompat.hookAllMethods(helperCls, "translateBackground", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof View)) {
                        return;
                    }
                    Boolean helperNeeds = sNeedsColorFillByHelper.get(param.thisObject);
                    boolean needsFill = helperNeeds != null ? helperNeeds : sLastNeedsColorFill;
                    boolean glassOn = XposedCompat.getBool(ExtraPrefsUI.KEY_FLIPHOME_WIDGET_GLASS, true);
                    if (!glassOn && !needsFill) {
                        return;
                    }
                    applyFrostedGlass((View) param.args[0], needsFill);
                }
            });

            XposedCompat.log(TAG + ": hooked WidgetBgHelper frosted glass + alpha + color fill");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install skipped: " + t);
        }
    }

    private static Field getDeclaredFieldSafe(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean computeNeedsColorFill(Object helper, Field fIsDesk, Field fIsFullScreen, Field fAppColor) {
        if (!XposedCompat.getBool(ExtraPrefsUI.KEY_FLIPHOME_WIDGET_COLOR_FILL, true)) {
            return false;
        }
        if (helper == null || fIsDesk == null || fIsFullScreen == null || fAppColor == null) {
            return false;
        }
        try {
            boolean isDesk = fIsDesk.getBoolean(helper);
            boolean isFull = fIsFullScreen.getBoolean(helper);
            int appColor = fAppColor.getInt(helper);
            // 透明或浅色导航栏（appColor == 0 或 -1，如 LSPosed）在兼容模式下侧边可能未补白边
            return !isDesk && !isFull && (appColor == 0 || appColor == -1);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int resolveTintColor(boolean needsFill) {
        int pct = XposedCompat.getInt(ExtraPrefsUI.KEY_FLIPHOME_WIDGET_GLASS_ALPHA, 15);
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        if (needsFill && pct < COLOR_FILL_MIN_ALPHA_PCT) {
            pct = COLOR_FILL_MIN_ALPHA_PCT;
        }
        int alpha = (pct * 255) / 100;
        return (alpha << 24) | 0x00FFFFFF;
    }

    private static ArrayList<Point> createGlassBlendList(int tintColor) {
        ArrayList<Point> list = new ArrayList<>(1);
        list.add(new Point(tintColor, BLEND_MODE_SRC_OVER));
        return list;
    }

    private static void applyFrostedGlass(View view, boolean needsFill) {
        if (view == null) {
            return;
        }
        try {
            Object withoutColor = sIsViewWithoutColor.invoke(null, view);
            if (Boolean.TRUE.equals(withoutColor)) {
                return;
            }
            view.setBackground(null);
            sSetViewBlurMode.invoke(null, view, 1);
            sSetBgBlendColors.invoke(null, view, createGlassBlendList(resolveTintColor(needsFill)));
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": applyFrostedGlass failed: " + t);
        }
    }
}
