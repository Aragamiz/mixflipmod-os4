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
        OuterFullscreenHook.install(param.getClassLoader());
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
