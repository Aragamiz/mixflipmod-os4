package io.github.libxposed.api;
public interface XposedModuleInterface {
    public interface ModuleLoadedParam {}
    public interface PackageReadyParam {
        String getPackageName();
        ClassLoader getClassLoader();
        boolean isFirstPackage();
    }
    public interface SystemServerStartingParam {
        ClassLoader getClassLoader();
    }
}
