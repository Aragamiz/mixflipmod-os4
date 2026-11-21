package io.github.libxposed.api;
import android.content.SharedPreferences;
import java.lang.reflect.Executable;
public abstract class XposedModule implements XposedInterface {
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {}
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {}
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {}
    public SharedPreferences getRemotePreferences(String group) { return null; }
    @Override public HookBuilder hook(Executable origin) { return null; }
    @Override public void log(int priority, String tag, String msg, Throwable tr) {}
}
