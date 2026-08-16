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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";
    private static final String PROPERTY_SERVICE_PATH = "/dev/socket/property_service";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd mTZService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;
    private List<String> successSockets = new ArrayList<>();

    static {
        System.loadLibrary("pocjni");
    }

    public static native ParcelFileDescriptor nativeConnectSocket(IMinkSocketFd tzService, String path, int[] handleArr);
    public static native String nativeReadFile(String path);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTZService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("[TZ] Service bound");
            updateStatus("Bound - starting tests");
            enableButtons(false, true);
            stopRequested.set(false);
            testThread = new Thread(() -> executeFullTest());
            testThread.start();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTZService = null;
            isBound = false;
            enableButtons(true, false);
            updateStatus("TZ disconnected");
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
            intent.setClassName(TARGET_PKG_TZ, TARGET_CLS_TZ);
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

    private void executeFullTest() {
        appendLog("========================================");
        appendLog("========== TZAccess Socket Scan ==========");

        String[] allSockets = {
            "/dev/socket/mdnsd",
            "/dev/socket/tcm",
            "/dev/socket/fwmarkd",
            "/dev/socket/dnsproxyd",
            "/dev/socket/logd",
            PROPERTY_SERVICE_PATH,
            "/dev/socket/ssgqmig",
            "/dev/socket/minksocket",
            "/dev/socket/netd",
            "/dev/socket/location",
            "/dev/socket/zygote",
            "/dev/socket/zygote_secondary"
        };

        for (String path : allSockets) {
            if (stopRequested.get()) break;
            testSocketJava(path);
        }

        appendLog("========== SUCCESSFUL SOCKETS (Java) ==========");
        if (successSockets.isEmpty()) {
            appendLog("  No successful sockets");
        } else {
            for (String s : successSockets) {
                appendLog("  " + s);
            }
        }

        appendLog("========== Testing via JNI ==========");
        if (mTZService != null && !successSockets.isEmpty()) {
            for (String path : successSockets) {
                if (stopRequested.get()) break;
                testSocketJNI(path);
            }
        } else {
            appendLog("  No successful sockets or TZ service null");
        }

        appendLog("========== JNI File Read Test ==========");
        String[] files = {"/proc/version", "/proc/self/status"};
        for (String f : files) {
            if (stopRequested.get()) break;
            try {
                String content = nativeReadFile(f);
                if (content != null && !content.isEmpty()) {
                    appendLog("[JNI] Read " + f + ": " + content.substring(0, Math.min(200, content.length())));
                } else {
                    appendLog("[JNI] Failed to read " + f + " (empty or null)");
                }
            } catch (Exception e) {
                appendLog("[JNI] Read " + f + " error: " + e.getMessage());
            }
        }

        if (!successSockets.isEmpty()) {
            appendLog("========== Interacting with successful sockets ==========");
            for (String s : successSockets) {
                if (stopRequested.get()) break;
                if (s.equals(PROPERTY_SERVICE_PATH)) continue;
                interactWithSocket(s);
            }
        }

        appendLog("========== Hello Tests on Specific Sockets ==========");
        testHelloOnSockets();

        if (successSockets.contains(PROPERTY_SERVICE_PATH)) {
            appendLog("========== Property Service Read-Only Test ==========");
            testPropertyServiceReadOnly();
        }

        appendLog("========== Advanced Device Tests (prop, qseecom, block) ==========");
        testAdvancedDevices();

        appendLog("========== ALL TESTS COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void testSocketJava(String path) {
        appendLog("[SCAN-JAVA] Testing: " + path);
        if (mTZService == null) {
            appendLog("  TZ service null");
            return;
        }
        try {
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = mTZService.a(path, iArr);
            if (pfd == null) {
                appendLog("  [FAIL] No FD");
                return;
            }
            appendLog("  [SUCCESS] Got FD: " + iArr[0]);
            successSockets.add(path);
            pfd.close();
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        } catch (Exception e) {
            appendLog("  Error: " + e.getMessage());
        }
    }

    private void testSocketJNI(String path) {
        appendLog("[SCAN-JNI] Testing: " + path);
        if (mTZService == null) {
            appendLog("  TZ service null");
            return;
        }
        try {
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = nativeConnectSocket(mTZService, path, iArr);
            if (pfd == null) {
                appendLog("  [FAIL] No FD from JNI");
                return;
            }
            appendLog("  [SUCCESS] JNI got FD: " + iArr[0]);
            pfd.close();
        } catch (Exception e) {
            appendLog("  JNI error: " + e.getMessage());
        }
    }

    private void interactWithSocket(String path) {
        appendLog("[INTERACT] " + path);
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a(path, iArr);
            if (pfd == null) {
                appendLog("  Re-connect failed");
                return;
            }
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  FD invalid");
                pfd.close();
                return;
            }

            OutputStream os = new FileOutputStream(fdesc);
            InputStream is = new FileInputStream(fdesc);

            String[] cmds = {"help", "status", "version", "getprop", "list", "dump", "\n"};
            for (String cmd : cmds) {
                if (stopRequested.get()) break;
                try {
                    os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    String resp = readWithTimeout(is, 500);
                    if (resp != null && !resp.isEmpty()) {
                        appendLog("  CMD[" + cmd + "] -> " + resp);
                    } else {
                        appendLog("  CMD[" + cmd + "] -> (no response)");
                    }
                } catch (Exception e) {
                    appendLog("  CMD[" + cmd + "] error: " + e.getMessage());
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            os.close();
            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("  Interaction error: " + e.toString());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private String readWithTimeout(InputStream is, int timeoutMs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long start = System.currentTimeMillis();
        byte[] buffer = new byte[512];
        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (is.available() > 0) {
                    int len = is.read(buffer);
                    if (len > 0) baos.write(buffer, 0, len);
                    else break;
                } else {
                    Thread.sleep(30);
                }
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
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
            File file = new File(dir, "tz_jni_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZ JNI PoC Log ===");
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
            Toast.makeText(MainActivity.this, "検査が終了したから終了しま~す", Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finishAffinity();
                System.exit(0);
            }, 2000);
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

    private void testHelloOnSockets() {
        String[] targetSockets = {
            "/dev/socket/logd",
            "/dev/socket/dnsproxyd",
            "/dev/socket/fwmarkd",
            "/dev/socket/mdnsd",
            "/dev/socket/tcm"
        };

        for (String path : targetSockets) {
            if (stopRequested.get()) break;
            if (!successSockets.contains(path)) continue;
            appendLog("[HELLO-TEST] Testing " + path);
            sendHelloToSocket(path);
        }
    }

    private void sendHelloToSocket(String path) {
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a(path, iArr);
            if (pfd == null) {
                appendLog("  Failed to get FD");
                return;
            }
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  Invalid FD");
                return;
            }

            OutputStream os = new FileOutputStream(fdesc);
            InputStream is = new FileInputStream(fdesc);

            String[] commands = {
                "hello\n",
                "HELLO\n",
                "hello world\n",
                "HELLO WORLD\n"
            };

            boolean responded = false;
            for (String cmd : commands) {
                if (stopRequested.get()) break;
                try {
                    os.write(cmd.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    String resp = readWithTimeout(is, 500);
                    if (resp != null && !resp.isEmpty()) {
                        appendLog("  CMD[" + cmd.trim() + "] -> " + resp);
                        responded = true;
                        break;
                    } else {
                        appendLog("  CMD[" + cmd.trim() + "] -> (no response)");
                    }
                } catch (Exception e) {
                    appendLog("  CMD[" + cmd.trim() + "] error: " + e.getMessage());
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }

            if (!responded) {
                appendLog("  No response to any hello command");
            }

            os.close();
            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("  Hello test error: " + e.toString());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void testPropertyServiceReadOnly() {
        appendLog("[PROP-READ] Reading properties via SystemProperties");
        String[] testProps = {
            "sys.retaildemo.enabled",
            "persist.sys.timezone",
            "ro.build.version.release",
            "ro.product.model",
            "persist.sys.language"
        };

        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method getMethod = spClass.getMethod("get", String.class);
            for (String prop : testProps) {
                String value = (String) getMethod.invoke(null, prop);
                appendLog("[PROP] " + prop + " = " + (value != null ? value : "(null)"));
            }
        } catch (Exception e) {
            appendLog("[PROP-READ] Reflection error: " + e.getMessage());
        }
    }

    private void testAdvancedDevices() {
        testPropertiesFs();
        testQseecom();
        testBlockDevices();
    }

    private void testPropertiesFs() {
        appendLog("[ADV] Testing /dev/__properties__ filesystem");
        String[] propFiles = {
            "/dev/__properties__/persist.sys.timezone",
            "/dev/__properties__/ro.build.version.release",
            "/dev/__properties__/sys.retaildemo.enabled",
            "/dev/__properties__/persist.sys.language"
        };

        for (String path : propFiles) {
            if (stopRequested.get()) break;
            appendLog("[PROP-FS] Trying " + path);
            ParcelFileDescriptor pfd = null;
            try {
                int[] iArr = new int[1];
                pfd = mTZService.a(path, iArr);
                if (pfd == null) {
                    appendLog("  Cannot open (null FD)");
                    continue;
                }
                java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                if (fdesc == null || !fdesc.valid()) {
                    appendLog("  Invalid FD");
                    pfd.close();
                    continue;
                }

                // Read
                FileInputStream fis = new FileInputStream(fdesc);
                byte[] buffer = new byte[256];
                int len = fis.read(buffer);
                if (len > 0) {
                    String content = new String(buffer, 0, len, StandardCharsets.UTF_8).trim();
                    appendLog("  Read: " + content);
                } else {
                    appendLog("  Read returned " + len);
                }

                // Write test (preserve original)
                if (len > 0) {
                    String original = new String(buffer, 0, len, StandardCharsets.UTF_8).trim();
                    String newValue = "TEST_" + System.currentTimeMillis();
                    // Write back original to avoid corruption, but test write capability
                    // First write new value
                    FileOutputStream fos = new FileOutputStream(fdesc);
                    fos.write(newValue.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    appendLog("  Wrote: " + newValue);
                    // Read back to verify
                    fis = new FileInputStream(fdesc);
                    len = fis.read(buffer);
                    if (len > 0) {
                        String readBack = new String(buffer, 0, len, StandardCharsets.UTF_8).trim();
                        appendLog("  Read back: " + readBack);
                    }
                    // Restore original
                    fos = new FileOutputStream(fdesc);
                    fos.write(original.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    appendLog("  Restored original");
                }
                fis.close();
                pfd.close();
            } catch (Exception e) {
                appendLog("  Error: " + e.getMessage());
                if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void testQseecom() {
        appendLog("[ADV] Testing /dev/qseecom (dummy I/O)");
        String path = "/dev/qseecom";
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a(path, iArr);
            if (pfd == null) {
                appendLog("  Cannot open (null FD)");
                return;
            }
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  Invalid FD");
                pfd.close();
                return;
            }

            OutputStream os = new FileOutputStream(fdesc);
            InputStream is = new FileInputStream(fdesc);

            // Send dummy commands
            byte[][] dummies = {
                "hello".getBytes(StandardCharsets.UTF_8),
                "status".getBytes(StandardCharsets.UTF_8),
                "version".getBytes(StandardCharsets.UTF_8),
                "\x00\x01\x02\x03".getBytes(StandardCharsets.ISO_8859_1)
            };

            for (byte[] data : dummies) {
                if (stopRequested.get()) break;
                try {
                    os.write(data);
                    os.flush();
                    byte[] resp = new byte[64];
                    int len = is.read(resp);
                    if (len > 0) {
                        String respStr = new String(resp, 0, len, StandardCharsets.UTF_8);
                        appendLog("  Write (" + data.length + " bytes) -> read " + len + " bytes: " + respStr);
                    } else {
                        appendLog("  Write (" + data.length + " bytes) -> no response");
                    }
                } catch (Exception e) {
                    appendLog("  I/O error: " + e.getMessage());
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }

            os.close();
            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("  Error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void testBlockDevices() {
        String[] blockDevices = {
            "/dev/block/mmcblk0p29",
            "/dev/block/mmcblk0p18",
            "/dev/block/mmcblk0p21"
        };

        for (String path : blockDevices) {
            if (stopRequested.get()) break;
            appendLog("[ADV] Testing block device " + path);
            ParcelFileDescriptor pfd = null;
            try {
                int[] iArr = new int[1];
                pfd = mTZService.a(path, iArr);
                if (pfd == null) {
                    appendLog("  Cannot open (null FD)");
                    continue;
                }
                java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                if (fdesc == null || !fdesc.valid()) {
                    appendLog("  Invalid FD");
                    pfd.close();
                    continue;
                }

                FileInputStream fis = new FileInputStream(fdesc);
                FileOutputStream fos = new FileOutputStream(fdesc);

                // Read first 512 bytes (sector)
                byte[] buffer = new byte[512];
                int len = fis.read(buffer);
                if (len > 0) {
                    appendLog("  Read " + len + " bytes from start");
                    // Show first few bytes as hex
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < Math.min(16, len); i++) {
                        hex.append(String.format("%02x ", buffer[i]));
                    }
                    appendLog("  First bytes: " + hex.toString());

                    // Write test: write the same data back (safe)
                    fos.write(buffer, 0, len);
                    fos.flush();
                    appendLog("  Wrote " + len + " bytes back (restored)");

                    // Optionally, try writing a small marker at offset 0 (but restore)
                    byte[] marker = "HELLO".getBytes(StandardCharsets.UTF_8);
                    // Save original first 5 bytes
                    byte[] orig5 = new byte[5];
                    System.arraycopy(buffer, 0, orig5, 0, 5);
                    // Write marker
                    fos = new FileOutputStream(fdesc);
                    fos.write(marker);
                    fos.flush();
                    appendLog("  Wrote marker 'HELLO' at offset 0");

                    // Read back to verify
                    fis = new FileInputStream(fdesc);
                    byte[] check = new byte[5];
                    fis.read(check);
                    String checkStr = new String(check, StandardCharsets.UTF_8);
                    appendLog("  Read back: " + checkStr);

                    // Restore original
                    fos = new FileOutputStream(fdesc);
                    fos.write(orig5);
                    fos.flush();
                    appendLog("  Restored original 5 bytes");
                } else {
                    appendLog("  Read returned " + len);
                }

                fis.close();
                fos.close();
                pfd.close();
            } catch (Exception e) {
                appendLog("  Error: " + e.getMessage());
                if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }
}
