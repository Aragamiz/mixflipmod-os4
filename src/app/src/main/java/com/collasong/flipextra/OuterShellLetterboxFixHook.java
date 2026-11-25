package com.collasong.flipextra;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 修复 HyperOS 4 (Android 17) 外屏共性问题：
 * 在外屏（1208x1392）已由 system_server 原厂 Letterbox - NBC 管理侧边补色的情况下，
 * com.android.systemui (WMShell) 的 MiuiShellLetterboxManagerImpl.onTaskInfoChanged()
 * 未过滤折叠外屏，导致任意 App 打开半透明子页/评论弹窗/输入法（如小红书 NoteCommentActivity 等）时：
 * 1) 误触发 reason=solidBlackTaskInfo 回退逻辑，在原厂白色 Letterbox - NBC 上方叠加一层纯黑 ShellLetterboxSurface，
 *    使侧边摄像头区域异常变黑且收起输入法后永久卡黑；
 * 2) 下次转场 OPEN 瞬间 LetterboxLifecycleControllerImpl 先隐藏黑层 ~89ms（露出底下白色 Letterbox - NBC），
 *    随后 onTaskInfoChanged 又重新拉起黑层，配合小部件毛玻璃 1 帧渲染延迟，肉眼表现为小部件猛闪一次纯白屏。
 *
 * 修复方案：
 * 在 com.android.systemui（WMShell）侧拦截 MiuiShellLetterboxManagerImpl.onTaskInfoChanged()
 * 与 notifyLetterboxChanged()，当任务边界为外屏 1208x1392 时直接跳过（内屏 1224x2912 不受任何影响），
 * 彻底交回原厂 Letterbox - NBC 统一管理，全机所有应用自动生效且无需勾选额外作用域。
 */
public final class OuterShellLetterboxFixHook {

    private static final String TAG = "FlipExtra/OuterShellLetterboxFix";
    private static final String MGR_CLASS = "com.android.wm.shell.compatui.letterbox.MiuiShellLetterboxManagerImpl";
    private static volatile boolean sLoggedBlocked = false;

    private OuterShellLetterboxFixHook() {
    }

    public static void installSystemUI(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> mgrCls = cl.loadClass(MGR_CLASS);
            XposedCompat.hookAllMethods(mgrCls, "onTaskInfoChanged", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (!XposedCompat.getBool(ExtraPrefsUI.KEY_OUTER_SHELL_LETTERBOX_FIX, true)) {
                        return;
                    }
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof ActivityManager.RunningTaskInfo)) {
                        return;
                    }
                    ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) param.args[0];
                    Rect bounds = getTaskBounds(info);
                    if (isFlipOuterBounds(bounds)) {
                        logBlockedOnce(info.taskId, bounds);
                        param.setResult(null);
                    }
                }
            });

            XposedCompat.hookAllMethods(mgrCls, "notifyLetterboxChanged", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (!XposedCompat.getBool(ExtraPrefsUI.KEY_OUTER_SHELL_LETTERBOX_FIX, true)) {
                        return;
                    }
                    if (param.args != null && param.args.length >= 3 && param.args[2] instanceof Rect) {
                        Rect bounds = (Rect) param.args[2];
                        if (isFlipOuterBounds(bounds)) {
                            int taskId = (param.args[0] instanceof Integer) ? (Integer) param.args[0] : -1;
                            logBlockedOnce(taskId, bounds);
                            param.setResult(null);
                        }
                    }
                }
            });

            XposedCompat.log(TAG + ": hooked " + MGR_CLASS + " for Flip outer screen");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install skipped: " + t);
        }
    }

    private static Rect getTaskBounds(ActivityManager.RunningTaskInfo info) {
        if (info == null) {
            return null;
        }
        try {
            Configuration cfg = info.configuration;
            if (cfg == null) {
                Method m = info.getClass().getMethod("getConfiguration");
                cfg = (Configuration) m.invoke(info);
            }
            if (cfg != null) {
                Field wcField = Configuration.class.getField("windowConfiguration");
                Object wc = wcField.get(cfg);
                if (wc != null) {
                    Method getBounds = wc.getClass().getMethod("getBounds");
                    Object r = getBounds.invoke(wc);
                    if (r instanceof Rect) {
                        return (Rect) r;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isFlipOuterBounds(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) {
            return false;
        }
        int w = bounds.width();
        int h = bounds.height();
        int minSide = Math.min(w, h);
        int maxSide = Math.max(w, h);
        return minSide == 1208 && maxSide == 1392;
    }

    private static void logBlockedOnce(int taskId, Rect bounds) {
        if (!sLoggedBlocked) {
            sLoggedBlocked = true;
            XposedCompat.log(TAG + ": blocked rogue ShellLetterboxSurface on outer screen (taskId="
                    + taskId + ", bounds=" + bounds + ")");
        }
    }
}
