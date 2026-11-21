package com.collasong.flipextra;

import android.app.ActivityThread;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.view.DisplayInfo;
import android.view.WindowInsets;

import java.util.HashSet;
import java.util.Set;

/**
 * 修正网易云音乐（com.netease.cloudmusic）在外屏倒置（摄像头朝上 rot==2）时，
 * 窗口顶部已避让 114px 状态栏后，App 内部仍叠加 status_bar_height（175px 逻辑像素 = 114px 物理像素）
 * 导致顶部出现双重 114px 留白、标题栏下移至 y=227 的问题。
 *
 * 关键细节：
 * 1. 不依赖 Display.getRotation()（会被小米 BoundsCompat 覆写为 0），改读 DisplayManagerGlobal 与 ActivityThread 配置；
 * 2. 返回 1px 而非 0px，绕过网易云 pj.l.d / kt.b 中 `== 0 ? 25dp : val` 的兜底回退；
 * 3. 仅拦截 getStableInsetTop()（供 StatusBarHolderView/kt.c 使用），不全局清空 DecorView 的 getInsets(statusBars)，
 *    确保内容区稳稳停在 y=114 状态栏正下方，不与状态栏文字重叠。
 */
public final class NetEaseOuterHook {

    private static final String TAG = "FlipExtra/NetEase";
    private static volatile boolean sLoggedApplied = false;
    private static final Set<Integer> sStatusBarIds = new HashSet<>();
    private static final ThreadLocal<Boolean> sInHook = new ThreadLocal<>();

    private NetEaseOuterHook() {
    }

    public static void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Resources sysRes = Resources.getSystem();
            String[] names = {
                    "status_bar_height",
                    "status_bar_height_portrait",
                    "status_bar_height_landscape",
                    "status_bar_height_default"
            };
            for (String n : names) {
                int id = sysRes.getIdentifier(n, "dimen", "android");
                if (id != 0) {
                    sStatusBarIds.add(id);
                }
            }
            if (sStatusBarIds.isEmpty()) {
                return;
            }

            XposedCompat.hookAllMethods(Resources.class, "getDimensionPixelSize", createDimenCb(false));
            XposedCompat.hookAllMethods(Resources.class, "getDimensionPixelOffset", createDimenCb(false));
            XposedCompat.hookAllMethods(Resources.class, "getDimension", createDimenCb(true));

            hookStableInsetTop();
            XposedCompat.log(TAG + ": hooked status_bar_height(1px) + getStableInsetTop for com.netease.cloudmusic");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install failed: " + t);
        }
    }

    private static void hookStableInsetTop() {
        try {
            XposedCompat.hookAllMethods(WindowInsets.class, "getStableInsetTop", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    Object res = param.getResult();
                    if (res instanceof Integer && (Integer) res > 0
                            && XposedCompat.getBool(ExtraPrefsUI.KEY_NETEASE_OUTER_TOP_FIX, true)
                            && isOuterCameraTop()) {
                        param.setResult(0);
                    }
                }
            });
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hookStableInsetTop failed: " + t);
        }
    }

    private static XposedCompat.SimpleCallback createDimenCb(final boolean isFloat) {
        return new XposedCompat.SimpleCallback() {
            @Override
            public void before(XposedCompat.HookParam param) {
                if (Boolean.TRUE.equals(sInHook.get())) {
                    return;
                }
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int id = (Integer) param.args[0];
                if (!sStatusBarIds.contains(id)) {
                    return;
                }
                if (!XposedCompat.getBool(ExtraPrefsUI.KEY_NETEASE_OUTER_TOP_FIX, true)) {
                    return;
                }
                sInHook.set(Boolean.TRUE);
                try {
                    if (isOuterCameraTop()) {
                        if (!sLoggedApplied) {
                            sLoggedApplied = true;
                            XposedCompat.log(TAG + ": clamped status_bar_height to 1px on outer rot=2");
                        }
                        param.setResult(isFloat ? Float.valueOf(1.0f) : Integer.valueOf(1));
                    }
                } finally {
                    sInHook.remove();
                }
            }
        };
    }

    private static boolean isOuterCameraTop() {
        // 1. 优先读 DisplayManagerGlobal（不走 Display.getRotation 的 BoundsCompat 覆写）
        try {
            DisplayInfo di = DisplayManagerGlobal.getInstance().getDisplayInfo(0);
            if (di != null) {
                int nw = di.getNaturalWidth();
                int nh = di.getNaturalHeight();
                int lw = di.logicalWidth;
                int lh = di.logicalHeight;
                boolean isOuter = (Math.min(nw, nh) == 1208 && Math.max(nw, nh) == 1392)
                        || (Math.min(lw, lh) == 1208 && Math.max(lw, lh) == 1392);
                if (isOuter) {
                    if (di.rotation == 2) {
                        return true;
                    }
                    // 若 DisplayInfo.rotation 仍被 BoundsCompat 改写为 0，则继续用 Configuration 兜底
                } else if (Math.min(nw, nh) > 0) {
                    // 明确在内屏（如 1224x2912），直接返回 false
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. 兜底读 ActivityThread / Resources.getSystem() 的真实 windowConfiguration
        try {
            ActivityThread at = ActivityThread.currentActivityThread();
            Configuration cfg = at != null ? at.getConfiguration() : null;
            if (cfg != null && cfg.windowConfiguration != null) {
                int rot = cfg.windowConfiguration.getDisplayRotation();
                Rect bounds = cfg.windowConfiguration.getBounds();
                if (rot == 2 && bounds != null && bounds.left >= 390) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
