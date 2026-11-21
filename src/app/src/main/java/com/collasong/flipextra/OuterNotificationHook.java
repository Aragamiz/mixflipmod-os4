package com.collasong.flipextra;

import java.lang.reflect.Method;

/**
 * P4 修复：接管 mixflipmod 在 OS4.0.4.0 下失效的 sysui_notification（外屏通知滑动菜单优化）。
 * <p>
 * 失效根因：OS4.0.4.0 中 MiuiNotificationMenuRow.createMenuViews(boolean) 改调两参重载
 * MiuiConfigs.isTinyScreen(Context, boolean)，原 mixflipmod 仅查找单参 isTinyScreen(Context)
 * 抛出 NoSuchMethodException 导致整体跳过。
 * <p>
 * 修复方式：直接复用 mixflipmod 的原生开关键 "sysui_notification"，在 createMenuViews 执行期间
 * 将所有 isTinyScreen 重载方法返回 false，从而在外屏保留完整的通知操作菜单而非仅显示提示。
 */
public final class OuterNotificationHook {

    private static final String TAG = "FlipExtra/NotifyMenu";
    private static final ThreadLocal<Boolean> sInCreateMenu = new ThreadLocal<>();

    private OuterNotificationHook() {
    }

    public static void install(ClassLoader cl) {
        try {
            Class<?> menuRowCls = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow");
            Class<?> configsCls = cl.loadClass("com.miui.utils.configs.MiuiConfigs");

            XposedCompat.hookAllMethods(menuRowCls, "createMenuViews", new XposedCompat.Callback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (XposedCompat.getBool("sysui_notification", false)) {
                        sInCreateMenu.set(Boolean.TRUE);
                    }
                }

                @Override
                public void after(XposedCompat.HookParam param) {
                    sInCreateMenu.remove();
                }
            });

            int tinyHooks = 0;
            for (Method m : configsCls.getDeclaredMethods()) {
                if ("isTinyScreen".equals(m.getName()) && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    XposedCompat.hook(m, new XposedCompat.SimpleCallback() {
                        @Override
                        public void before(XposedCompat.HookParam param) {
                            if (Boolean.TRUE.equals(sInCreateMenu.get())) {
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    });
                    tinyHooks++;
                }
            }
            XposedCompat.log(TAG + ": hooked MiuiNotificationMenuRow.createMenuViews + isTinyScreen(" + tinyHooks + ")");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install skipped: " + t);
        }
    }
}
