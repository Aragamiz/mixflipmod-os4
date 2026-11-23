package com.collasong.flipextra;

import android.graphics.Rect;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 修复 HyperOS 4 系统级「哪里点开哪里返回」动画（HyperViewScaleRemoteTransition）在
 * MIX Flip 2 外屏倒置（摄像头朝上 ROTATION_180，appBounds.left=399）时的坐标换算 Bug。
 *
 * 运行在 com.android.systemui（WMShell）进程中，在通过 Binder 向任意第三方应用发送
 * debugName="view_scale_remote_transition" 的 TransitionInfo 前，对 MiuiChange.mAppBounds
 * 进行数学预补偿（发送完毕后立即还原 SystemUI 侧原值），从而：
 * 1) 无需用户勾选任何第三方应用作用域，全机小红书、B 站、微博等自动生效；
 * 2) 零影响其他系统转场，且 ROTATION_0（appBounds.left==0 && top==0）完全不介入。
 *
 * 数学推导（对应 HyperViewScaleRemoteTransition.adjustForSizeCompat 原厂公式）：
 * - 原厂对 animChange 计算:
 *     contentBounds.left = endBounds.left + round(appBounds.left * compatScale)
 *   期望得到 endBounds.left + origLeft -> 预补偿 animChange.appBounds.left = round(origLeft / compatScale)
 *   （宽高保持不变，contentWidth = round(width * compatScale) 精准对齐 809px）
 * - 原厂对 otherChange 计算:
 *     newViewRect.left = round(origViewLeft * viewScale) + round(viewAppBounds.left * viewScale) + endBounds.left
 *   期望得到 round((origViewLeft - origLeft) * viewScale) + origLeft + endBounds.left
 *           = round(origViewLeft * viewScale) + origLeft * (1 - viewScale) + endBounds.left
 *   -> 预补偿 otherChange.appBounds.left = round(origLeft * (1 - viewScale) / viewScale)
 */
public final class HyperViewScaleFixHook {

    private static final String TAG = "FlipExtra/HyperViewScaleFix";
    private static final String TARGET_DEBUG_NAME = "view_scale_remote_transition";

    private static final WeakHashMap<IBinder, Boolean> sViewScaleBinders = new WeakHashMap<>();
    private static final ThreadLocal<List<SavedBounds>> sSavedBounds = new ThreadLocal<>();

    private static final class SavedBounds {
        final Object miuiChange;
        final Rect origRect;

        SavedBounds(Object miuiChange, Rect origRect) {
            this.miuiChange = miuiChange;
            this.origRect = origRect;
        }
    }

    private HyperViewScaleFixHook() {}

    public static void installSystemUI(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> remoteTransitionCls = cl.loadClass("android.window.RemoteTransition");
            final Method mGetDebugName = remoteTransitionCls.getMethod("getDebugName");

            XposedCompat.hookAllMethods(remoteTransitionCls, "getRemoteTransition", new XposedCompat.SimpleCallback() {
                @Override
                public void after(XposedCompat.HookParam param) {
                    Object res = param.getResult();
                    if (!(res instanceof IInterface) || param.thisObject == null) {
                        return;
                    }
                    try {
                        Object name = mGetDebugName.invoke(param.thisObject);
                        if (TARGET_DEBUG_NAME.equals(name)) {
                            IBinder binder = ((IInterface) res).asBinder();
                            if (binder != null) {
                                synchronized (sViewScaleBinders) {
                                    sViewScaleBinders.put(binder, Boolean.TRUE);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            Class<?> proxyCls = cl.loadClass("android.window.IRemoteTransition$Stub$Proxy");
            Class<?> transitionInfoCls = cl.loadClass("android.window.TransitionInfo");
            Class<?> changeCls = cl.loadClass("android.window.TransitionInfo$Change");
            final Method mGetType = transitionInfoCls.getMethod("getType");
            final Method mGetChanges = transitionInfoCls.getMethod("getChanges");
            final Method mGetMode = changeCls.getMethod("getMode");
            final Method mGetMiuiChange = changeCls.getMethod("getMiuiChange");

            XposedCompat.hookAllMethods(proxyCls, "startAnimation", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (!XposedCompat.getBool(ExtraPrefsUI.KEY_HYPER_VIEW_SCALE_FIX, true)) {
                        return;
                    }
                    if (!(param.thisObject instanceof IInterface) || param.args == null || param.args.length < 2) {
                        return;
                    }
                    IBinder binder = ((IInterface) param.thisObject).asBinder();
                    if (binder == null) {
                        return;
                    }
                    boolean isTarget;
                    synchronized (sViewScaleBinders) {
                        isTarget = sViewScaleBinders.containsKey(binder);
                    }
                    if (!isTarget) {
                        return;
                    }
                    Object info = param.args[1];
                    if (info == null) {
                        return;
                    }
                    try {
                        int type = (Integer) mGetType.invoke(info);
                        boolean openScene = (type == 1);
                        @SuppressWarnings("unchecked")
                        List<Object> changes = (List<Object>) mGetChanges.invoke(info);
                        if (changes == null || changes.isEmpty()) {
                            return;
                        }

                        // Pass 1: 找出 animChange 的 compatScale（与 parseTransitionChanges 保持一致）
                        float animCompatScale = 1.0f;
                        for (int i = changes.size() - 1; i >= 0; i--) {
                            Object change = changes.get(i);
                            if (change == null) continue;
                            int mode = (Integer) mGetMode.invoke(change);
                            boolean isAnim = openScene ? (mode == 1 || mode == 3) : (mode == 2 || mode == 4);
                            if (isAnim) {
                                Object mc = mGetMiuiChange.invoke(change);
                                if (mc != null) {
                                    float cs = (Float) mc.getClass().getMethod("getCompatScale").invoke(mc);
                                    animCompatScale = cs;
                                }
                            }
                        }

                        List<SavedBounds> saved = null;
                        StringBuilder logBuf = null;

                        // Pass 2: 分别对 animChange 与 otherChange 的 appBounds 进行预补偿
                        for (int i = changes.size() - 1; i >= 0; i--) {
                            Object change = changes.get(i);
                            if (change == null) continue;
                            Object mc = mGetMiuiChange.invoke(change);
                            if (mc == null) continue;
                            Method mGetCompatScale = mc.getClass().getMethod("getCompatScale");
                            Method mGetAppBounds = mc.getClass().getMethod("getAppBounds");
                            Method mSetAppBounds = mc.getClass().getMethod("setAppBounds", Rect.class);

                            float cs = (Float) mGetCompatScale.invoke(mc);
                            Rect ab = (Rect) mGetAppBounds.invoke(mc);
                            if (ab == null || ab.isEmpty() || (ab.left == 0 && ab.top == 0)) {
                                continue;
                            }

                            int mode = (Integer) mGetMode.invoke(change);
                            boolean isAnim = openScene ? (mode == 1 || mode == 3) : (mode == 2 || mode == 4);

                            Rect fixed = null;
                            if (isAnim) {
                                if (cs > 0f && cs < 1f) {
                                    int newLeft = Math.round(ab.left / cs);
                                    int newTop = Math.round(ab.top / cs);
                                    fixed = new Rect(newLeft, newTop, newLeft + ab.width(), newTop + ab.height());
                                }
                            } else {
                                float vScale = (cs > 0f && cs < 1f) ? cs : animCompatScale;
                                if (vScale > 0f && vScale < 1f) {
                                    int newLeft = Math.round(ab.left * (1.0f - vScale) / vScale);
                                    int newTop = Math.round(ab.top * (1.0f - vScale) / vScale);
                                    fixed = new Rect(newLeft, newTop, newLeft + ab.width(), newTop + ab.height());
                                }
                            }

                            if (fixed != null) {
                                if (saved == null) {
                                    saved = new ArrayList<>(2);
                                    logBuf = new StringBuilder();
                                }
                                saved.add(new SavedBounds(mc, new Rect(ab)));
                                mSetAppBounds.invoke(mc, fixed);
                                logBuf.append(isAnim ? " anim:" : " other:")
                                      .append(ab).append("->").append(fixed)
                                      .append("(s=").append(cs).append(")");
                            }
                        }

                        if (saved != null) {
                            sSavedBounds.set(saved);
                            XposedCompat.log(TAG + ": compensated type=" + type + logBuf);
                        }
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + ": before startAnimation error: " + t);
                    }
                }

                @Override
                public void after(XposedCompat.HookParam param) {
                    List<SavedBounds> saved = sSavedBounds.get();
                    if (saved == null) {
                        return;
                    }
                    sSavedBounds.remove();
                    for (SavedBounds sb : saved) {
                        try {
                            sb.miuiChange.getClass().getMethod("setAppBounds", Rect.class)
                                    .invoke(sb.miuiChange, sb.origRect);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });

            XposedCompat.log(TAG + ": installed WMShell RemoteTransition pre-compensation hook in SystemUI");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": installSystemUI failed: " + t);
        }
    }
}
