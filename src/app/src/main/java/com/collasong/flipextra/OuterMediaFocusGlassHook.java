package com.collasong.flipextra;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 外屏媒体播放控件（FlipMediaRowHolder）与外屏焦点通知（CustomViewFlipRowHolder）统一应用玻璃样式：
 * SystemUI 的 FlipRowAdapter.toggleBlend() 显式跳过了这两类卡片，导致它们在外屏仍停留在旧版混色/纯色背景。
 */
public final class OuterMediaFocusGlassHook {

    private static final String TAG = "FlipExtra/MediaFocusGlass";
    private static final String GLASS_EFFECT_CLS =
            "com.android.systemui.statusbar.notification.style.vieweffect.FlipRowGlassEffect";
    private static final String MEDIA_HOLDER_CLS =
            "com.android.notification.tinypanel.FlipMediaRowHolder";
    private static final String CUSTOM_HOLDER_CLS =
            "com.android.notification.tinypanel.CustomViewFlipRowHolder";

    private static Object sGlassEffectInstance;
    private static Method sGlassApplyMethod;

    private OuterMediaFocusGlassHook() {
    }

    public static void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> glassCls = cl.loadClass(GLASS_EFFECT_CLS);
            sGlassEffectInstance = glassCls.getField("INSTANCE").get(null);
            sGlassApplyMethod = glassCls.getMethod("apply", Context.class, View.class);
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": FlipRowGlassEffect not found: " + t);
            return;
        }

        hookMediaHolder(cl);
        hookCustomFocusHolder(cl);
    }

    private static void hookMediaHolder(ClassLoader cl) {
        try {
            Class<?> mediaCls = cl.loadClass(MEDIA_HOLDER_CLS);
            final Field fMediaBg = mediaCls.getDeclaredField("mMediaBg");
            final Field fItemView = mediaCls.getDeclaredField("mItemView");
            final Field fBlurOpen = mediaCls.getDeclaredField("mBlurOpen");
            fMediaBg.setAccessible(true);
            fItemView.setAccessible(true);
            fBlurOpen.setAccessible(true);

            XposedCompat.hookAllMethods(mediaCls, "updateResources$1", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (param.thisObject == null) {
                        return;
                    }
                    try {
                        boolean blurOpen = fBlurOpen.getBoolean(param.thisObject);
                        if (!blurOpen) {
                            return;
                        }
                        Object bgObj = fMediaBg.get(param.thisObject);
                        Object itemObj = fItemView.get(param.thisObject);
                        if (bgObj instanceof View && itemObj instanceof View) {
                            Context ctx = ((View) itemObj).getContext();
                            if (ctx != null) {
                                sGlassApplyMethod.invoke(sGlassEffectInstance, ctx, bgObj);
                            }
                        }
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + ": FlipMediaRowHolder glass failed: " + t);
                    }
                }
            });
            XposedCompat.log(TAG + ": hooked FlipMediaRowHolder.updateResources$1");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hookMediaHolder failed: " + t);
        }
    }

    private static void hookCustomFocusHolder(ClassLoader cl) {
        try {
            Class<?> customCls = cl.loadClass(CUSTOM_HOLDER_CLS);
            final Field fItemView = customCls.getField("itemView");
            final Field fContext = customCls.getDeclaredField("mContext");
            final Field fEntry = customCls.getDeclaredField("mEntry");
            fContext.setAccessible(true);
            fEntry.setAccessible(true);

            XposedCompat.hookAllMethods(customCls, "updateResources$1", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (param.thisObject == null) {
                        return;
                    }
                    try {
                        Object itemObj = fItemView.get(param.thisObject);
                        Object ctxObj = fContext.get(param.thisObject);
                        if (!(itemObj instanceof View) || !(ctxObj instanceof Context)) {
                            return;
                        }
                        Object entryObj = fEntry.get(param.thisObject);
                        if (hasFocusCustomBg(entryObj)) {
                            return;
                        }
                        sGlassApplyMethod.invoke(sGlassEffectInstance, ctxObj, itemObj);
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + ": CustomViewFlipRowHolder glass failed: " + t);
                    }
                }
            });
            XposedCompat.log(TAG + ": hooked CustomViewFlipRowHolder.updateResources$1");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hookCustomFocusHolder failed: " + t);
        }
    }

    private static boolean hasFocusCustomBg(Object entryObj) {
        if (entryObj == null) {
            return false;
        }
        try {
            Field fSbn = entryObj.getClass().getField("mSbn");
            Object sbnObj = fSbn.get(entryObj);
            if (sbnObj == null) {
                return false;
            }
            Field fIsFocus = sbnObj.getClass().getField("mIsFocusNotification");
            if (!fIsFocus.getBoolean(sbnObj)) {
                return false;
            }
            Method getNotif = sbnObj.getClass().getMethod("getNotification");
            Object notifObj = getNotif.invoke(sbnObj);
            if (notifObj == null) {
                return false;
            }
            Field fExtras = notifObj.getClass().getField("extras");
            Object extrasObj = fExtras.get(notifObj);
            if (extrasObj instanceof Bundle) {
                return ((Bundle) extrasObj).getBoolean("miui.focus.hasCustomBg", false);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
