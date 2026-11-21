package com.collasong.flipextra;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统层面外屏真全屏 Hook（在 system_server 中运行）：
 * 1. 隐藏 com.miui.home 在外屏底部叠加的 32px 半透明灰/白防误触条（AntiMistakeTouchView）；
 * 2. 接管 MiuiSizeCompatService.getSkipBottomInsetsActivities，消除 Xiaomi BoundsCompat
 *    强行注入兼容模式 App 的 (21.0f / scale) 底部导航栏留白（android:id/navigationBarBackground）。
 */
public final class OuterFullscreenHook {

    private static final String TAG = "FlipExtra/OuterFS";
    private static final String TARGET_TITLE = "AntiMistakeTouchView";

    private static volatile Method sCompatGetMethod;
    private static volatile Method sIsFlipFoldedMethod;
    private static volatile boolean sCompatInitDone = false;

    private static volatile Field sAnimWinField;
    private static volatile Field sAnimAlphaField;
    private static volatile Field sAnimShownAlphaField;
    private static volatile boolean sAnimFieldsInitDone = false;

    private static volatile Field sWinAttrsField;
    private static volatile Method sWinGetBoundsMethod;
    private static volatile boolean sWinFieldsInitDone = false;

    private static volatile Field sScsContextField;
    private static final ConcurrentHashMap<String, Map<String, String>> sSkipInsetsCache =
            new ConcurrentHashMap<>();

    private static volatile boolean sLoggedHide = false;
    private static volatile boolean sLoggedSkipInsets = false;

    private OuterFullscreenHook() {
    }

    public static void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> wsCls = cl.loadClass("com.android.server.wm.WindowState");
            XposedCompat.hookAllMethods(wsCls, "isVisibleByPolicy", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (shouldHideWindow(cl, param.thisObject)) {
                        param.setResult(Boolean.FALSE);
                        if (!sLoggedHide) {
                            sLoggedHide = true;
                            XposedCompat.log(TAG + ": hid AntiMistakeTouchView via isVisibleByPolicy=false");
                        }
                    }
                }
            });
            XposedCompat.log(TAG + ": hooked WindowState.isVisibleByPolicy");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hook WindowState.isVisibleByPolicy failed: " + t);
        }

        try {
            Class<?> wmsCls = cl.loadClass("com.android.server.wm.WindowManagerService");
            XposedCompat.hookAllMethods(wmsCls, "addWindow", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    handleLayoutParamsArgs(cl, param.args);
                }
            });
            XposedCompat.hookAllMethods(wmsCls, "relayoutWindow", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    handleLayoutParamsArgs(cl, param.args);
                }
            });
            XposedCompat.log(TAG + ": hooked WindowManagerService addWindow/relayoutWindow");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hook WindowManagerService failed: " + t);
        }

        try {
            Class<?> animCls = cl.loadClass("com.android.server.wm.WindowStateAnimator");
            XposedCompat.hookAllMethods(animCls, "prepareSurfaceLocked", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    handlePrepareSurface(cl, param.thisObject);
                }
            });
            XposedCompat.log(TAG + ": hooked WindowStateAnimator.prepareSurfaceLocked");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hook WindowStateAnimator.prepareSurfaceLocked failed: " + t);
        }

        try {
            Class<?> scsCls = cl.loadClass("com.android.server.wm.MiuiSizeCompatService");
            sScsContextField = findField(scsCls, "mContext");
            XposedCompat.hookAllMethods(scsCls, "getSkipBottomInsetsActivities", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    handleSkipBottomInsets(param);
                }
            });
            XposedCompat.log(TAG + ": hooked MiuiSizeCompatService.getSkipBottomInsetsActivities");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hook MiuiSizeCompatService.getSkipBottomInsetsActivities failed: " + t);
        }
    }

    /**
     * 运行在 com.miui.fliphome 进程中：
     * 外屏真全屏后去掉了底部 21px 留白与 32px 提示条，手指起手点容易高于原 97.5px (30dp) 热区。
     * 将 GestureOperationHelper.sBottomRegionHeight 适度放宽至 140px (~43dp)，对齐标准全面屏手势热区。
     */
    public static void installFlipHomeGestureHook(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> helperCls = cl.loadClass("com.miui.fliphome.gesture.GestureOperationHelper");
            final Field fHeight = helperCls.getDeclaredField("sBottomRegionHeight");
            final Field fRegion = helperCls.getDeclaredField("REGION_BOTTOM");
            fHeight.setAccessible(true);
            fRegion.setAccessible(true);

            XposedCompat.SimpleCallback regionAdjust = new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (!XposedCompat.getBool(ExtraPrefsUI.KEY_HIDE_OUTER_ANTIMISTAKE, true)) {
                        return;
                    }
                    try {
                        float targetH = 140.0f;
                        fHeight.setFloat(null, targetH);
                        Object rectObj = fRegion.get(null);
                        if (rectObj instanceof android.graphics.RectF) {
                            android.graphics.RectF r = (android.graphics.RectF) rectObj;
                            if (!r.isEmpty()) {
                                r.top = Math.max(0.0f, (r.bottom - 1.0f) - targetH);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedCompat.hookAllMethods(helperCls, "initRegion", regionAdjust);
            XposedCompat.hookAllMethods(helperCls, "updateRegion", regionAdjust);
            XposedCompat.log(TAG + ": hooked FlipHome GestureOperationHelper bottom region");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hook FlipHome GestureOperationHelper failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleSkipBottomInsets(XposedCompat.HookParam param) {
        if (!XposedCompat.getBool(ExtraPrefsUI.KEY_HIDE_OUTER_ANTIMISTAKE, true)) {
            return;
        }
        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
            return;
        }
        String pkgName = (String) param.args[0];
        if (pkgName.isEmpty()) {
            return;
        }
        Map<String, String> cached = sSkipInsetsCache.get(pkgName);
        if (cached != null) {
            param.setResult(cached);
            return;
        }
        try {
            Object scs = param.thisObject;
            Object ctxObj = (scs != null && sScsContextField != null) ? sScsContextField.get(scs) : null;
            if (!(ctxObj instanceof Context)) {
                return;
            }
            PackageManager pm = ((Context) ctxObj).getPackageManager();
            if (pm == null) {
                return;
            }
            PackageInfo pi = pm.getPackageInfo(pkgName,
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            Map<String, String> result = new HashMap<>();
            Object orig = param.getResult();
            if (orig instanceof Map) {
                result.putAll((Map<String, String>) orig);
            }
            if (pi != null && pi.activities != null) {
                for (ActivityInfo ai : pi.activities) {
                    if (ai != null && ai.name != null) {
                        result.put(ai.name, "1");
                    }
                }
            }
            Map<String, String> unmod = Collections.unmodifiableMap(result);
            sSkipInsetsCache.put(pkgName, unmod);
            param.setResult(unmod);
            if (!sLoggedSkipInsets) {
                sLoggedSkipInsets = true;
                XposedCompat.log(TAG + ": skip bottom insets for " + pkgName + " activities=" + unmod.size());
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": handleSkipBottomInsets failed for " + pkgName + ": " + t);
        }
    }

    private static void handleLayoutParamsArgs(ClassLoader cl, Object[] args) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) arg;
                if (isAntiMistakeAttrs(lp)
                        && isFlipFolded(cl)
                        && XposedCompat.getBool(ExtraPrefsUI.KEY_HIDE_OUTER_ANTIMISTAKE, true)) {
                    lp.alpha = 0.0f;
                    lp.width = 0;
                    lp.height = 0;
                }
                return;
            }
        }
    }

    private static void handlePrepareSurface(ClassLoader cl, Object animator) {
        if (animator == null) {
            return;
        }
        try {
            initAnimFieldsIfNeeded(animator);
            Object win = sAnimWinField != null ? sAnimWinField.get(animator) : null;
            if (!shouldHideWindow(cl, win)) {
                return;
            }
            if (sAnimAlphaField != null) {
                sAnimAlphaField.setFloat(animator, 0.0f);
            }
            if (sAnimShownAlphaField != null) {
                sAnimShownAlphaField.setFloat(animator, 0.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean shouldHideWindow(ClassLoader cl, Object win) {
        if (win == null) {
            return false;
        }
        try {
            initWinFieldsIfNeeded(win);
            Object attrsObj = sWinAttrsField != null ? sWinAttrsField.get(win) : null;
            if (!(attrsObj instanceof WindowManager.LayoutParams)) {
                return false;
            }
            if (!isAntiMistakeAttrs((WindowManager.LayoutParams) attrsObj)) {
                return false;
            }
            if (!isOuterWindowOrFolded(cl, win)) {
                return false;
            }
            return XposedCompat.getBool(ExtraPrefsUI.KEY_HIDE_OUTER_ANTIMISTAKE, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAntiMistakeAttrs(WindowManager.LayoutParams lp) {
        if (lp == null || lp.type != WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY) {
            return false;
        }
        CharSequence title = lp.getTitle();
        return title != null && TARGET_TITLE.equals(title.toString());
    }

    private static boolean isOuterWindowOrFolded(ClassLoader cl, Object win) {
        if (isFlipFolded(cl)) {
            return true;
        }
        try {
            if (sWinGetBoundsMethod != null) {
                Object res = sWinGetBoundsMethod.invoke(win);
                if (res instanceof Rect) {
                    Rect r = (Rect) res;
                    int w = r.width();
                    int h = r.height();
                    return (w == 1208 && h == 1392) || (w == 1392 && h == 1208);
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isFlipFolded(ClassLoader cl) {
        try {
            if (!sCompatInitDone) {
                sCompatInitDone = true;
                Class<?> stubCls = cl.loadClass("android.sizecompat.MiuiAppSizeCompatModeStub");
                sCompatGetMethod = stubCls.getMethod("get");
                Object stub = sCompatGetMethod.invoke(null);
                if (stub != null) {
                    sIsFlipFoldedMethod = stub.getClass().getMethod("isFlipFolded");
                }
            }
            if (sCompatGetMethod != null && sIsFlipFoldedMethod != null) {
                Object stub = sCompatGetMethod.invoke(null);
                Object res = stub != null ? sIsFlipFoldedMethod.invoke(stub) : null;
                return Boolean.TRUE.equals(res);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void initAnimFieldsIfNeeded(Object animator) {
        if (sAnimFieldsInitDone) {
            return;
        }
        sAnimFieldsInitDone = true;
        Class<?> animCls = animator.getClass();
        sAnimWinField = findField(animCls, "mWin");
        sAnimAlphaField = findField(animCls, "mAlpha");
        sAnimShownAlphaField = findField(animCls, "mShownAlpha");
    }

    private static void initWinFieldsIfNeeded(Object win) {
        if (sWinFieldsInitDone) {
            return;
        }
        sWinFieldsInitDone = true;
        Class<?> winCls = win.getClass();
        sWinAttrsField = findField(winCls, "mAttrs");
        try {
            sWinGetBoundsMethod = winCls.getMethod("getBounds");
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
