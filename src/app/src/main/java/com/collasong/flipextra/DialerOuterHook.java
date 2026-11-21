package com.collasong.flipextra;

import android.content.Context;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * P5: 外屏拨号盘布局与体验对齐内屏。
 * <p>
 * 1) 在外屏下将 12 键行高缩放至 0.72f，并按外屏可用宽度（左右留白 48px）水平居中铺满；
 * 2) 同步按高度比例微调单键内部数字/字母字号与垂直间距，避免缩高后数字与字母重叠；
 * 3) 当拨号盘（dialer_pad）在外屏可见时自动隐藏并穿透顶部的 action_bar_container，
 *    避免「通话」大标题与绿色号码输入框(digits)/退格键(clear_button)重叠及吞掉点击；
 *    拨号盘收起时自动恢复 action_bar_container。
 */
public final class DialerOuterHook {

    private static final String TAG = "FlipExtra/Dialer";
    private static final float OUTER_HEIGHT_SCALE = 0.72f;
    private static final float INNER_TEXT_SCALE = 0.80f;
    private static final int OUTER_SIDE_PADDING_PX = 48;
    private static final int TAG_ORIG_TEXT_SIZE = 0x0FD10002;
    private static final int TAG_SYNC_ATTACHED = 0x0FD10003;

    private static Method sSetMeasuredDim;
    private static boolean sLoggedScaled = false;

    static {
        try {
            sSetMeasuredDim = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
            sSetMeasuredDim.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isOuterScreen(View v) {
        Display d = v.getDisplay();
        if (d == null) {
            try {
                WindowManager wm = (WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    d = wm.getDefaultDisplay();
                }
            } catch (Throwable ignored) {
            }
        }
        if (d != null) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                return true;
            }
            try {
                Display.Mode m = d.getMode();
                if (m != null) {
                    int mw = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                    int mh = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                    if (mw == 1208 && mh == 1392) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            android.graphics.Point pt = new android.graphics.Point();
            d.getRealSize(pt);
            int min = Math.min(pt.x, pt.y);
            int max = Math.max(pt.x, pt.y);
            if (min == 1208 && max == 1392) {
                return true;
            }
        }
        DisplayMetrics dm = v.getResources().getDisplayMetrics();
        int min = Math.min(dm.widthPixels, dm.heightPixels);
        int max = Math.max(dm.widthPixels, dm.heightPixels);
        return min == 1208 && max <= 1450;
    }

    private static void adjustKeyChildren(View item) {
        if (!(item instanceof ViewGroup)) {
            return;
        }
        Context ctx = item.getContext();
        String pkg = ctx.getPackageName();
        int idNum = ctx.getResources().getIdentifier("dialer_number", "id", pkg);
        int idLet = ctx.getResources().getIdentifier("dialer_letter", "id", pkg);
        int idImg = ctx.getResources().getIdentifier("dialer_letter_img", "id", pkg);

        View numV = idNum != 0 ? item.findViewById(idNum) : null;
        if (numV instanceof TextView) {
            TextView tv = (TextView) numV;
            Object origObj = tv.getTag(TAG_ORIG_TEXT_SIZE);
            float origPx = (origObj instanceof Float) ? (Float) origObj : tv.getTextSize();
            if (!(origObj instanceof Float) && origPx > 0f) {
                tv.setTag(TAG_ORIG_TEXT_SIZE, origPx);
            }
            float targetPx = origPx * INNER_TEXT_SCALE;
            if (targetPx > 0f && Math.abs(tv.getTextSize() - targetPx) > 0.5f) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx);
            }
            tv.setTranslationY(-10f);
        }

        View letV = idLet != 0 ? item.findViewById(idLet) : null;
        if (letV instanceof TextView) {
            TextView tv = (TextView) letV;
            Object origObj = tv.getTag(TAG_ORIG_TEXT_SIZE);
            float origPx = (origObj instanceof Float) ? (Float) origObj : tv.getTextSize();
            if (!(origObj instanceof Float) && origPx > 0f) {
                tv.setTag(TAG_ORIG_TEXT_SIZE, origPx);
            }
            float targetPx = origPx * 0.90f;
            if (targetPx > 0f && Math.abs(tv.getTextSize() - targetPx) > 0.5f) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx);
            }
            tv.setTranslationY(10f);
        }

        View imgV = idImg != 0 ? item.findViewById(idImg) : null;
        if (imgV != null) {
            imgV.setScaleX(0.85f);
            imgV.setScaleY(0.85f);
            imgV.setTranslationY(10f);
        }
    }

    private static void restoreActionBar(View anyView) {
        if (anyView == null) {
            return;
        }
        View root = anyView.getRootView();
        if (root == null) {
            return;
        }
        Context ctx = anyView.getContext();
        int idActionBarContainer = ctx.getResources().getIdentifier("action_bar_container", "id", ctx.getPackageName());
        if (idActionBarContainer == 0) {
            return;
        }
        View actionBarContainer = root.findViewById(idActionBarContainer);
        if (actionBarContainer != null) {
            if (actionBarContainer.getVisibility() != View.VISIBLE) {
                actionBarContainer.setVisibility(View.VISIBLE);
            }
            if (!actionBarContainer.isClickable()) {
                actionBarContainer.setClickable(true);
            }
        }
    }

    private static void syncActionBarVisibility(View anyView) {
        if (anyView == null || !isOuterScreen(anyView)) {
            return;
        }
        if (!XposedCompat.getBool(ExtraPrefsUI.KEY_DIALER_OUTER_FIT, true)) {
            restoreActionBar(anyView);
            return;
        }
        View root = anyView.getRootView();
        if (root == null) {
            return;
        }
        Context ctx = anyView.getContext();
        String pkg = ctx.getPackageName();
        int idActionBarContainer = ctx.getResources().getIdentifier("action_bar_container", "id", pkg);
        int idDialerPad = ctx.getResources().getIdentifier("dialer_pad", "id", pkg);
        if (idActionBarContainer == 0 || idDialerPad == 0) {
            return;
        }
        View actionBarContainer = root.findViewById(idActionBarContainer);
        View dialerPad = root.findViewById(idDialerPad);
        if (actionBarContainer == null || dialerPad == null) {
            return;
        }
        boolean padShown = dialerPad.getVisibility() == View.VISIBLE && dialerPad.isShown();
        if (padShown) {
            if (actionBarContainer.getVisibility() != View.INVISIBLE) {
                actionBarContainer.setVisibility(View.INVISIBLE);
            }
            if (actionBarContainer.isClickable()) {
                actionBarContainer.setClickable(false);
            }
        } else {
            if (actionBarContainer.getVisibility() != View.VISIBLE) {
                actionBarContainer.setVisibility(View.VISIBLE);
            }
            if (!actionBarContainer.isClickable()) {
                actionBarContainer.setClickable(true);
            }
        }
    }

    private static void ensureActionBarSync(final View vg) {
        if (vg.getTag(TAG_SYNC_ATTACHED) != null) {
            return;
        }
        vg.setTag(TAG_SYNC_ATTACHED, Boolean.TRUE);
        final ViewTreeObserver vto = vg.getViewTreeObserver();
        if (vto != null && vto.isAlive()) {
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    try {
                        syncActionBarVisibility(vg);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
        }
    }

    public static void install(ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass("com.android.contacts.dialer.view.DialPadDigitsKeyContainer");
            XposedCompat.hookMethod(cls, "onMeasure", new Class<?>[]{int.class, int.class},
                    new XposedCompat.SimpleCallback() {
                        @Override
                        public void after(XposedCompat.HookParam param) {
                            ViewGroup vg = (ViewGroup) param.thisObject;
                            if (!XposedCompat.getBool(ExtraPrefsUI.KEY_DIALER_OUTER_FIT, true)) {
                                restoreActionBar(vg);
                                return;
                            }
                            if (!isOuterScreen(vg)) {
                                return;
                            }
                            ensureActionBarSync(vg);
                            syncActionBarVisibility(vg);

                            int count = vg.getChildCount();
                            int w = vg.getMeasuredWidth();
                            int h = vg.getMeasuredHeight();
                            if (count <= 0 || w <= 0 || h <= 0 || sSetMeasuredDim == null) {
                                return;
                            }
                            View first = vg.getChildAt(0);
                            int ch = first.getMeasuredHeight();
                            if (ch <= 0) {
                                return;
                            }
                            int newCw = Math.max(1, (w - OUTER_SIDE_PADDING_PX * 2) / 3);
                            int newCh = Math.round(ch * OUTER_HEIGHT_SCALE);
                            int wSpec = View.MeasureSpec.makeMeasureSpec(newCw, View.MeasureSpec.EXACTLY);
                            int hSpec = View.MeasureSpec.makeMeasureSpec(newCh, View.MeasureSpec.EXACTLY);
                            for (int i = 0; i < count; i++) {
                                View child = vg.getChildAt(i);
                                adjustKeyChildren(child);
                                child.measure(wSpec, hSpec);
                            }
                            int newH = Math.round(h * OUTER_HEIGHT_SCALE);
                            try {
                                sSetMeasuredDim.invoke(vg, w, newH);
                                if (!sLoggedScaled) {
                                    sLoggedScaled = true;
                                    XposedCompat.log(TAG + ": outer dialer grid " + w + "x" + newH
                                            + " key=" + newCw + "x" + newCh);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedCompat.hookMethod(cls, "onLayout",
                    new Class<?>[]{boolean.class, int.class, int.class, int.class, int.class},
                    new XposedCompat.SimpleCallback() {
                        @Override
                        public void before(XposedCompat.HookParam param) {
                            ViewGroup vg = (ViewGroup) param.thisObject;
                            if (!XposedCompat.getBool(ExtraPrefsUI.KEY_DIALER_OUTER_FIT, true) || !isOuterScreen(vg)) {
                                return;
                            }
                            int count = vg.getChildCount();
                            if (count < 12) {
                                return;
                            }
                            int left = (Integer) param.args[1];
                            int right = (Integer) param.args[3];
                            int w = right - left;
                            int kw = vg.getChildAt(0).getMeasuredWidth();
                            int kh = vg.getChildAt(0).getMeasuredHeight();
                            if (w <= 0 || kw <= 0 || kh <= 0) {
                                return;
                            }
                            int startX = Math.max(0, (w - kw * 3) / 2);
                            for (int row = 0; row < 4; row++) {
                                int y = row * kh;
                                for (int col = 0; col < 3; col++) {
                                    int idx = row * 3 + col;
                                    int x = startX + col * kw;
                                    vg.getChildAt(idx).layout(x, y, x + kw, y + kh);
                                }
                            }
                            param.setResult(null);
                        }
                    });

            XposedCompat.log(TAG + ": hooked DialPadDigitsKeyContainer.onMeasure/onLayout");
        } catch (Throwable t) {
            XposedCompat.log(TAG + ": install failed: " + t);
        }
    }
}
