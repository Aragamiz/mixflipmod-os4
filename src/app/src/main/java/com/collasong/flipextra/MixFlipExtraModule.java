package com.collasong.flipextra;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class MixFlipExtraModule extends XposedModule {

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        XposedCompat.init(this);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null || !param.isFirstPackage()) {
            return;
        }
        String pkg = param.getPackageName();
        ClassLoader cl = param.getClassLoader();
        if ("com.android.systemui".equals(pkg)) {
            StatusLockHook.install(cl);
            OuterNotificationHook.install(cl);
            ControlCenterOuterHook.install(cl);
            OuterMediaFocusGlassHook.install(cl);
            HyperViewScaleFixHook.installSystemUI(cl);
            OuterShellLetterboxFixHook.installSystemUI(cl);
        } else if ("com.miui.fliphome".equals(pkg)) {
            WidgetGlassHook.install(cl);
            OuterFullscreenHook.installFlipHomeGestureHook(cl);
        } else if ("com.tencent.wetype".equals(pkg)) {
            WeTypeOuterHook.install(cl);
        } else if ("com.android.contacts".equals(pkg)) {
            DialerOuterHook.install(cl);
        } else if ("com.miui.calculator".equals(pkg)) {
            CalculatorOuterHook.install(cl);
        } else if ("com.netease.cloudmusic".equals(pkg)) {
            NetEaseOuterHook.install(cl);
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        if (param == null) {
            return;
        }
        installWeTypeFlipCompatHook(param.getClassLoader());
        installOuterImeBootGuard(param.getClassLoader());
        OuterFullscreenHook.install(param.getClassLoader());
        OuterXiaoAiStandbyHook.install(param.getClassLoader());
    }

    /**
     * 修复合盖开机时外屏微信输入法无法拉起的两处系统链式 Bug：
     * 1) Direct Boot（解锁前）FoldStateListener 触发时，微信输入法未声明 directBootAware，
     *    InputMethodManagerServiceImpl.getSogouMethodIdLocked() 返回 null 且未判空，
     *    直接将 Settings.Secure.default_input_method 写为 null，导致解锁后被迫回退小米输入法；
     * 2) 解锁后外屏首次拉起输入法时，SogouInputMethodSwitcher.mayChangeInputMethodLocked()
     *    传入 mCurClient=null 调用 clearClientSessionLocked(null) 抛出 NPE，打断切换流程。
     */
    private static void installOuterImeBootGuard(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> secureCls = Class.forName("android.provider.Settings$Secure", false, cl);
            XposedCompat.hookAllMethods(secureCls, "putStringForUser", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.args == null || param.args.length < 3) {
                        return;
                    }
                    if (!"default_input_method".equals(param.args[1])) {
                        return;
                    }
                    Object val = param.args[2];
                    if (val == null || (val instanceof String && ((String) val).isEmpty())) {
                        XposedCompat.log("FlipExtra/ImeBootGuard: blocked writing empty/null default_input_method");
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            XposedCompat.log("FlipExtra/ImeBootGuard: hooked Settings.Secure.putStringForUser");
        } catch (Throwable t) {
            XposedCompat.log("FlipExtra/ImeBootGuard: hook Settings.Secure failed: " + t);
        }

        try {
            Class<?> immsCls = cl.loadClass("com.android.server.inputmethod.InputMethodManagerService");
            XposedCompat.hookAllMethods(immsCls, "clearClientSessionLocked", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.args != null && param.args.length >= 1 && param.args[0] == null) {
                        XposedCompat.log("FlipExtra/ImeBootGuard: prevented NPE in clearClientSessionLocked(null)");
                        param.setResult(null);
                    }
                }
            });
            XposedCompat.log("FlipExtra/ImeBootGuard: hooked IMMS.clearClientSessionLocked");
        } catch (Throwable t) {
            XposedCompat.log("FlipExtra/ImeBootGuard: hook clearClientSessionLocked failed: " + t);
        }
    }

    /**
     * 对齐搜狗输入法的 miui.supportFlipFullScreen = 0，让微信输入法在外屏不再被强制进入 1011x1740 比例兼容模式。
     */
    private static void installWeTypeFlipCompatHook(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        String[] targetClasses = {
                "com.android.server.wm.BoundsCompatUtils",
                "com.android.server.wm.WindowManagerServiceImpl"
        };
        for (String clsName : targetClasses) {
            try {
                Class<?> utilsCls = cl.loadClass(clsName);
                XposedCompat.hookAllMethods(utilsCls, "getFlipCompatModeByApp", new XposedCompat.SimpleCallback() {
                    @Override
                    public void before(XposedCompat.HookParam param) {
                        if (!XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_ROT180_FULL, true)) {
                            return;
                        }
                        if (param.args != null && param.args.length >= 2) {
                            Object pkgObj = param.args[1];
                            if ("com.tencent.wetype".equals(pkgObj)) {
                                param.setResult(0);
                            }
                        }
                    }
                });
                XposedCompat.log("FlipExtra/SysServer: hooked " + clsName + ".getFlipCompatModeByApp for wetype");
            } catch (Throwable t) {
                XposedCompat.log("FlipExtra/SysServer: hook " + clsName + " failed: " + t);
            }
        }
    }
}
