package com.collasong.flipextra;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将扩展模块开关（P1 状态栏双击锁屏、P10 微信输入法避让、P5 外屏拨号盘对齐等）
 * 原生注入到 cn.zeldati.mixflipmod 设置界面（cb 配置表 + Compose UI）。
 */
public final class ExtraPrefsUI {

    private static final String TAG = "FlipExtra/PrefsUI";

    public static final String KEY_STATUS_DOUBLE_TAP = "sysui_outer_double_tap_lock";
    public static final String KEY_WETYPE_OUTER_FIT = "wetype_outer_fit_401";
    public static final String KEY_WETYPE_ROT180_FULL = "wetype_rot180_full";
    public static final String KEY_FLIPHOME_WIDGET_GLASS = "fliphome_widget_glass";
    public static final String KEY_FLIPHOME_WIDGET_GLASS_ALPHA = "fliphome_widget_glass_alpha";
    public static final String KEY_FLIPHOME_WIDGET_COLOR_FILL = "fliphome_widget_color_fill";
    public static final String KEY_DIALER_OUTER_FIT = "dialer_outer_fit";
    public static final String KEY_HIDE_OUTER_ANTIMISTAKE = "hide_outer_antimistake_bar";
    public static final String KEY_NETEASE_OUTER_TOP_FIX = "netease_outer_top_fix";
    public static final String KEY_OUTER_XIAOAI_STANDBY = "outer_xiaoai_standby";
    public static final String KEY_HYPER_VIEW_SCALE_FIX = "sysui_hyper_view_scale_rot180_fix";

    private static final int ID_STATUS_LOCK_TITLE   = 0x7f990001;
    private static final int ID_STATUS_LOCK_SUMMARY = 0x7f990002;
    private static final int ID_WETYPE_TITLE        = 0x7f990003;
    private static final int ID_WETYPE_SUMMARY      = 0x7f990004;
    private static final int ID_DIALER_TITLE        = 0x7f990005;
    private static final int ID_DIALER_SUMMARY      = 0x7f990006;
    private static final int ID_WETYPE_ROT180_TITLE = 0x7f990007;
    private static final int ID_WETYPE_ROT180_SUM   = 0x7f990008;
    private static final int ID_WIDGET_GLASS_TITLE  = 0x7f990009;
    private static final int ID_WIDGET_GLASS_SUM    = 0x7f99000a;
    private static final int ID_HIDE_ANTIMISTAKE_TITLE = 0x7f99000b;
    private static final int ID_HIDE_ANTIMISTAKE_SUM   = 0x7f99000c;
    private static final int ID_NETEASE_TOP_TITLE      = 0x7f99000d;
    private static final int ID_NETEASE_TOP_SUM        = 0x7f99000e;
    private static final int ID_WIDGET_COLOR_FILL_TITLE = 0x7f99000f;
    private static final int ID_WIDGET_COLOR_FILL_SUM   = 0x7f990010;
    private static final int ID_WIDGET_GLASS_ALPHA_TITLE = 0x7f990011;
    private static final int ID_GLASS_ALPHA_5            = 0x7f990012;
    private static final int ID_GLASS_ALPHA_15           = 0x7f990013;
    private static final int ID_GLASS_ALPHA_25           = 0x7f990014;
    private static final int ID_GLASS_ALPHA_35           = 0x7f990015;
    private static final int ID_GLASS_ALPHA_50           = 0x7f990016;
    private static final int ID_GLASS_ALPHA_70           = 0x7f990017;
    private static final int ID_GLASS_ALPHA_85           = 0x7f990018;
    private static final int ID_XIAOAI_STANDBY_TITLE     = 0x7f990019;
    private static final int ID_XIAOAI_STANDBY_SUM       = 0x7f99001a;
    private static final int ID_HYPER_VIEW_SCALE_TITLE   = 0x7f99001b;
    private static final int ID_HYPER_VIEW_SCALE_SUM     = 0x7f99001c;

    private static final Map<Integer, String> STRINGS = new HashMap<>();
    private static final String[] DEFAULT_TRUE_KEYS = {
            KEY_STATUS_DOUBLE_TAP,
            KEY_WETYPE_OUTER_FIT,
            KEY_WETYPE_ROT180_FULL,
            KEY_FLIPHOME_WIDGET_GLASS,
            KEY_FLIPHOME_WIDGET_COLOR_FILL,
            KEY_DIALER_OUTER_FIT,
            KEY_HIDE_OUTER_ANTIMISTAKE,
            KEY_NETEASE_OUTER_TOP_FIX,
            KEY_OUTER_XIAOAI_STANDBY,
            KEY_HYPER_VIEW_SCALE_FIX
    };

    private static volatile boolean sInjected = false;
    private static volatile boolean sDefaultsChecked = false;

    static {
        STRINGS.put(ID_STATUS_LOCK_TITLE, "外屏状态栏双击锁屏");
        STRINGS.put(ID_STATUS_LOCK_SUMMARY, "双击外屏顶部状态栏息屏（由扩展模块接管）");
        STRINGS.put(ID_WETYPE_TITLE, "微信输入法 4.0.1+ 外屏避让");
        STRINGS.put(ID_WETYPE_SUMMARY, "兼容外屏比例模式，自动收拢至 810px 安全区避让镜头");
        STRINGS.put(ID_WETYPE_ROT180_TITLE, "微信输入法倒置（摄像头朝上）全宽");
        STRINGS.put(ID_WETYPE_ROT180_SUM, "外屏倒置持握时放开 1208px 底部全宽输入体验");
        STRINGS.put(ID_WIDGET_GLASS_TITLE, "外屏小部件常态柔光玻璃");
        STRINGS.put(ID_WIDGET_GLASS_SUM, "桌面及打开应用侧边常驻小部件保持透明柔光玻璃质感");
        STRINGS.put(ID_DIALER_TITLE, "外屏拨号盘布局对齐内屏");
        STRINGS.put(ID_DIALER_SUMMARY, "居中铺满 12 键网格并自动避让顶部通话标题栏");
        STRINGS.put(ID_HIDE_ANTIMISTAKE_TITLE, "外屏真全屏（隐藏底部灰白防误触条）");
        STRINGS.put(ID_HIDE_ANTIMISTAKE_SUM, "隐藏外屏底部 32px 系统防误触遮罩，消除应用与输入法底部留边（更新后需重启）");
        STRINGS.put(ID_NETEASE_TOP_TITLE, "网易云外屏界面下移修正");
        STRINGS.put(ID_NETEASE_TOP_SUM, "阻止外屏焦点触发窗口异常向下滚动，消除顶部黑边、双重留白及底部导航栏被顶出屏幕");
        STRINGS.put(ID_WIDGET_COLOR_FILL_TITLE, "未补色应用侧边智能补色");
        STRINGS.put(ID_WIDGET_COLOR_FILL_SUM, "针对 LSPosed 等透明导航栏应用未补白边的情况，自动抬高小部件浅色磨砂托底保证文字清晰");
        STRINGS.put(ID_WIDGET_GLASS_ALPHA_TITLE, "玻璃通透度调节");
        STRINGS.put(ID_GLASS_ALPHA_5, "5% 极清透明");
        STRINGS.put(ID_GLASS_ALPHA_15, "15% 轻透玻璃（推荐）");
        STRINGS.put(ID_GLASS_ALPHA_25, "25% 柔和微磨砂");
        STRINGS.put(ID_GLASS_ALPHA_35, "35% 清晰磨砂白");
        STRINGS.put(ID_GLASS_ALPHA_50, "50% 浓雾磨砂");
        STRINGS.put(ID_GLASS_ALPHA_70, "70% 高对比浅白");
        STRINGS.put(ID_GLASS_ALPHA_85, "85% 近似纯白");
        STRINGS.put(ID_XIAOAI_STANDBY_TITLE, "合盖外屏唤醒小爱直达陪伴模式");
        STRINGS.put(ID_XIAOAI_STANDBY_SUM, "合盖态长按电源键等快捷方式唤醒超级小爱时，直接进入 Standby 实时语音陪伴（更新后需重启）");
        STRINGS.put(ID_HYPER_VIEW_SCALE_TITLE, "外屏倒置应用卡片动画修正");
        STRINGS.put(ID_HYPER_VIEW_SCALE_SUM, "修复摄像头朝上时小红书、B 站等「哪里点开哪里返回」动画错位偏移（无需额外勾选第三方应用）");
        STRINGS.put(0x7f090067, "MixFlipMod 派生版：外屏布局、输入法与音乐适配\n由collasong修改定制os4相关内容");
    }

    private ExtraPrefsUI() {
    }

    public static void onMainActivityCreate(Activity act) {
        if (act == null) {
            return;
        }
        try {
            wrapActivityResources(act);
        } catch (Throwable t) {
            Log.w(TAG, "wrapActivityResources failed", t);
        }
        try {
            injectIntoCb(act.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "injectIntoCb failed", t);
        }
    }

    public static void ensureDefaults(SharedPreferences sp) {
        if (sDefaultsChecked || sp == null) {
            return;
        }
        sDefaultsChecked = true;
        try {
            SharedPreferences.Editor ed = null;
            if (sp.contains("restore_sogou_once_v1")) {
                ed = sp.edit();
                ed.remove("restore_sogou_once_v1");
                ed.putBoolean(KEY_WETYPE_OUTER_FIT, true);
            }
            if (sp.getBoolean("widget_import", false)) {
                ed = sp.edit();
                ed.putBoolean("widget_import", false);
            }
            for (String key : DEFAULT_TRUE_KEYS) {
                if (!sp.contains(key)) {
                    if (ed == null) {
                        ed = sp.edit();
                    }
                    ed.putBoolean(key, true);
                }
            }
            if (!sp.contains(KEY_FLIPHOME_WIDGET_GLASS_ALPHA)) {
                if (ed == null) {
                    ed = sp.edit();
                }
                ed.putInt(KEY_FLIPHOME_WIDGET_GLASS_ALPHA, 15);
            }
            if (ed != null) {
                ed.apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureDefaults failed", t);
        }
    }

    private static void wrapActivityResources(Activity act) {
        Resources orig = act.getResources();
        if (orig instanceof ExtraResources) {
            return;
        }
        Resources wrapped = new ExtraResources(orig);
        setFieldInHierarchy(act, ContextWrapper.class, "mResources", wrapped);
        setFieldInHierarchy(act, android.view.ContextThemeWrapper.class, "mResources", wrapped);
        Context base = act.getBaseContext();
        if (base != null) {
            setFieldInHierarchy(base, base.getClass(), "mResources", wrapped);
        }
    }

    private static void setFieldInHierarchy(Object target, Class<?> startCls, String fieldName, Object val) {
        Class<?> c = startCls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, val);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static synchronized void injectIntoCb(ClassLoader cl) throws Exception {
        if (sInjected) {
            return;
        }
        Class<?> cbCls = cl.loadClass("cb");
        Class<?> bbCls = cl.loadClass("bb");
        Class<?> k21Cls = cl.loadClass("k21");
        Class<?> qmCls = cl.loadClass("qm");
        Class<?> pmCls = cl.loadClass("pm");

        Constructor<?> k21Ctor = k21Cls.getDeclaredConstructor(
                String.class, int.class, Integer.class, List.class, int.class);
        k21Ctor.setAccessible(true);

        Field bbPkgField = bbCls.getDeclaredField("a");
        Field bbPrefsField = bbCls.getDeclaredField("b");
        bbPkgField.setAccessible(true);
        bbPrefsField.setAccessible(true);

        Constructor<?> qmCtor = qmCls.getDeclaredConstructor(bbCls);
        qmCtor.setAccessible(true);

        // 0. android (系统框架) 分组追加：外屏真全屏 + 合盖外屏唤醒小爱直达陪伴模式
        Object androidBb = cbCls.getDeclaredField("b").get(null);
        appendSwitch(androidBb, bbPrefsField,
                k21Ctor.newInstance(KEY_HIDE_OUTER_ANTIMISTAKE, ID_HIDE_ANTIMISTAKE_TITLE,
                        Integer.valueOf(ID_HIDE_ANTIMISTAKE_SUM), null, 24));
        appendSwitch(androidBb, bbPrefsField,
                k21Ctor.newInstance(KEY_OUTER_XIAOAI_STANDBY, ID_XIAOAI_STANDBY_TITLE,
                        Integer.valueOf(ID_XIAOAI_STANDBY_SUM), null, 24));

        // 1. com.android.systemui 分组追加：外屏状态栏双击锁屏 + 外屏倒置应用卡片动画修正
        Object sysuiBb = cbCls.getDeclaredField("c").get(null);
        appendSwitch(sysuiBb, bbPrefsField,
                k21Ctor.newInstance(KEY_STATUS_DOUBLE_TAP, ID_STATUS_LOCK_TITLE,
                        Integer.valueOf(ID_STATUS_LOCK_SUMMARY), null, 24));
        appendSwitch(sysuiBb, bbPrefsField,
                k21Ctor.newInstance(KEY_HYPER_VIEW_SCALE_FIX, ID_HYPER_VIEW_SCALE_TITLE,
                        Integer.valueOf(ID_HYPER_VIEW_SCALE_SUM), null, 24));

        // 2. com.tencent.wetype 分组：移除原版旧款外屏缩放开关（wechat_ime_fit，不兼容 4.0.1+），追加 4.0.1+ 外屏避让 + 倒置全宽
        Object wetypeBb = cbCls.getDeclaredField("g").get(null);
        removeSwitchesByKey(wetypeBb, bbPrefsField, "wechat_ime_fit");
        appendSwitch(wetypeBb, bbPrefsField,
                k21Ctor.newInstance(KEY_WETYPE_OUTER_FIT, ID_WETYPE_TITLE,
                        Integer.valueOf(ID_WETYPE_SUMMARY), null, 24));
        appendSwitch(wetypeBb, bbPrefsField,
                k21Ctor.newInstance(KEY_WETYPE_ROT180_FULL, ID_WETYPE_ROT180_TITLE,
                        Integer.valueOf(ID_WETYPE_ROT180_SUM), null, 24));

        // 3. com.miui.fliphome 分组：移除三合一对齐与第三方小部件导入，追加外屏小部件常态柔光玻璃 + 通透度选择 + 侧边智能补色
        Object fliphomeBb = cbCls.getDeclaredField("d").get(null);
        removeSwitchesByKey(fliphomeBb, bbPrefsField, "trio_text_alignment", "widget_import");
        appendSwitch(fliphomeBb, bbPrefsField,
                k21Ctor.newInstance(KEY_FLIPHOME_WIDGET_GLASS, ID_WIDGET_GLASS_TITLE,
                        Integer.valueOf(ID_WIDGET_GLASS_SUM), null, 24));
        Object alphaSelect = buildGlassAlphaSelect(cl);
        if (alphaSelect != null) {
            appendSwitch(fliphomeBb, bbPrefsField, alphaSelect);
        }
        appendSwitch(fliphomeBb, bbPrefsField,
                k21Ctor.newInstance(KEY_FLIPHOME_WIDGET_COLOR_FILL, ID_WIDGET_COLOR_FILL_TITLE,
                        Integer.valueOf(ID_WIDGET_COLOR_FILL_SUM), null, 24));

        // 3.5. com.netease.cloudmusic 分组追加：网易云倒置顶部留白修正
        Object neteaseBb = cbCls.getDeclaredField("h").get(null);
        appendSwitch(neteaseBb, bbPrefsField,
                k21Ctor.newInstance(KEY_NETEASE_OUTER_TOP_FIX, ID_NETEASE_TOP_TITLE,
                        Integer.valueOf(ID_NETEASE_TOP_SUM), null, 24));

        // 4. 新增 com.android.contacts 分组：外屏拨号盘布局对齐内屏
        Object contactsBb = allocateInstance(bbCls);
        bbPkgField.set(contactsBb, "com.android.contacts");
        List<Object> contactsList = new ArrayList<>();
        contactsList.add(k21Ctor.newInstance(KEY_DIALER_OUTER_FIT, ID_DIALER_TITLE,
                Integer.valueOf(ID_DIALER_SUMMARY), null, 24));
        bbPrefsField.set(contactsBb, contactsList);

        Field cbIField = cbCls.getDeclaredField("i");
        cbIField.setAccessible(true);
        List<Object> iList = new ArrayList<>((List<Object>) cbIField.get(null));
        Object paBb = cbCls.getDeclaredField("e").get(null);
        Field qmBbField = null;
        for (Field f : qmCls.getDeclaredFields()) {
            if (f.getType() == bbCls) {
                f.setAccessible(true);
                qmBbField = f;
                break;
            }
        }
        List<Object> removedGalleryBbs = new ArrayList<>();
        for (int idx = iList.size() - 1; idx >= 0; idx--) {
            Object item = iList.get(idx);
            if (pmCls.isInstance(item)) {
                for (Field f : pmCls.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object listObj = f.get(item);
                        if (listObj instanceof List) {
                            List<Object> sub = new ArrayList<>((List<Object>) listObj);
                            for (int j = sub.size() - 1; j >= 0; j--) {
                                Object bObj = sub.get(j);
                                if (bObj != null && bbCls.isInstance(bObj)
                                        && "com.miui.gallery".equals(bbPkgField.get(bObj))) {
                                    removedGalleryBbs.add(bObj);
                                    sub.remove(j);
                                }
                            }
                            f.set(item, sub);
                        }
                    }
                }
            } else if (qmBbField != null && qmCls.isInstance(item)
                    && qmBbField.get(item) == paBb) {
                iList.remove(idx);
            }
        }
        try {
            Field cbAField = cbCls.getDeclaredField("a");
            cbAField.setAccessible(true);
            List<String> aOrig = (List<String>) cbAField.get(null);
            if (aOrig != null && aOrig.contains("com.miui.gallery")) {
                List<String> aCopy = new ArrayList<>(aOrig);
                aCopy.remove("com.miui.gallery");
                cbAField.set(null, aCopy);
            }
        } catch (Throwable ignored) {
        }
        iList.add(qmCtor.newInstance(contactsBb));
        cbIField.set(null, iList);

        Field cbJField = cbCls.getDeclaredField("j");
        cbJField.setAccessible(true);
        List<Object> jList = (List<Object>) cbJField.get(null);
        jList.remove(paBb);
        jList.removeAll(removedGalleryBbs);
        jList.add(contactsBb);

        sInjected = true;
        Log.i(TAG, "injected extra switches & removed gallery/personalassistant");
    }

    private static Object allocateInstance(Class<?> cls) throws Exception {
        try {
            Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (NoSuchMethodException ignored) {
        }
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafeCls.getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
    }

    @SuppressWarnings("unchecked")
    private static void removeSwitchesByKey(Object bbObj, Field bbPrefsField, String... keysToRemove) throws Exception {
        List<Object> orig = (List<Object>) bbPrefsField.get(bbObj);
        if (orig == null) {
            return;
        }
        List<Object> copy = new ArrayList<>();
        for (Object item : orig) {
            if (item == null) {
                continue;
            }
            String key = null;
            try {
                Field aField = item.getClass().getField("a");
                Object val = aField.get(item);
                if (val instanceof String) {
                    key = (String) val;
                }
            } catch (Throwable ignored) {
            }
            boolean remove = false;
            if (key != null) {
                for (String k : keysToRemove) {
                    if (k.equals(key)) {
                        remove = true;
                        break;
                    }
                }
            }
            if (!remove) {
                copy.add(item);
            }
        }
        bbPrefsField.set(bbObj, copy);
    }

    @SuppressWarnings("unchecked")
    private static void appendSwitch(Object bbObj, Field bbPrefsField, Object k21Obj) throws Exception {
        String newKey = extractSwitchKey(k21Obj);
        if (newKey != null) {
            removeSwitchesByKey(bbObj, bbPrefsField, newKey);
        }
        List<Object> orig = (List<Object>) bbPrefsField.get(bbObj);
        List<Object> copy = orig != null ? new ArrayList<>(orig) : new ArrayList<>();
        copy.add(k21Obj);
        bbPrefsField.set(bbObj, copy);
    }

    private static Object buildGlassAlphaSelect(ClassLoader cl) {
        try {
            Class<?> j21Cls = cl.loadClass("j21");
            Class<?> h21Cls = cl.loadClass("h21");
            Class<?> sv0Cls = cl.loadClass("sv0");
            Class<?> uv0Cls = cl.loadClass("uv0");
            Constructor<?> sv0Ctor = sv0Cls.getDeclaredConstructor(int.class);
            Constructor<?> j21Ctor = j21Cls.getDeclaredConstructor(
                    String.class, int.class, uv0Cls, List.class, int.class);
            sv0Ctor.setAccessible(true);
            j21Ctor.setAccessible(true);
            Field h21Val = h21Cls.getDeclaredField("a");
            Field h21Label = h21Cls.getDeclaredField("b");
            h21Val.setAccessible(true);
            h21Label.setAccessible(true);

            int[] values = {5, 15, 25, 35, 50, 70, 85};
            int[] labels = {
                    ID_GLASS_ALPHA_5, ID_GLASS_ALPHA_15, ID_GLASS_ALPHA_25,
                    ID_GLASS_ALPHA_35, ID_GLASS_ALPHA_50, ID_GLASS_ALPHA_70, ID_GLASS_ALPHA_85
            };
            List<Object> opts = new ArrayList<>(values.length);
            for (int i = 0; i < values.length; i++) {
                Object opt = allocateInstance(h21Cls);
                h21Val.set(opt, sv0Ctor.newInstance(values[i]));
                h21Label.setInt(opt, labels[i]);
                opts.add(opt);
            }
            return j21Ctor.newInstance(
                    KEY_FLIPHOME_WIDGET_GLASS_ALPHA,
                    ID_WIDGET_GLASS_ALPHA_TITLE,
                    sv0Ctor.newInstance(15),
                    opts,
                    36);
        } catch (Throwable t) {
            Log.w(TAG, "buildGlassAlphaSelect failed", t);
            return null;
        }
    }

    private static String extractSwitchKey(Object item) {
        if (item == null) {
            return null;
        }
        try {
            Object val = item.getClass().getField("a").get(item);
            return val instanceof String ? (String) val : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static final class ExtraResources extends Resources {
        private final Resources mBase;

        ExtraResources(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            mBase = base;
        }

        @Override
        public String getString(int id) throws NotFoundException {
            String custom = STRINGS.get(id);
            if (custom != null) {
                return custom;
            }
            return mBase.getString(id);
        }

        @Override
        public String getString(int id, Object... formatArgs) throws NotFoundException {
            String custom = STRINGS.get(id);
            if (custom != null) {
                return String.format(custom, formatArgs);
            }
            return mBase.getString(id, formatArgs);
        }

        @Override
        public CharSequence getText(int id) throws NotFoundException {
            String custom = STRINGS.get(id);
            if (custom != null) {
                return custom;
            }
            return mBase.getText(id);
        }

        @Override
        public CharSequence getText(int id, CharSequence def) {
            String custom = STRINGS.get(id);
            if (custom != null) {
                return custom;
            }
            return mBase.getText(id, def);
        }
    }
}
