package com.collasong.flipextra;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class XposedCompat {

    private static volatile XposedModule sModule;

    public interface Callback {
        void before(HookParam param) throws Throwable;
        void after(HookParam param) throws Throwable;
    }

    public static abstract class SimpleCallback implements Callback {
        @Override
        public void before(HookParam param) throws Throwable {}
        @Override
        public void after(HookParam param) throws Throwable {}
    }

    public static final class HookParam {
        public final Object thisObject;
        public final Object[] args;
        private Object result;
        private boolean hasResult;

        HookParam(Object thisObject, Object[] args) {
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.hasResult = true;
        }

        public boolean hasResult() {
            return hasResult;
        }
    }

    public static void init(XposedModule module) {
        sModule = module;
    }

    public static boolean getBool(String key, boolean defVal) {
        XposedModule m = sModule;
        if (m == null) {
            return defVal;
        }
        try {
            SharedPreferences sp = m.getRemotePreferences("mixflipmod");
            if (sp != null) {
                return sp.getBoolean(key, defVal);
            }
        } catch (Throwable ignored) {
        }
        return defVal;
    }

    public static int getInt(String key, int defVal) {
        XposedModule m = sModule;
        if (m == null) {
            return defVal;
        }
        try {
            SharedPreferences sp = m.getRemotePreferences("mixflipmod");
            if (sp != null) {
                return sp.getInt(key, defVal);
            }
        } catch (Throwable ignored) {
        }
        return defVal;
    }

    public static void log(String msg) {
        XposedModule m = sModule;
        if (m != null) {
            m.log(Log.INFO, "FlipExtra", msg, null);
        } else {
            Log.i("FlipExtra", msg);
        }
    }

    public static void hook(Executable target, final Callback cb) {
        XposedModule m = sModule;
        if (m == null || target == null) {
            return;
        }
        m.hook(target).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                List<Object> argList = chain.getArgs();
                Object[] args = argList != null ? argList.toArray(new Object[0]) : new Object[0];
                HookParam param = new HookParam(chain.getThisObject(), args);
                cb.before(param);
                if (param.hasResult()) {
                    return param.getResult();
                }
                Object res = chain.proceed(args);
                param.result = res;
                cb.after(param);
                return param.hasResult() ? param.getResult() : res;
            }
        });
    }

    public static void hookMethod(Class<?> cls, String name, Class<?>[] paramTypes, Callback cb)
            throws NoSuchMethodException {
        Method m = cls.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        hook(m, cb);
    }

    public static void hookAllMethods(Class<?> cls, String name, Callback cb) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                hook(m, cb);
            }
        }
    }
}
