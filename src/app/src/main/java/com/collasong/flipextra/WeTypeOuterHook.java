package com.collasong.flipextra;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * P10: 微信输入法（com.tencent.wetype）外屏适配，兼容 4.0.1+（动态定位 imeProxy 与 NormalImeProxy）。
 * 摄像头朝下（rot==0）避让右侧相机切角（safeW=810）；摄像头朝上（rot==2）充分利用底部全宽（1208）。
 */
public final class WeTypeOuterHook {

    private static final String TAG = "FlipExtra/WeType";
    private static final String SERVICE_CLASS = "com.tencent.wetype.plugin.hld.WxHldService";
    private static final String NORMAL_PROXY_CLASS = "com.tencent.wetype.plugin.hld.k";
    private static final String FLOAT_MGR_CLASS = "com.tencent.wetype.plugin.hld.floatview.w";
    private static final String FLOAT_VIEW_CLASS = "com.tencent.wetype.plugin.hld.floatview.u";

    private static volatile boolean sInstalled = false;
    private static Field sProxyField;
    private static Method sSetupAreaMethod;
    private static volatile boolean sPendingSetup = false;
    private static volatile boolean sInSetup = false;
    private static WeakReference<OuterFitRoot> sRootRef = new WeakReference<>(null);
    private static final int[] sKbLoc = new int[2];
    private static volatile boolean sKbLocValid = false;
    private static volatile float sCurrentScale = 1.0f;
    private static volatile boolean sCurrentOuterMode = false;
    private static volatile int sLastKnownOuterRot = -1;
    private static volatile boolean sRot2FullMode = false;
    private static volatile float sRot2KbScaleX = 1.0f;
    private static volatile float sRot2KbScaleY = 1.04f;
    private static final int ROT2_TARGET_KB_TOP = 744;    // QWE top at y≈758 (30px below cutout y=728)
    private static final int ROT2_TARGET_KB_BOTTOM = 1392; // flush to bottom edge (0px gray margin)
    private static final float ROT2_CAND_SCALE_X = 810.0f / 1208.0f;
    private static final float ROT2_CAND_SCALE_Y = 0.85f;
    private static final int ROT2_CUTOUT_W = 398;

    public static void install(ClassLoader cl) {
        try {
            XposedCompat.hookMethod(Application.class, "attach", new Class<?>[]{Context.class},
                    new XposedCompat.SimpleCallback() {
                        @Override
                        public void after(XposedCompat.HookParam param) {
                            Context ctx = (Context) param.args[0];
                            if (ctx != null) {
                                installWithContext(ctx);
                            }
                        }
                    });
            XposedCompat.log(TAG + ": Application.attach hook armed");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": arm failed: " + t);
        }
    }

    private static synchronized void installWithContext(Context ctx) {
        if (sInstalled) {
            return;
        }
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> serviceCls = cl.loadClass(SERVICE_CLASS);
            if (!InputMethodService.class.isAssignableFrom(serviceCls)) {
                XposedCompat.log(TAG + ": WxHldService is not InputMethodService");
                return;
            }
            Field proxyField = findProxyField(serviceCls);
            if (proxyField == null) {
                XposedCompat.log(TAG + ": imeProxy field not found on WxHldService");
                return;
            }
            proxyField.setAccessible(true);

            Class<?> normalProxyCls = cl.loadClass(NORMAL_PROXY_CLASS);
            Method setupMethod = findSetupMethod(normalProxyCls);
            if (setupMethod == null) {
                XposedCompat.log(TAG + ": setupInputViewFullScreenArea method not found on " + NORMAL_PROXY_CLASS);
                return;
            }
            setupMethod.setAccessible(true);

            sProxyField = proxyField;
            sSetupAreaMethod = setupMethod;

            // 1. Hook NormalImeProxy.e(View, int, int) -> swap inner view to OuterFitRoot when wrapped
            XposedCompat.hook(setupMethod, new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (sInSetup) {
                        return;
                    }
                    Object arg0 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (!(arg0 instanceof View)) {
                        return;
                    }
                    View v = (View) arg0;
                    OuterFitRoot root = unwrapRootIfDirectChild(v);
                    if (root != null && root == sRootRef.get() && isAttachedToInputArea(root)) {
                        InputMethodService ims = root.imsRef.get();
                        if (isNormalProxy(ims)) {
                            param.args[0] = root;
                        }
                    }
                }
            });

            // 2. Hook InputMethodService.setInputView(View) -> wrap WxHldService input view
            XposedCompat.hookMethod(InputMethodService.class, "setInputView", new Class<?>[]{View.class},
                    new XposedCompat.SimpleCallback() {
                        @Override
                        public void before(XposedCompat.HookParam param) {
                            if (!(param.thisObject instanceof InputMethodService)) {
                                return;
                            }
                            InputMethodService ims = (InputMethodService) param.thisObject;
                            if (!SERVICE_CLASS.equals(ims.getClass().getName()) || !isNormalProxy(ims)) {
                                return;
                            }
                            View view = (View) param.args[0];
                            if (view == null || (view instanceof OuterFitRoot)) {
                                return;
                            }
                            if (view.getParent() instanceof InnerScaleLayout) {
                                param.args[0] = ((InnerScaleLayout) view.getParent()).root;
                                return;
                            }
                            ViewParent parent = view.getParent();
                            if (parent instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) parent;
                                if (vg.getId() == android.R.id.inputArea) {
                                    vg.removeView(view);
                                } else {
                                    return;
                                }
                            }
                            OuterFitRoot root = new OuterFitRoot(ims, view);
                            param.args[0] = root;
                            sRootRef = new WeakReference<>(root);
                            scheduleSetup(root);
                            XposedCompat.log(TAG + ": wrapped WxHldService input root");
                        }
                    });

            // 3. Hook lifecycle methods on InputMethodService (filtered for WxHldService) to refresh outer geometry
            XposedCompat.SimpleCallback refreshHook = new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.thisObject instanceof InputMethodService
                            && SERVICE_CLASS.equals(param.thisObject.getClass().getName())) {
                        applySogouCutoutAndFullscreen((InputMethodService) param.thisObject);
                        refreshCurrent();
                    }
                }

                @Override
                public void after(XposedCompat.HookParam param) {
                    if (param.thisObject instanceof InputMethodService
                            && SERVICE_CLASS.equals(param.thisObject.getClass().getName())) {
                        applySogouCutoutAndFullscreen((InputMethodService) param.thisObject);
                        refreshCurrent();
                        OuterFitRoot root = sRootRef.get();
                        if (root != null) {
                            scheduleSetup(root);
                        }
                    }
                }
            };
            XposedCompat.hookMethod(InputMethodService.class, "onConfigurationChanged", new Class<?>[]{Configuration.class}, refreshHook);
            XposedCompat.hookMethod(InputMethodService.class, "onStartInputView", new Class<?>[]{EditorInfo.class, boolean.class}, refreshHook);
            XposedCompat.hookMethod(InputMethodService.class, "onWindowShown", new Class<?>[0], refreshHook);
            XposedCompat.hookMethod(InputMethodService.class, "onComputeInsets",
                    new Class<?>[]{InputMethodService.Insets.class}, new XposedCompat.SimpleCallback() {
                        @Override
                        public void after(XposedCompat.HookParam param) {
                            if (param.thisObject instanceof InputMethodService
                                    && SERVICE_CLASS.equals(param.thisObject.getClass().getName())
                                    && param.args != null && param.args.length > 0
                                    && param.args[0] instanceof InputMethodService.Insets) {
                                adjustRot2ComputeInsets((InputMethodService) param.thisObject,
                                        (InputMethodService.Insets) param.args[0]);
                            }
                        }
                    });
            XposedCompat.hookMethod(InputMethodService.class, "onDestroy", new Class<?>[0], new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.thisObject != null && SERVICE_CLASS.equals(param.thisObject.getClass().getName())) {
                        sRootRef.clear();
                    }
                }
            });

            sInstalled = true;
            installWindowCutoutHooks();
            installFloatViewHooks(cl);
            XposedCompat.log(TAG + ": hooks installed (proxyField=" + proxyField.getName()
                    + ", setupMethod=" + setupMethod.getName() + ")");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install failed: " + t);
        }
    }

    @SuppressWarnings("deprecation")
    private static void installWindowCutoutHooks() {
        try {
            Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
            XposedCompat.SimpleCallback wmHook = new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    if (!(param.args[1] instanceof WindowManager.LayoutParams)) {
                        return;
                    }
                    WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) param.args[1];
                    if (wlp.type != WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
                        return;
                    }
                    boolean fit = XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_OUTER_FIT, true);
                    boolean rotFull = XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_ROT180_FULL, true);
                    if (!fit) {
                        return;
                    }
                    View v = (param.args[0] instanceof View) ? (View) param.args[0] : null;
                    int rot = getAccurateRotation(null, v, v != null ? v.getContext() : null,
                            v != null ? v.getDisplay() : null);
                    int targetMode = (rotFull && rot == 2)
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                    if (wlp.layoutInDisplayCutoutMode != targetMode) {
                        XposedCompat.log(TAG + ": WMG IME cutout " + targetMode + " (was " + wlp.layoutInDisplayCutoutMode + ", rot=" + rot + ")");
                        wlp.layoutInDisplayCutoutMode = targetMode;
                    }
                    if (v != null) {
                        int vis = v.getSystemUiVisibility();
                        int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
                        if (targetMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                            if ((vis & flags) != flags) {
                                v.setSystemUiVisibility(vis | flags);
                            }
                        } else if ((vis & flags) != 0) {
                            v.setSystemUiVisibility(vis & ~flags);
                        }
                    }
                }
            };
            XposedCompat.hookAllMethods(wmgCls, "addView", wmHook);
            XposedCompat.hookAllMethods(wmgCls, "updateViewLayout", wmHook);
            XposedCompat.log(TAG + ": WindowManagerGlobal cutout hooks installed");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": WindowManagerGlobal hooks failed: " + t);
        }
    }

    /**
     * 修正外屏缩放后按键气泡/弹窗（floatview.u）挂在未缩放 DecorView 导致向右下歪斜的问题：
     * 1) 在 floatview.w.a0 创建浮层前抓取 keyboard.getLocationInWindow(sKbLoc)；
     * 2) 在 floatview.u.v 定位前将未缩放的 drawRect 偏移按 sCurrentScale 缩放；
     * 3) 若父容器不在 OuterFitRoot 内（即直挂 DecorView），同步按底边中点缩放浮层自身。
     */
    private static void installFloatViewHooks(ClassLoader cl) {
        try {
            Class<?> mgrCls = cl.loadClass(FLOAT_MGR_CLASS);
            Class<?> floatViewCls = cl.loadClass(FLOAT_VIEW_CLASS);

            XposedCompat.hookAllMethods(mgrCls, "a0", new XposedCompat.SimpleCallback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    sKbLocValid = false;
                    if (!sCurrentOuterMode || sCurrentScale >= 0.999f || param.args == null) {
                        return;
                    }
                    for (Object arg : param.args) {
                        if (arg instanceof View && arg.getClass().getName().contains("keyboard")) {
                            ((View) arg).getLocationInWindow(sKbLoc);
                            sKbLocValid = true;
                            return;
                        }
                    }
                }
            });

            XposedCompat.hookAllMethods(floatViewCls, "v", new XposedCompat.Callback() {
                @Override
                public void before(XposedCompat.HookParam param) {
                    if (!sCurrentOuterMode || sCurrentScale >= 0.999f || !sKbLocValid) {
                        return;
                    }
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Rect) {
                        Rect r = (Rect) param.args[0];
                        r.left = Math.round(sKbLoc[0] + (r.left - sKbLoc[0]) * sCurrentScale);
                        r.top = Math.round(sKbLoc[1] + (r.top - sKbLoc[1]) * sCurrentScale);
                        r.right = Math.round(sKbLoc[0] + (r.right - sKbLoc[0]) * sCurrentScale);
                        r.bottom = Math.round(sKbLoc[1] + (r.bottom - sKbLoc[1]) * sCurrentScale);
                    }
                }

                @Override
                public void after(XposedCompat.HookParam param) {
                    sKbLocValid = false;
                    if (!(param.thisObject instanceof View)) {
                        return;
                    }
                    View fv = (View) param.thisObject;
                    if (!sCurrentOuterMode || sCurrentScale >= 0.999f) {
                        fv.setScaleX(1.0f);
                        fv.setScaleY(1.0f);
                        return;
                    }
                    ViewGroup parent = (param.args != null && param.args.length > 1 && param.args[1] instanceof ViewGroup)
                            ? (ViewGroup) param.args[1] : null;
                    if (parent != null && isInsideOuterFitRoot(parent)) {
                        fv.setScaleX(1.0f);
                        fv.setScaleY(1.0f);
                        return;
                    }
                    final int anchorCx = (param.args != null && param.args.length > 0 && param.args[0] instanceof Rect)
                            ? ((Rect) param.args[0]).centerX() : -1;
                    final int anchorBottom = (param.args != null && param.args.length > 0 && param.args[0] instanceof Rect)
                            ? ((Rect) param.args[0]).bottom : -1;
                    fv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(View v, int l, int t, int r, int b,
                                                   int ol, int ot, int or, int ob) {
                            v.removeOnLayoutChangeListener(this);
                            float px = (anchorCx >= 0) ? (float) (anchorCx - l) : (r - l) * 0.5f;
                            float py = (anchorBottom >= 0) ? (float) (anchorBottom - t) : (float) (b - t);
                            v.setPivotX(px);
                            v.setPivotY(py);
                        }
                    });
                    fv.setScaleX(sCurrentScale);
                    fv.setScaleY(sCurrentScale);
                }
            });
            XposedCompat.log(TAG + ": floatview skew fix hooks installed");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": floatview hooks skipped: " + t);
        }
    }

    /**
     * 对齐小米定制版搜狗输入法 com.sogou.inputmethod.oem.xiaomi.util.i (RotationManager)：
     * 优先通过 rootWindowInsets.getDisplayCutout() 的 safeInsetLeft / safeInsetRight 判定外屏正置(0)与倒置(2)，
     * 再回退至 Configuration.windowConfiguration.getRotation() 与 Display.getRotation()。
     */
    private static int getAccurateRotation(InputMethodService ims, View view, Context ctx, Display d) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && view != null) {
                try {
                    android.view.WindowInsets insets = view.getRootWindowInsets();
                    android.view.DisplayCutout cutout = insets != null ? insets.getDisplayCutout() : null;
                    if (cutout != null) {
                        if (cutout.getSafeInsetLeft() > 0) {
                            sLastKnownOuterRot = 2;
                            return 2; // isFlipPort: 倒置持握，镜头在左侧 (0..398)
                        }
                        if (cutout.getSafeInsetRight() > 0) {
                            sLastKnownOuterRot = 0;
                            return 0; // isFlipInvertPort: 正置持握，镜头在右侧 (810..1208)
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        Configuration cfg = null;
        try {
            if (ims != null && ims.getResources() != null) {
                cfg = ims.getResources().getConfiguration();
            } else if (ctx != null && ctx.getResources() != null) {
                cfg = ctx.getResources().getConfiguration();
            }
        } catch (Throwable ignored) {
        }
        if (cfg != null) {
            try {
                Field wcField = Configuration.class.getField("windowConfiguration");
                Object wc = wcField.get(cfg);
                if (wc != null) {
                    Method getRot = wc.getClass().getMethod("getRotation");
                    int r = (Integer) getRot.invoke(wc);
                    if (r >= 0) {
                        return r;
                    }
                }
            } catch (Throwable ignored) {
            }
            String s = cfg.toString();
            if (s.contains("mRotation=ROTATION_180") || s.contains("mDisplayRotation=ROTATION_180")) {
                return 2;
            }
            if (s.contains("mRotation=ROTATION_90") || s.contains("mDisplayRotation=ROTATION_90")) {
                return 1;
            }
            if (s.contains("mRotation=ROTATION_270") || s.contains("mDisplayRotation=ROTATION_270")) {
                return 3;
            }
        }
        return d != null ? d.getRotation() : 0;
    }

    /**
     * 对齐小米定制版搜狗输入法 com.sogou.inputmethod.oem.j.r -> com.sogou.bu.basic.util.e.c(window, true, true)：
     * 在 onStartInputView 阶段直接为 TYPE_INPUT_METHOD (2011) 窗口设置：
     * 1) attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS (3)
     * 2) decorView.setSystemUiVisibility(vis | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN(1024) | SYSTEM_UI_FLAG_FULLSCREEN(4))
     * 从而使系统 WindowManager 授予外屏输入法完整 [0,0][1208,1392] 窗口帧。
     */
    @SuppressWarnings("deprecation")
    private static void applySogouCutoutAndFullscreen(InputMethodService ims) {
        if (ims == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        if (!XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_OUTER_FIT, true)) {
            return;
        }
        try {
            android.app.Dialog d = ims.getWindow();
            android.view.Window w = d != null ? d.getWindow() : null;
            if (w == null) {
                return;
            }
            WindowManager.LayoutParams lp = w.getAttributes();
            View decorView = w.getDecorView();
            boolean rot180Full = XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_ROT180_FULL, true);
            int rot = getAccurateRotation(ims, decorView, ims, decorView != null ? decorView.getDisplay() : null);
            int targetMode = (rot180Full && rot == 2)
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            if (lp != null && lp.layoutInDisplayCutoutMode != targetMode) {
                lp.layoutInDisplayCutoutMode = targetMode;
                w.setAttributes(lp);
                XposedCompat.log(TAG + ": set IME cutout=" + targetMode + " rot=" + rot + " attached="
                        + (decorView != null && decorView.isAttachedToWindow()));
            }
            if (decorView != null) {
                int vis = decorView.getSystemUiVisibility();
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN; // 1024 | 4
                if (targetMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                    if ((vis & flags) != flags) {
                        decorView.setSystemUiVisibility(vis | flags);
                    }
                } else if ((vis & flags) != 0) {
                    decorView.setSystemUiVisibility(vis & ~flags);
                }
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": applySogouCutoutAndFullscreen err: " + t);
        }
    }

    private static void applyViewTransform(View v, float px, float py, float sx, float sy, float tx, float ty) {
        if (v == null) {
            return;
        }
        if (v.getPivotX() != px) v.setPivotX(px);
        if (v.getPivotY() != py) v.setPivotY(py);
        if (v.getScaleX() != sx) v.setScaleX(sx);
        if (v.getScaleY() != sy) v.setScaleY(sy);
        if (v.getTranslationX() != tx) v.setTranslationX(tx);
        if (v.getTranslationY() != ty) v.setTranslationY(ty);
    }

    private static void applyRot2SplitLayout(OuterFitRoot root) {
        if (root == null || !(root.child instanceof ViewGroup)) {
            return;
        }
        ViewGroup cg = (ViewGroup) root.child;
        if (cg.getChildCount() == 0 || !(cg.getChildAt(0) instanceof ViewGroup)) {
            return;
        }
        ViewGroup imeRoot = (ViewGroup) cg.getChildAt(0);
        if (imeRoot.getWidth() <= 0 || imeRoot.getHeight() <= 0 || imeRoot.getChildCount() < 2) {
            return;
        }
        View bgRaw = imeRoot.getChildAt(0);
        View t1Raw = imeRoot.getChildAt(1);
        if (!(bgRaw instanceof ViewGroup) || !(t1Raw instanceof ViewGroup)) {
            return;
        }
        ViewGroup bgGroup = (ViewGroup) bgRaw;
        ViewGroup t1 = (ViewGroup) t1Raw;
        View aeq = bgGroup.getChildCount() > 0 ? bgGroup.getChildAt(0) : null;
        View dv = bgGroup.getChildCount() > 1 ? bgGroup.getChildAt(1) : null;

        ViewGroup mainCol = null;
        for (int i = 0; i < t1.getChildCount(); i++) {
            View c = t1.getChildAt(i);
            if (c instanceof LinearLayout && ((ViewGroup) c).getChildCount() >= 2) {
                mainCol = (ViewGroup) c;
                break;
            }
        }
        if (mainCol == null) {
            return;
        }
        View candView = mainCol.getChildAt(0);
        View kbView = mainCol.getChildAt(1);

        InputMethodService ims = root.imsRef.get();
        int rot = getAccurateRotation(ims, root, root.getContext(), root.getDisplay());
        if (rot == 0 && sLastKnownOuterRot == 2) {
            // Avoid transient cutout==null during panel switch falsely resetting rot==2 transform
            android.view.WindowInsets wi = root.getRootWindowInsets();
            android.view.DisplayCutout dc = wi != null ? wi.getDisplayCutout() : null;
            if (dc == null || dc.getSafeInsetRight() <= 0) {
                rot = 2;
            }
        }
        boolean fit = XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_OUTER_FIT, true);
        boolean rotFull = XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_ROT180_FULL, true);
        boolean isOuterSize = (root.getWidth() == 1208 && imeRoot.getWidth() == 1208);
        if (!fit || !rotFull || root.getWidth() != 1208 || rot != 2) {
            resetTransform(candView);
            resetTransform(kbView);
            resetTransform(aeq);
            resetTransform(dv);
            return;
        }
        if (!isOuterSize || kbView.getHeight() <= 0 || candView.getHeight() <= 0) {
            return;
        }

        imeRoot.setClipChildren(false);
        imeRoot.setClipToPadding(false);
        t1.setClipChildren(false);
        t1.setClipToPadding(false);
        mainCol.setClipChildren(false);
        mainCol.setClipToPadding(false);
        bgGroup.setClipChildren(false);
        bgGroup.setClipToPadding(false);

        int imeH = imeRoot.getHeight();
        int imeW = imeRoot.getWidth();
        // Cutout in rot==2 is [0,0][398,728]; target keyboard top at y=736, bottom at y=1392 -> 656px tall.
        float targetKbH = 656.0f;
        float kbTopLocal = imeH - targetKbH;

        int colOffsetY = t1.getTop() + mainCol.getTop();
        float kbH = kbView.getHeight();
        float kbScaleY = Math.max(0.75f, Math.min(1.0f, targetKbH / kbH));
        applyViewTransform(kbView, 0f, kbH, 1.0f, kbScaleY, 0f, imeH - (colOffsetY + kbView.getBottom()));

        if (dv != null && dv.getHeight() > 0) {
            float dvH = dv.getHeight();
            float dvScaleY = Math.max(0.75f, Math.min(1.0f, targetKbH / dvH));
            applyViewTransform(dv, 0f, dvH, 1.0f, dvScaleY, 0f, imeH - (bgGroup.getTop() + dv.getBottom()));
        }

        float candScaleX = (imeW - ROT2_CUTOUT_W) / (float) imeW; // 810 / 1208
        float candScaleY = candScaleX; // keep 1:1 aspect ratio so toolbar buttons stay circular
        float candW = candView.getWidth();
        float candH = candView.getHeight();
        applyViewTransform(candView, candW, candH, candScaleX, candScaleY, 0f, kbTopLocal - (colOffsetY + candView.getBottom()));

        if (aeq != null && aeq.getWidth() > 0 && aeq.getHeight() > 0) {
            applyViewTransform(aeq, aeq.getWidth(), aeq.getHeight(), candScaleX, candScaleY,
                    0f, kbTopLocal - (bgGroup.getTop() + aeq.getBottom()));
        }
    }

    private static void adjustRot2ComputeInsets(InputMethodService ims, InputMethodService.Insets outInsets) {
        OuterFitRoot root = sRootRef.get();
        if (root == null || root.outerMode || root.getWidth() != 1208) {
            return;
        }
        if (!XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_OUTER_FIT, true)
                || !XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_ROT180_FULL, true)) {
            return;
        }
        int rot = getAccurateRotation(ims, root, root.getContext(), root.getDisplay());
        if (rot == 0 && sLastKnownOuterRot == 2) {
            rot = 2;
        }
        if (rot != 2) {
            return;
        }
        android.app.Dialog d = ims.getWindow();
        android.view.Window w = d != null ? d.getWindow() : null;
        View decor = w != null ? w.getDecorView() : null;
        int decorH = decor != null ? decor.getHeight() : 1392;
        if (decorH <= 0) {
            decorH = 1392;
        }
        int candScaledH = 120;
        if (root.child instanceof ViewGroup) {
            ViewGroup cg = (ViewGroup) root.child;
            if (cg.getChildCount() > 0 && cg.getChildAt(0) instanceof ViewGroup) {
                ViewGroup imeRoot = (ViewGroup) cg.getChildAt(0);
                if (imeRoot.getChildCount() >= 2 && imeRoot.getChildAt(1) instanceof ViewGroup) {
                    ViewGroup t1 = (ViewGroup) imeRoot.getChildAt(1);
                    for (int i = 0; i < t1.getChildCount(); i++) {
                        View c = t1.getChildAt(i);
                        if (c instanceof LinearLayout && ((ViewGroup) c).getChildCount() >= 2) {
                            View candView = ((ViewGroup) c).getChildAt(0);
                            if (candView.getHeight() > 0) {
                                float candScale = (1208 - ROT2_CUTOUT_W) / 1208.0f;
                                candScaledH = Math.round(candView.getHeight() * candScale);
                            }
                            break;
                        }
                    }
                }
            }
        }
        int actualTop = Math.max(0, decorH - (656 + candScaledH));
        if (outInsets.contentTopInsets > 0 && outInsets.contentTopInsets < actualTop) {
            outInsets.contentTopInsets = actualTop;
        }
        if (outInsets.visibleTopInsets > 0 && outInsets.visibleTopInsets < actualTop) {
            outInsets.visibleTopInsets = actualTop;
        }
    }

    private static void resetTransform(View v) {
        if (v == null) {
            return;
        }
        if (v.getScaleX() != 1.0f) v.setScaleX(1.0f);
        if (v.getScaleY() != 1.0f) v.setScaleY(1.0f);
        if (v.getTranslationX() != 0.0f) v.setTranslationX(0.0f);
        if (v.getTranslationY() != 0.0f) v.setTranslationY(0.0f);
    }

    private static boolean isInsideOuterFitRoot(View v) {
        View cur = v;
        while (cur != null) {
            if (cur instanceof OuterFitRoot) {
                return true;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    private static Field findProxyField(Class<?> serviceCls) {
        try {
            return serviceCls.getDeclaredField("imeProxy");
        } catch (NoSuchFieldException ignored) {
        }
        try {
            return serviceCls.getDeclaredField("w");
        } catch (NoSuchFieldException ignored) {
        }
        for (Field f : serviceCls.getDeclaredFields()) {
            Class<?> type = f.getType();
            if (type.isInterface()) {
                for (Method m : type.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (m.getReturnType() == void.class && pts.length == 3
                            && pts[0] == View.class && pts[1] == int.class && pts[2] == int.class) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    private static Method findSetupMethod(Class<?> proxyCls) {
        try {
            Method m = proxyCls.getDeclaredMethod("e", View.class, int.class, int.class);
            if (m.getReturnType() == void.class) {
                return m;
            }
        } catch (NoSuchMethodException ignored) {
        }
        for (Method m : proxyCls.getDeclaredMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if (m.getReturnType() == void.class && pts.length == 3
                    && pts[0] == View.class && pts[1] == int.class && pts[2] == int.class) {
                return m;
            }
        }
        return null;
    }

    private static boolean isNormalProxy(InputMethodService ims) {
        if (ims == null || sProxyField == null) {
            return false;
        }
        try {
            Object proxy = sProxyField.get(ims);
            return proxy != null && NORMAL_PROXY_CLASS.equals(proxy.getClass().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static OuterFitRoot unwrapRootIfDirectChild(View view) {
        if (view instanceof OuterFitRoot) {
            return (OuterFitRoot) view;
        }
        ViewParent p = view.getParent();
        if (p instanceof InnerScaleLayout) {
            OuterFitRoot root = ((InnerScaleLayout) p).root;
            if (root != null && root.child == view) {
                return root;
            }
        }
        return null;
    }

    private static boolean isAttachedToInputArea(View view) {
        ViewParent p = view.getParent();
        if (!(p instanceof FrameLayout)) {
            return false;
        }
        FrameLayout inputArea = (FrameLayout) p;
        if (inputArea.getId() != android.R.id.inputArea) {
            return false;
        }
        ViewParent gp = inputArea.getParent();
        if (!(gp instanceof LinearLayout)) {
            return false;
        }
        LinearLayout ll = (LinearLayout) gp;
        return ll.getChildCount() >= 2 && (ll.getChildAt(0) instanceof LinearLayout) && ll.getChildAt(1) == inputArea;
    }

    private static void refreshCurrent() {
        OuterFitRoot root = sRootRef.get();
        if (root != null) {
            root.refresh();
        }
    }

    private static void scheduleSetup(final OuterFitRoot root) {
        if (sPendingSetup || sInSetup || sRootRef.get() != root) {
            return;
        }
        sPendingSetup = true;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                sPendingSetup = false;
                if (sRootRef.get() != root) {
                    return;
                }
                try {
                    InputMethodService ims = root.imsRef.get();
                    if (root.isAttachedToWindow() && isAttachedToInputArea(root)
                            && isNormalProxy(ims) && sSetupAreaMethod != null && sProxyField != null) {
                        sInSetup = true;
                        try {
                            sSetupAreaMethod.invoke(sProxyField.get(ims), root, 0, 0);
                        } finally {
                            sInSetup = false;
                        }
                        root.refresh();
                    }
                } catch (Throwable t) {
                    XposedCompat.log(TAG + ": setup failed: " + t);
                }
            }
        });
    }

    private static final class InnerScaleLayout extends FrameLayout {
        final OuterFitRoot root;

        InnerScaleLayout(Context context, OuterFitRoot root) {
            super(context);
            this.root = root;
        }
    }

    private static final class OuterFitRoot extends FrameLayout implements DisplayManager.DisplayListener {
        final View child;
        final InnerScaleLayout inner;
        final WeakReference<InputMethodService> imsRef;
        DisplayManager dm;
        boolean touching;
        boolean outerMode;
        int targetW;
        int targetH;
        int topOffset;
        float scale = 1.0f;
        String lastMeasureLog = "";
        final android.view.ViewTreeObserver.OnPreDrawListener preDrawListener =
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (!outerMode) {
                            applyRot2SplitLayout(OuterFitRoot.this);
                        }
                        return true;
                    }
                };

        OuterFitRoot(InputMethodService ims, View child) {
            super(ims);
            this.imsRef = new WeakReference<>(ims);
            this.child = child;
            setPadding(0, 0, 0, 0);
            setFocusable(false);
            setClickable(false);
            setClipChildren(true);
            setClipToPadding(true);

            this.inner = new InnerScaleLayout(ims, this);
            this.inner.setPadding(0, 0, 0, 0);
            this.inner.setPivotX(0.0f);
            this.inner.setPivotY(0.0f);
            ViewGroup.LayoutParams childLp = child.getLayoutParams();
            if (childLp == null) {
                childLp = new FrameLayout.LayoutParams(-1, -2);
            }
            this.inner.addView(child, childLp);
            addView(this.inner, new FrameLayout.LayoutParams(-1, -2));
        }

        void refresh() {
            if (touching) {
                long now = SystemClock.uptimeMillis();
                MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
                try {
                    super.dispatchTouchEvent(cancel);
                } finally {
                    cancel.recycle();
                    touching = false;
                }
            }
            child.forceLayout();
            inner.forceLayout();
            requestLayout();
            invalidate();
        }

        @Override
        public android.view.WindowInsets dispatchApplyWindowInsets(android.view.WindowInsets insets) {
            if (!outerMode && insets != null && XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_ROT180_FULL, true)) {
                try {
                    android.view.Display d = getDisplay();
                    int rot = getAccurateRotation(imsRef.get(), this, getContext(), d);
                    if (rot == 2) {
                        if (child.getPaddingLeft() != 0 || child.getPaddingRight() != 0) {
                            child.setPadding(0, child.getPaddingTop(), 0, child.getPaddingBottom());
                        }
                        return super.dispatchApplyWindowInsets(insets.consumeDisplayCutout());
                    }
                } catch (Throwable ignored) {
                }
            }
            return super.dispatchApplyWindowInsets(insets);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touching = true;
            }
            boolean handled = super.dispatchTouchEvent(ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
            }
            return handled;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (!outerMode) {
                super.dispatchDraw(canvas);
                return;
            }
            int save = canvas.save();
            canvas.clipRect(0, topOffset, Math.min(getWidth(), targetW), Math.min(getHeight(), topOffset + targetH));
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (sRootRef.get() == this) {
                scheduleSetup(this);
            }
            try {
                getViewTreeObserver().addOnPreDrawListener(preDrawListener);
            } catch (Throwable ignored) {
            }
            try {
                dm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    dm.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            try {
                getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
            } catch (Throwable ignored) {
            }
            try {
                if (dm != null) {
                    dm.unregisterDisplayListener(this);
                }
            } catch (Throwable ignored) {
            }
            dm = null;
            touching = false;
            super.onDetachedFromWindow();
        }

        @Override
        public void onDisplayAdded(int displayId) {
            refresh();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            refresh();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            refresh();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (!outerMode) {
                topOffset = 0;
                inner.layout(0, 0, r - l, b - t);
                applyRot2SplitLayout(this);
                return;
            }
            topOffset = Math.max(0, getHeight() - targetH);
            inner.layout(0, topOffset, inner.getMeasuredWidth(), topOffset + inner.getMeasuredHeight());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int parentW = MeasureSpec.getSize(widthMeasureSpec);
            Point pt = new Point();
            int rot = -1;
            int safeW = 0;
            int maxW = 0;
            boolean isOuterDisplay = false;
            try {
                Display d = getDisplay();
                if (d == null) {
                    WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                    d = wm != null ? wm.getDefaultDisplay() : null;
                }
                int modeW = 0, modeH = 0;
                if (d != null) {
                    d.getRealSize(pt);
                    rot = getAccurateRotation(imsRef.get(), this, getContext(), d);
                    try {
                        Display.Mode m = d.getMode();
                        if (m != null) {
                            modeW = m.getPhysicalWidth();
                            modeH = m.getPhysicalHeight();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                boolean physOuter = (modeW == 1208 && modeH == 1392)
                        || (modeW == 1392 && modeH == 1208)
                        || (pt.x == 1208 && pt.y == 1392)
                        || parentW == 1208;
                isOuterDisplay = "2505APX7BC".equals(Build.MODEL) && physOuter
                        && XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_OUTER_FIT, true);
                boolean rot180Full = XposedCompat.getBool(ExtraPrefsUI.KEY_WETYPE_ROT180_FULL, true);
                if (isOuterDisplay) {
                    applySogouCutoutAndFullscreen(imsRef.get());
                }
                boolean needClamp = (rot == 0) || (rot == 2 && (!rot180Full || parentW <= 810));
                if (isOuterDisplay && needClamp && parentW <= 810) {
                    safeW = 810;
                } else if (isOuterDisplay && needClamp && pt.x == 1208) {
                    safeW = 810;
                }
                InputMethodService ims = imsRef.get();
                if (ims != null) {
                    if (ims.isFullscreenMode() || !isNormalProxy(ims)) {
                        safeW = 0;
                    }
                    try {
                        maxW = ims.getMaxWidth();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                safeW = 0;
            }

            int desiredW = 0;
            if (safeW > 0 && parentW > 0) {
                desiredW = Math.min(safeW, parentW);
                if (maxW >= 400) {
                    desiredW = Math.min(desiredW, maxW);
                }
            }
            int outerWidth = (pt.x == 1208) ? pt.x : (parentW == 1208 ? 1208 : pt.x);
            outerMode = desiredW > 0 && outerWidth > 0;
            sCurrentOuterMode = outerMode;
            if (!outerMode) {
                scale = 1.0f;
                sCurrentScale = 1.0f;
                inner.setScaleX(1.0f);
                inner.setScaleY(1.0f);
                inner.setTranslationX(0f);
                inner.setTranslationY(0f);
                if (rot == 2 && (child.getPaddingLeft() != 0 || child.getPaddingRight() != 0)) {
                    child.setPadding(0, child.getPaddingTop(), 0, child.getPaddingBottom());
                }
                inner.measure(widthMeasureSpec, heightMeasureSpec);
                targetW = inner.getMeasuredWidth();
                targetH = inner.getMeasuredHeight();
                setMeasuredDimension(targetW, targetH);
                String msg = "phys=" + pt.x + "x" + pt.y + " rot=" + rot + " parentW=" + parentW
                        + " native=" + targetW + "x" + targetH + " target=" + targetW + "x" + targetH
                        + " scale=1.0 mode=NATIVE";
                if (!msg.equals(lastMeasureLog)) {
                    lastMeasureLog = msg;
                    StringBuilder chain = new StringBuilder();
                    View cur = child;
                    for (int depth = 0; cur != null && depth < 7; depth++) {
                        int[] loc = new int[2];
                        cur.getLocationOnScreen(loc);
                        chain.append("[").append(cur.getClass().getSimpleName())
                                .append(" w=").append(cur.getWidth())
                                .append(" l=").append(cur.getLeft())
                                .append(" pL=").append(cur.getPaddingLeft())
                                .append(" tx=").append(cur.getTranslationX())
                                .append(" scr=").append(loc[0]).append("]");
                        cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
                    }
                    XposedCompat.log(TAG + ": MEASURE " + msg + " tree=" + chain);
                }
                return;
            }

            int measureW = outerWidth;
            float newScale = Math.min(1.0f, (float) desiredW / (float) measureW);
            scale = newScale;
            sCurrentScale = newScale;
            inner.setScaleX(newScale);
            inner.setScaleY(newScale);
            inner.setTranslationX(0f);
            inner.setTranslationY(0f);

            int parentH = MeasureSpec.getSize(heightMeasureSpec);
            int childHSpec;
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            } else {
                int availH = (parentH <= 0 || newScale <= 0f) ? 0
                        : (int) Math.min(100000.0, Math.floor((double) parentH / (double) newScale));
                childHSpec = MeasureSpec.makeMeasureSpec(availH, MeasureSpec.AT_MOST);
            }
            inner.measure(MeasureSpec.makeMeasureSpec(measureW, MeasureSpec.EXACTLY), childHSpec);
            targetW = desiredW;
            targetH = (int) Math.ceil((double) Math.max(0, inner.getMeasuredHeight()) * (double) newScale - 1.0e-5);
            setMeasuredDimension(
                    View.resolveSizeAndState(desiredW, widthMeasureSpec, inner.getMeasuredState()),
                    View.resolveSizeAndState(targetH, heightMeasureSpec, 0));

            String msg = "phys=" + pt.x + "x" + pt.y + " rot=" + rot + " parentW=" + parentW
                    + " native=" + inner.getMeasuredWidth() + "x" + inner.getMeasuredHeight()
                    + " target=" + targetW + "x" + targetH + " scale=" + scale
                    + " mode=OUTER";
            if (!msg.equals(lastMeasureLog)) {
                lastMeasureLog = msg;
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                View pView = getParent() instanceof View ? (View) getParent() : null;
                XposedCompat.log(TAG + ": MEASURE " + msg + " onScr=" + loc[0] + "," + loc[1]
                        + " pPadL=" + (pView != null ? pView.getPaddingLeft() : -1)
                        + " cPadL=" + child.getPaddingLeft()
                        + " cLeft=" + child.getLeft());
            }
        }
    }
}
