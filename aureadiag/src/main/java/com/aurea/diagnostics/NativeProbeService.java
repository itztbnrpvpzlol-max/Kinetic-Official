package com.aurea.diagnostics;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo.VideoCapabilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import android.os.Build;
import android.view.Display;
import com.aurea.aurea.engine.AureaEngine;
import java.io.*;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class NativeProbeService extends Service {
    private File report;
    @Override public void onCreate() { super.onCreate(); report = new File(getFilesDir(), "aurea_native_probe.txt"); }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        new Thread(this::runProbe, "AureaNativeProbe").start();
        return START_NOT_STICKY;
    }
    private synchronized void log(String s) {
        try (FileWriter w = new FileWriter(report, true)) {
            w.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + s + "\n");
            w.flush();
        } catch (Exception ignored) {}
    }
    private void copyAsset(String name, File dst) throws Exception {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try (InputStream in = getAssets().open(name); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[32768]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); out.getFD().sync();
        }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("Cannot install " + name);
    }
    private int codecType(String mime) {
        if ("video/av01".equalsIgnoreCase(mime)) return 1635135537;
        if ("video/hevc".equalsIgnoreCase(mime)) return 1752589105;
        if ("video/avc".equalsIgnoreCase(mime)) return 1635148593;
        if ("video/x-vnd.on2.vp9".equalsIgnoreCase(mime)) return 1987063865;
        return 0;
    }
    private int profileCapability(int type, MediaCodecInfo.CodecCapabilities caps) {
        String[] fields;
        if (type == 1635135537) fields = new String[]{"AV1ProfileMain10","AV1ProfileMain10HDR10","AV1ProfileMain10HDR10Plus"};
        else if (type == 1752589105) fields = new String[]{"HEVCProfileMain10","HEVCProfileMain10HDR10","HEVCProfileMain10HDR10Plus"};
        else if (type == 1987063865) fields = new String[]{"VP9Profile2","VP9Profile3","VP9Profile2HDR","VP9Profile3HDR"};
        else return 8;
        for (MediaCodecInfo.CodecProfileLevel p : caps.profileLevels) {
            for (String field : fields) {
                try {
                    int value = MediaCodecInfo.CodecProfileLevel.class.getField(field).getInt(null);
                    if (value == p.profile) return 10;
                } catch (Throwable ignored) {}
            }
        }
        return 8;
    }
    private int[] collectCodecInfo() {
        Map<Long,int[]> best = new LinkedHashMap<>();
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                for (String mime : codec.getSupportedTypes()) {
                    int type = codecType(mime);
                    if (type == 0) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(mime);
                        VideoCapabilities video = caps.getVideoCapabilities();
                        if (video == null) continue;
                        boolean hardware;
                        if (Build.VERSION.SDK_INT >= 29) hardware = codec.isHardwareAccelerated();
                        else {
                            String name = codec.getName();
                            hardware = !(name.startsWith("OMX.google.") || name.startsWith("c2.android.")
                                    || name.startsWith("c2.google.") || name.startsWith("OMX.ffmpeg.")
                                    || name.contains(".sw."));
                        }
                        int[] entry = new int[]{
                            type, codec.isEncoder() ? 1 : 0, hardware ? 1 : 0,
                            video.getSupportedWidths().getUpper(),
                            video.getSupportedHeights().getUpper(),
                            profileCapability(type,caps), caps.getMaxSupportedInstances()
                        };
                        long key = (((long)type) << 1) | (codec.isEncoder() ? 1L : 0L);
                        int[] old = best.get(key);
                        if (old == null || entry[2] > old[2]
                                || (entry[2] == old[2] && (long)entry[3]*entry[4] > (long)old[3]*old[4])) best.put(key,entry);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        ArrayList<Integer> flat = new ArrayList<>();
        for (int[] entry : best.values()) for (int value : entry) flat.add(value);
        int[] result = new int[flat.size()];
        for (int i=0;i<flat.size();i++) result[i]=flat.get(i);
        return result;
    }
    private void runProbe() {
        try {
            if (report.exists()) report.delete();
            log("PROBE_PROCESS_STARTED");
            log("DEVICE " + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " abi=" + Build.SUPPORTED_ABIS[0]);
            File motor = new File(getCacheDir(), "motor");
            File projects = new File(getFilesDir(), "projetos");
            File plugins = new File(getFilesDir(), "plugins");
            motor.mkdirs(); projects.mkdirs(); new File(projects, ".miniaturas").mkdirs(); plugins.mkdirs();
            log("INSTALLING_FONTS");
            copyAsset("Roboto-Regular.ttf", new File(motor,"Roboto-Regular.ttf"));
            copyAsset("NotoNaskhArabic-Regular.ttf", new File(motor,"NotoNaskhArabic-Regular.ttf"));
            copyAsset("NotoSansJP-Regular.otf", new File(motor,"NotoSansJP-Regular.otf"));
            log("FONTS_READY cache=" + motor.getAbsolutePath() + " projects=" + projects.getAbsolutePath());

            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
            if (am != null) am.getMemoryInfo(mi);
            DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            float hz = display == null ? 60f : display.getRefreshRate();
            boolean debug = false; // Match the normal non-debug Aurea release configuration.

            log("LOADING_LIBAUREA");
            AureaEngine engine = new AureaEngine();
            long handle = engine.handle();
            log("NATIVE_CREATE_RETURNED handle=0x" + Long.toHexString(handle));
            if (handle == 0) { log("FAIL nativeCreate returned zero"); return; }

            int[] codecInfo = collectCodecInfo();
            log("DEVICE_CODEC_PROFILE entries=" + (codecInfo.length / 7) + " values=" + codecInfo.length);
            log("NATIVE_INITIALIZE_CALL_STARTED hz=" + hz + " totalMem=" + mi.totalMem + " availMem=" + mi.availMem + " codecInfoValues=" + codecInfo.length);
            boolean ok = engine.initialize(hz, motor.getAbsolutePath(), projects.getAbsolutePath(),
                    debug, new long[]{mi.totalMem, mi.availMem}, codecInfo, plugins.getAbsolutePath());
            log("NATIVE_INITIALIZE_RETURNED result=" + ok);
            String error = engine.startupError();
            log("NATIVE_STARTUP_ERROR " + (error == null ? "<null>" : error));
            try { log("NATIVE_DEVICE_SUMMARY " + engine.deviceSummary()); }
            catch (Throwable t) { log("DEVICE_SUMMARY_ERROR " + t); }
            try {
                long[] values = new long[16];
                int code = engine.memoryReport(values);
                StringBuilder s = new StringBuilder("NATIVE_MEMORY_REPORT code=").append(code).append(" values=");
                for (int i=0;i<values.length;i++) { if(i>0)s.append(','); s.append(values[i]); }
                log(s.toString());
            } catch (Throwable t) { log("MEMORY_REPORT_ERROR " + t); }
            try {
                String schema = engine.builtinPropertySchema();
                log("BUILTIN_PROPERTY_SCHEMA " + (schema == null ? "<null>" : "length=" + schema.length()));
            } catch (Throwable t) { log("PROPERTY_SCHEMA_ERROR " + t); }
            log("NATIVE_SHUTDOWN_STARTED");
            engine.shutdown();
            engine.destroy();
            log("NATIVE_DESTROY_COMPLETED");
            log("PROBE_COMPLETE initialized=" + ok);
        } catch (Throwable t) {
            log("JAVA_EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            for (StackTraceElement e : t.getStackTrace()) log(" at " + e);
        } finally {
            try {
                if (report.exists()) GitHubReporter.uploadIfFailureBlocking(this,
                    new String(Files.readAllBytes(report.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            stopSelf();
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}