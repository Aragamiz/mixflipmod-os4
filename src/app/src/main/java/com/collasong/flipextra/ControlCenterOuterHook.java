package com.collasong.flipextra;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.util.List;

/**
 * P4-2 修复：接管 mixflipmod 在 OS4.0.4.0 下失效的 sysui_control_center（外屏控制中心增强）。
 * <p>
 * 失效根因：OS4.0.4.0 中 SystemUI 的 PluginInstance$PluginFactory 将字段 mComponentName
 * 更名为 componentName，导致原 mixflipmod 反射抛出 NoSuchFieldException 并跳过插件 Hook。
 * <p>
 * 修复方式：监听 PluginFactory.createPluginContext()，兼容读取 componentName / mComponentName，
 * 并在 miui.systemui.plugin 加载时执行完整的外屏控制中心展开与设备卡片适配。
 */
public final class ControlCenterOuterHook {

    private static final String TAG = "FlipExtra/CtrlCenter";
    private static final String PLUGIN_FACTORY_CLS =
            "com.android.systemui.shared.plugins.PluginInstance$PluginFactory";
    private static final String PLUGIN_PKG = "miui.systemui.plugin";
    private static final String CTRL_CENTER_CLS = "miui.systemui.controlcenter.MiuiControlCenter";
    private static final String QS_TILE_PLUGIN_CLS = "miui.systemui.quicksettings.LocalMiuiQSTilePlugin";

    private static volatile boolean sPluginHooked = false;
    private static volatile boolean sIsCompact = false;
    private static final ThreadLocal<Boolean> sInAvailable = new ThreadLocal<>();

    private ControlCenterOuterHook() {
    }

    public static void install(ClassLoader sysuiCl) {
        try {
            Class<?> factoryCls = sysuiCl.loadClass(PLUGIN_FACTORY_CLS);
            XposedCompat.hookAllMethods(factoryCls, "createPluginContext", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    if (sPluginHooked || !XposedCompat.getBool("sysui_control_center", false)) {
                        return;
                    }
                    ComponentName cn = readComponentName(param.thisObject);
                    if (cn == null || !PLUGIN_PKG.equals(cn.getPackageName())) {
                        return;
                    }
                    String clsName = cn.getClassName();
                    if (!CTRL_CENTER_CLS.equals(clsName) && !QS_TILE_PLUGIN_CLS.equals(clsName)) {
                        return;
                    }
                    Object res = param.getResult();
                    if (!(res instanceof ContextWrapper)) {
                        return;
                    }
                    ClassLoader pluginCl = ((ContextWrapper) res).getClassLoader();
                    if (pluginCl != null) {
                        hookPlugin(pluginCl);
                    }
                }
            });
            XposedCompat.log(TAG + ": armed PluginFactory.createPluginContext hook");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install skipped: " + t);
        }
    }

    private static ComponentName readComponentName(Object factory) {
        if (factory == null) {
            return null;
        }
        String[] names = {"componentName", "mComponentName"};
        for (String name : names) {
            try {
                Field f = factory.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(factory);
                if (v instanceof ComponentName) {
                    return (ComponentName) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static synchronized void hookPlugin(ClassLoader cl) {
        if (sPluginHooked) {
            return;
        }
        try {
            Class<?> styleEnum = cl.loadClass(
                    "miui.systemui.controlcenter.panel.main.MainPanelController$Style");
            final Object styleCompact = styleEnum.getField("COMPACT").get(null);
            final Object styleVertical = styleEnum.getField("VERTICAL").get(null);

            Class<?> styleCtrlCls = cl.loadClass(
                    "miui.systemui.controlcenter.panel.main.MainPanelStyleController");
            XposedCompat.hookAllMethods(styleCtrlCls, "set_style", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.args != null && param.args.length > 0) {
                        sIsCompact = (param.args[0] == styleCompact);
                    }
                }
            });
            XposedCompat.hookAllMethods(styleCtrlCls, "getStyle", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (sIsCompact && Boolean.TRUE.equals(sInAvailable.get())) {
                        param.setResult(styleVertical);
                    }
                }
            });

            String[] controllers = {
                    "miui.systemui.controlcenter.panel.main.qs.EditButtonController",
                    "miui.systemui.controlcenter.panel.main.qs.QSListController",
                    "miui.systemui.controlcenter.panel.main.qs.CompactQSListController",
                    "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController",
                    "miui.systemui.controlcenter.panel.main.devicecontrol.DeviceControlsEntryController"
            };
            XposedCompat.Callback availCb = new XposedCompat.Callback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    sInAvailable.set(Boolean.TRUE);
                }

                @Override
                public void after(XposedCompat.HookParam param) {
                    sInAvailable.remove();
                }
            };
            for (String ctrlName : controllers) {
                try {
                    Class<?> cls = cl.loadClass(ctrlName);
                    XposedCompat.hookAllMethods(cls, "available", availCb);
                } catch (Throwable ignored) {
                }
            }

            try {
                Class<?> tileCls = cl.loadClass("miui.systemui.controlcenter.qs.tileview.QSTileItemView");
                XposedCompat.hookAllMethods(tileCls, "onFinishInflate", new XposedCompat.SimpleCallback() {
                    @Override
                    public void after(XposedCompat.HookParam param) {
                        if (param.thisObject instanceof FrameLayout) {
                            FrameLayout fl = (FrameLayout) param.thisObject;
                            fl.setOnLongClickListener(v -> false);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                Class<?> dimenCls = cl.loadClass("miui.systemui.controlcenter.R$dimen");
                final int deviceItemWidthId = dimenCls.getField("device_center_device_item_width").getInt(null);
                XposedCompat.hookAllMethods(Resources.class, "getDimensionPixelSize",
                        new XposedCompat.SimpleCallback() {
                            @Override
                            public void before(XposedCompat.HookParam param) {
                                if (sIsCompact && param.args != null && param.args.length > 0
                                        && param.args[0] instanceof Integer
                                        && ((Integer) param.args[0]) == deviceItemWidthId) {
                                    param.setResult(236);
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }

            try {
                Class<?> adapterCls = cl.loadClass(
                        "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController$_adapter$1");
                XposedCompat.hookAllMethods(adapterCls, "onCreateViewHolder",
                        new XposedCompat.SimpleCallback() {
                            @Override
                            public void after(XposedCompat.HookParam param) {
                                if (!sIsCompact) {
                                    return;
                                }
                                Object vh = param.getResult();
                                if (vh == null) {
                                    return;
                                }
                                try {
                                    Field f = vh.getClass().getField("itemView");
                                    Object iv = f.get(vh);
                                    if (iv instanceof View) {
                                        int targetW = 236;
                                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof ViewGroup) {
                                            ViewGroup parent = (ViewGroup) param.args[0];
                                            int avail = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
                                            if (avail > 300) {
                                                targetW = Math.min(245, avail / 3);
                                            }
                                        }
                                        ViewGroup.LayoutParams lp = ((View) iv).getLayoutParams();
                                        if (lp != null && lp.width != -1) {
                                            lp.width = targetW;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }

            try {
                Class<?> modeCls = cl.loadClass(
                        "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder$Mode");
                final Object modeCollapsed = modeCls.getField("MODE_COLLAPSED").get(null);
                final Object mode1Row = modeCls.getField("MODE_1_ROW").get(null);
                final Object mode2Rows = modeCls.getField("MODE_2_ROWS").get(null);
                Class<?> cardCtrlCls = cl.loadClass(
                        "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController");
                XposedCompat.hookAllMethods(cardCtrlCls, "getMode", new XposedCompat.SimpleCallback() {
                    @Override
                    public void before(XposedCompat.HookParam param) {
                        if (!sIsCompact || param.thisObject == null) {
                            return;
                        }
                        try {
                            Field f = param.thisObject.getClass().getDeclaredField("deviceItems");
                            f.setAccessible(true);
                            Object items = f.get(param.thisObject);
                            if (items instanceof List) {
                                int sz = ((List<?>) items).size();
                                if (sz <= 1) {
                                    param.setResult(modeCollapsed);
                                } else if (sz <= 3) {
                                    param.setResult(mode1Row);
                                } else {
                                    param.setResult(mode2Rows);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                Class<?> dcCtrlCls = cl.loadClass("miui.systemui.devicecenter.DeviceCenterController");
                final ThreadLocal<List<?>> sSavedDeviceList = new ThreadLocal<>();
                XposedCompat.hookAllMethods(dcCtrlCls, "handleDeviceListUpdate",
                        new XposedCompat.Callback() {
                            @Override
                            public void before(XposedCompat.HookParam param) {
                                if (!sIsCompact || param.thisObject == null) {
                                    return;
                                }
                                try {
                                    Field f = param.thisObject.getClass().getDeclaredField("deviceList");
                                    f.setAccessible(true);
                                    Object listObj = f.get(param.thisObject);
                                    if (listObj instanceof List) {
                                        List<?> list = (List<?>) listObj;
                                        if (list.size() > 5) {
                                            sSavedDeviceList.set(list);
                                            f.set(param.thisObject, new java.util.ArrayList<>(list.subList(0, 5)));
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }

                            @Override
                            public void after(XposedCompat.HookParam param) {
                                List<?> orig = sSavedDeviceList.get();
                                if (orig != null) {
                                    sSavedDeviceList.remove();
                                    try {
                                        Field f = param.thisObject.getClass().getDeclaredField("deviceList");
                                        f.setAccessible(true);
                                        f.set(param.thisObject, orig);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }

            sPluginHooked = true;
            XposedCompat.log(TAG + ": miui.systemui.plugin control center hooks installed");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": hookPlugin failed: " + t);
        }
    }
}
