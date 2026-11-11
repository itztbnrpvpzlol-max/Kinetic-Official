package com.aurea.aurea.engine;

public final class AureaEngine {
    static { System.loadLibrary("aurea"); }
    private final long handle;
    public AureaEngine() { handle = nativeCreate(); }
    public long handle() { return handle; }
    public boolean initialize(float refreshRate, String cachePath, String projectsPath, boolean debug, long[] memoryBytes, int[] codecIds, String pluginsPath) {
        return nativeInitialize(handle, refreshRate, cachePath, projectsPath, debug, memoryBytes, codecIds, pluginsPath);
    }
    public String startupError() { return nativeStartupError(handle); }
    public String deviceSummary() { return nativeDeviceSummary(handle); }
    public String builtinPropertySchema() { return nativeBuiltinPropertySchema(); }
    public int memoryReport(long[] values) { return nativeMemoryReport(handle, values); }
    public void shutdown() { nativeShutdown(handle); }
    public void destroy() { nativeDestroy(handle); }
    private static native long nativeCreate();
    private final native boolean nativeInitialize(long engine, float refreshRate, String cachePath, String projectsPath, boolean debug, long[] memoryBytes, int[] codecIds, String pluginsPath);
    private final native String nativeStartupError(long engine);
    private final native String nativeDeviceSummary(long engine);
    private final native String nativeBuiltinPropertySchema();
    private final native int nativeMemoryReport(long engine, long[] values);
    private final native void nativeShutdown(long engine);
    private static native void nativeDestroy(long engine);
}