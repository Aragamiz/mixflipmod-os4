package io.github.libxposed.api;
import java.lang.reflect.Executable;
import java.util.List;
public interface XposedInterface {
    public interface HookHandle { void unhook(); }
    public interface HookBuilder { HookHandle intercept(Hooker hooker); }
    public interface Hooker { Object intercept(Chain chain) throws Throwable; }
    public interface Chain {
        Executable getExecutable();
        Object getThisObject();
        List<Object> getArgs();
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
    }
    HookBuilder hook(Executable origin);
    void log(int priority, String tag, String msg, Throwable tr);
}
