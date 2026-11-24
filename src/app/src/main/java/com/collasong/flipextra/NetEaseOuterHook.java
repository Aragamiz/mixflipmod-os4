package com.collasong.flipextra;

import android.app.ActivityThread;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.view.DisplayInfo;
import android.widget.Scroller;

import java.lang.reflect.Field;

/**
 * 修正网易云音乐（com.netease.cloudmusic）在外屏下的顶部黑条、双重留白与底部导航栏被顶出屏幕问题。
 *
 * 根因：
 * 网易云使用 LAYOUT_FULLSCREEN（mContentInsets.top = 0）而状态栏可见（mVisibleInsets.top = 175px 逻辑 / 114px 物理），
 * 在外屏高度 2142px 下，主内容 ViewPager2 高度 1966px <= 2142 - 175 = 1967px（刚好小 1px），
 * 当顶部区域持有焦点时，ViewRootImpl.scrollToRectOrFocus() 误判「焦点被状态栏遮挡」，
 * 通过 Scroller 将整个窗口向下滚动 mScrollY = -175（mHardwareYOffset = -175）：
 *   - 顶部 0~114px 暴露未绘制的黑底；
 *   - 网易云自身状态栏留白被整体下推到 114~227px，形成双重留白；
 *   - 底部 5 个导航栏图标被顶出屏幕底边 114px。
 *
 * 修复：
 * 完全不篡改 status_bar_height 与 WindowInsets（保留网易云原生沉浸布局与状态栏避让，不重叠不闪烁），
 * 仅在 ViewRootImpl.scrollToRectOrFocus() 执行期间将 mVisibleInsets.top 临时对齐为 mContentInsets.top
 * 并在执行后恢复，同时禁止外屏出现负的 mScrollY（不影响输入法弹出时正向 mScrollY > 0 的正常顶起）。
 */
public final class NetEaseOuterHook {

    private static final String TAG = "FlipExtra/NetEase";
    private static volatile boolean sLoggedFixed = false;

    private static Field sAttachInfoField;
    private static Field sContentInsetsField;
    private static Field sVisibleInsetsField;
    private static Field sScrollYField;
    private static Field sScrollerField;

    private static final ThreadLocal<Integer> sSavedViTop = new ThreadLocal<Integer>();

    private NetEaseOuterHook() {
    }

    public static void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> vriClass = Class.forName("android.view.ViewRootImpl", false, cl);
            sAttachInfoField = findField(vriClass, "mAttachInfo");
            sScrollYField = findField(vriClass, "mScrollY");
            sScrollerField = findField(vriClass, "mScroller");

            XposedCompat.hookAllMethods(vriClass, "scrollToRectOrFocus", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    sSavedViTop.remove();
                    if (!XposedCompat.getBool(ExtraPrefsUI.KEY_NETEASE_OUTER_TOP_FIX, true)
                            || !isOuterScreen()) {
                        return;
                    }
                    try {
                        Object vri = param.thisObject;
                        Rect ci = getAttachRect(vri, true);
                        Rect vi = getAttachRect(vri, false);
                        if (ci != null && vi != null && vi.top > ci.top) {
                            sSavedViTop.set(vi.top);
                            vi.top = ci.top;
                            logFixedOnce();
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void after(XposedCompat.HookParam param) {
                    try {
                        Object vri = param.thisObject;
                        Integer saved = sSavedViTop.get();
                        if (saved != null) {
                            sSavedViTop.remove();
                            Rect vi = getAttachRect(vri, false);
                            if (vi != null) {
                                vi.top = saved;
                            }
                        }
                        if (XposedCompat.getBool(ExtraPrefsUI.KEY_NETEASE_OUTER_TOP_FIX, true)
                                && isOuterScreen()) {
                            clampNegativeScrollY(vri);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedCompat.hookAllMethods(vriClass, "setScrollY", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.args != null && param.args.length > 0
                            && param.args[0] instanceof Integer && (Integer) param.args[0] < 0
                            && XposedCompat.getBool(ExtraPrefsUI.KEY_NETEASE_OUTER_TOP_FIX, true)
                            && isOuterScreen()) {
                        logFixedOnce();
                        param.args[0] = 0;
                    }
                }
            });

            XposedCompat.log(TAG + ": hooked ViewRootImpl.scrollToRectOrFocus for com.netease.cloudmusic");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install failed: " + t);
        }
    }

    private static Rect getAttachRect(Object vri, boolean content) throws Throwable {
        if (vri == null || sAttachInfoField == null) {
            return null;
        }
        Object attachInfo = sAttachInfoField.get(vri);
        if (attachInfo == null) {
            return null;
        }
        Field f;
        if (content) {
            if (sContentInsetsField == null) {
                sContentInsetsField = findField(attachInfo.getClass(), "mContentInsets");
            }
            f = sContentInsetsField;
        } else {
            if (sVisibleInsetsField == null) {
                sVisibleInsetsField = findField(attachInfo.getClass(), "mVisibleInsets");
            }
            f = sVisibleInsetsField;
        }
        return f != null ? (Rect) f.get(attachInfo) : null;
    }

    private static void clampNegativeScrollY(Object vri) throws Throwable {
        if (vri == null || sScrollYField == null) {
            return;
        }
        int sy = sScrollYField.getInt(vri);
        if (sy < 0) {
            logFixedOnce();
            if (sScrollerField != null) {
                Object scroller = sScrollerField.get(vri);
                if (scroller instanceof Scroller) {
                    ((Scroller) scroller).abortAnimation();
                }
            }
            sScrollYField.setInt(vri, 0);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void logFixedOnce() {
        if (!sLoggedFixed) {
            sLoggedFixed = true;
            XposedCompat.log(TAG + ": blocked negative ViewRootImpl mScrollY on outer screen");
        }
    }

    private static boolean isOuterScreen() {
        try {
            DisplayInfo di = DisplayManagerGlobal.getInstance().getDisplayInfo(0);
            if (di != null) {
                int nw = di.getNaturalWidth();
                int nh = di.getNaturalHeight();
                int lw = di.logicalWidth;
                int lh = di.logicalHeight;
                boolean isOuter = (Math.min(nw, nh) == 1208 && Math.max(nw, nh) == 1392)
                        || (Math.min(lw, lh) == 1208 && Math.max(lw, lh) == 1392);
                boolean isInner = (Math.min(nw, nh) == 1224 && Math.max(nw, nh) == 2912);
                if (isOuter) {
                    return true;
                }
                if (isInner) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            ActivityThread at = ActivityThread.currentActivityThread();
            Configuration cfg = at != null ? at.getConfiguration() : null;
            if (cfg != null && cfg.windowConfiguration != null) {
                Rect bounds = cfg.windowConfiguration.getBounds();
                if (bounds != null && (bounds.left >= 390 || Math.min(bounds.width(), bounds.height()) <= 1300)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
