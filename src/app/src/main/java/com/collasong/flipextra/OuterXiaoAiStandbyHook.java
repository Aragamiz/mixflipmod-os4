package com.collasong.flipextra;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 合盖外屏唤醒小爱直达 Standby 陪伴模式（运行在 system_server）：
 * HyperOS 4 超级小爱默认外屏唤醒不再进入 StandbyDialogActivity，
 * 本 Hook 在合盖状态下拦截 ShortCutActionsUtils.triggerFunction("launch_voice_assistant")，
 * 直接在外屏（displayId=0）拉起 com.miui.voiceassist/com.xiaomi.voiceassistant.multimodal.video.StandbyDialogActivity。
 */
public final class OuterXiaoAiStandbyHook {

    private static final String TAG = "FlipExtra/XiaoAiStandby";
    private static final String PKG_VOICE_ASSIST = "com.miui.voiceassist";
    private static final String CLS_STANDBY_ACTIVITY = "com.xiaomi.voiceassistant.multimodal.video.StandbyDialogActivity";

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static volatile Field sContextField;
    private static volatile Method sCompatGetMethod;
    private static volatile Method sIsFlipFoldedMethod;
    private static volatile boolean sInitDone = false;

    private OuterXiaoAiStandbyHook() {
    }

    public static void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> sauCls = cl.loadClass("com.miui.server.input.util.ShortCutActionsUtils");
            XposedCompat.hookAllMethods(sauCls, "triggerFunction", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (!XposedCompat.getBool(ExtraPrefsUI.KEY_OUTER_XIAOAI_STANDBY, true)) {
                        return;
                    }
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String func = (String) param.args[0];
                    if (!"launch_voice_assistant".equals(func)) {
                        return;
                    }
                    if (!isFlipFolded(cl)) {
                        return;
                    }
                    Context ctx = getContext(param.thisObject);
                    if (ctx == null) {
                        return;
                    }
                    param.setResult(Boolean.TRUE);
                    sMainHandler.post(() -> launchStandbyDialog(ctx));
                }
            });
            XposedCompat.log(TAG + ": hooked ShortCutActionsUtils.triggerFunction for folded XiaoAi Standby");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install failed: " + t);
        }
    }

    private static void launchStandbyDialog(Context ctx) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setComponent(new ComponentName(PKG_VOICE_ASSIST, CLS_STANDBY_ACTIVITY));
            it.setPackage(PKG_VOICE_ASSIST);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(0);
            ctx.startActivity(it, opts.toBundle());
            XposedCompat.log(TAG + ": launched StandbyDialogActivity on folded outer screen");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": launch StandbyDialogActivity failed: " + t);
        }
    }

    private static Context getContext(Object sauObj) {
        if (sauObj == null) {
            return null;
        }
        try {
            if (sContextField == null) {
                Field f = sauObj.getClass().getDeclaredField("mContext");
                f.setAccessible(true);
                sContextField = f;
            }
            return (Context) sContextField.get(sauObj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isFlipFolded(ClassLoader cl) {
        try {
            if (!sInitDone) {
                sInitDone = true;
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
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": isFlipFolded check failed: " + t);
        }
        return false;
    }
}
